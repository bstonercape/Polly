package org.thoughtcrime.securesms.account.protocol

import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.signal.core.util.Base64
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.thoughtcrime.securesms.account.AccountRegistry

/**
 * Lightweight [IdentityKeyStore] backed by a raw SQLCipher database handle and cached
 * identity keys from [AccountRegistry]. Independent of [SignalDatabase] and [SignalStore].
 *
 * The own identity key pair comes from the cached registry entry (avoiding a second DB open).
 * Peer identity keys are read from the `identities` table in the account's signal.db.
 */
class BackgroundIdentityKeyStore(
  private val db: SQLiteDatabase,
  private val ownIdentityKeyPair: IdentityKeyPair,
  private val localRegistrationId: Int
) : IdentityKeyStore {

  override fun getIdentityKeyPair(): IdentityKeyPair = ownIdentityKeyPair

  override fun getLocalRegistrationId(): Int = localRegistrationId

  override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
    val existing = getIdentity(address)

    if (existing == null) {
      // New identity — insert
      db.compileStatement(
        "INSERT OR IGNORE INTO identities (address, identity_key, first_use, timestamp, verified, nonblocking_approval) " +
          "VALUES (?, ?, 1, ?, 0, 0)"
      ).use { stmt ->
        stmt.bindString(1, address.name)
        stmt.bindString(2, Base64.encodeWithPadding(identityKey.serialize()))
        stmt.bindLong(3, System.currentTimeMillis())
        stmt.execute()
      }
      return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
    }

    if (existing != identityKey) {
      // Identity changed — update
      db.compileStatement(
        "UPDATE identities SET identity_key = ?, timestamp = ?, first_use = 0 WHERE address = ?"
      ).use { stmt ->
        stmt.bindString(1, Base64.encodeWithPadding(identityKey.serialize()))
        stmt.bindLong(2, System.currentTimeMillis())
        stmt.bindString(3, address.name)
        stmt.execute()
      }
      return IdentityKeyStore.IdentityChange.REPLACED_EXISTING
    }

    return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
  }

  override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean {
    // For background decryption, trust all identities. Full identity verification
    // happens when the account becomes active and deferred envelopes are processed.
    // See security tradeoffs in docs/background-receivers-plan.md.
    return true
  }

  override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
    db.query("identities", arrayOf("identity_key"),
      "address = ?", arrayOf(address.name),
      null, null, null).use { cursor ->
      if (cursor.moveToFirst()) {
        val keyString = cursor.getString(0) ?: return null
        try {
          return IdentityKey(Base64.decode(keyString), 0)
        } catch (e: Exception) {
          Log.w(TAG, "Failed to deserialize identity key for ${address.name}", e)
        }
      }
    }
    return null
  }

  companion object {
    private val TAG = Log.tag(BackgroundIdentityKeyStore::class.java)

    /**
     * Creates a [BackgroundIdentityKeyStore] from a cached [AccountRegistry.AccountEntry],
     * using the ACI identity key pair. Returns null if the entry lacks identity keys.
     */
    fun forAci(db: SQLiteDatabase, entry: AccountRegistry.AccountEntry): BackgroundIdentityKeyStore? {
      if (!entry.hasIdentityKeys) return null
      val keyPair = IdentityKeyPair(
        IdentityKey(entry.aciIdentityPublicKey!!),
        org.signal.libsignal.protocol.ecc.ECPrivateKey(entry.aciIdentityPrivateKey!!)
      )
      return BackgroundIdentityKeyStore(db, keyPair, entry.registrationId)
    }

    /**
     * Creates a [BackgroundIdentityKeyStore] for the PNI identity, if available.
     */
    fun forPni(db: SQLiteDatabase, entry: AccountRegistry.AccountEntry): BackgroundIdentityKeyStore? {
      if (entry.pniIdentityPublicKey == null || entry.pniIdentityPrivateKey == null) return null
      val keyPair = IdentityKeyPair(
        IdentityKey(entry.pniIdentityPublicKey),
        org.signal.libsignal.protocol.ecc.ECPrivateKey(entry.pniIdentityPrivateKey)
      )
      // PNI registration ID is separate, but we don't cache it — use 0 as fallback
      return BackgroundIdentityKeyStore(db, keyPair, 0)
    }
  }
}
