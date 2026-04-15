package org.thoughtcrime.securesms.account.protocol

import android.app.Application
import org.signal.core.models.ServiceId
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.ServiceId.PNI
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.thoughtcrime.securesms.account.AccountRegistry
import org.thoughtcrime.securesms.account.BackgroundDatabaseHandle
import org.whispersystems.signalservice.api.SignalServiceAccountDataStore
import org.whispersystems.signalservice.api.SignalServiceDataStore
import org.whispersystems.signalservice.api.push.DistributionId
import java.io.Closeable
import java.util.UUID

/**
 * Composes the lightweight protocol stores into a complete [SignalServiceDataStore]
 * for a background account. Routes between ACI and PNI sub-stores, just like the
 * main [SignalServiceDataStoreImpl].
 *
 * Create via [BackgroundProtocolStore.create], which opens the database and builds
 * all sub-stores. Must be [close]d when no longer needed.
 */
class BackgroundProtocolStore private constructor(
  private val dbHandle: BackgroundDatabaseHandle,
  private val aci: ACI,
  private val pni: PNI?,
  private val aciStore: BackgroundAccountDataStore,
  private val pniStore: BackgroundAccountDataStore?,
  private val isMultiDevice: Boolean
) : SignalServiceDataStore, Closeable {

  override fun get(accountIdentifier: ServiceId): SignalServiceAccountDataStore {
    return when {
      accountIdentifier == aci -> aciStore
      pni != null && accountIdentifier == pni -> pniStore ?: throw IllegalArgumentException("PNI store not available")
      else -> throw IllegalArgumentException("No matching store for $accountIdentifier")
    }
  }

  override fun aci(): SignalServiceAccountDataStore = aciStore
  override fun pni(): SignalServiceAccountDataStore = pniStore ?: throw IllegalStateException("PNI store not available")
  override fun isMultiDevice(): Boolean = isMultiDevice

  /** Returns the raw database handle for direct SQL operations (message insertion, etc.) */
  val database get() = dbHandle.db

  override fun close() {
    dbHandle.close()
  }

  companion object {
    private val TAG = Log.tag(BackgroundProtocolStore::class.java)

    /**
     * Creates a complete [BackgroundProtocolStore] for a background account.
     * Opens the account's signal.db and builds all protocol sub-stores.
     *
     * @return The store, or null if the entry lacks required credentials/keys
     */
    fun create(application: Application, entry: AccountRegistry.AccountEntry): BackgroundProtocolStore? {
      val aci = ACI.parseOrNull(entry.aci) ?: return null
      val pni = entry.pni?.let { PNI.parseOrNull(it) }

      if (!entry.hasIdentityKeys) {
        Log.w(TAG, "Cannot create protocol store for ${entry.accountId}: no identity keys")
        return null
      }

      val dbHandle = BackgroundDatabaseHandle(application, entry.accountId)
      val db = dbHandle.db

      val aciIdentityStore = BackgroundIdentityKeyStore.forAci(db, entry) ?: run {
        dbHandle.close()
        return null
      }
      val aciSessionStore = BackgroundSessionStore(db, aci)
      val aciPreKeyStore = BackgroundPreKeyStore(db, aci)
      val aciSenderKeyStore = BackgroundSenderKeyStore(db)
      val aciStore = BackgroundAccountDataStore(
        aciIdentityStore, aciSessionStore, aciPreKeyStore, aciSenderKeyStore
      )

      val pniStore = pni?.let {
        val pniIdentityStore = BackgroundIdentityKeyStore.forPni(db, entry) ?: return@let null
        val pniSessionStore = BackgroundSessionStore(db, it)
        val pniPreKeyStore = BackgroundPreKeyStore(db, it)
        BackgroundAccountDataStore(
          pniIdentityStore, pniSessionStore, pniPreKeyStore, aciSenderKeyStore
        )
      }

      Log.i(TAG, "Created background protocol store for ${entry.accountId}")
      return BackgroundProtocolStore(dbHandle, aci, pni, aciStore, pniStore, entry.deviceId != 1)
    }
  }
}

/**
 * Single-identity (ACI or PNI) account data store composed of the lightweight background stores.
 * Implements [SignalServiceAccountDataStore] by delegating to the individual stores.
 */
class BackgroundAccountDataStore(
  private val identityStore: BackgroundIdentityKeyStore,
  private val sessionStore: BackgroundSessionStore,
  private val preKeyStore: BackgroundPreKeyStore,
  private val senderKeyStore: BackgroundSenderKeyStore
) : SignalServiceAccountDataStore {

  // Identity
  override fun getIdentityKeyPair(): IdentityKeyPair = identityStore.getIdentityKeyPair()
  override fun getLocalRegistrationId(): Int = identityStore.getLocalRegistrationId()
  override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange = identityStore.saveIdentity(address, identityKey)
  override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean = identityStore.isTrustedIdentity(address, identityKey, direction)
  override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = identityStore.getIdentity(address)

  // Sessions
  override fun loadSession(address: SignalProtocolAddress): SessionRecord = sessionStore.loadSession(address)
  override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> = sessionStore.loadExistingSessions(addresses)
  override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) = sessionStore.storeSession(address, record)
  override fun containsSession(address: SignalProtocolAddress): Boolean = sessionStore.containsSession(address)
  override fun deleteSession(address: SignalProtocolAddress) = sessionStore.deleteSession(address)
  override fun deleteAllSessions(name: String) = sessionStore.deleteAllSessions(name)
  override fun getSubDeviceSessions(name: String): List<Int> = sessionStore.getSubDeviceSessions(name)
  override fun archiveSession(address: SignalProtocolAddress) = sessionStore.archiveSession(address)
  override fun getAllAddressesWithActiveSessions(addressNames: List<String>): Map<SignalProtocolAddress, SessionRecord> = sessionStore.getAllAddressesWithActiveSessions(addressNames)

  // One-time pre-keys
  override fun loadPreKey(preKeyId: Int): PreKeyRecord = preKeyStore.loadPreKey(preKeyId)
  override fun storePreKey(preKeyId: Int, record: PreKeyRecord) = preKeyStore.storePreKey(preKeyId, record)
  override fun containsPreKey(preKeyId: Int): Boolean = preKeyStore.containsPreKey(preKeyId)
  override fun removePreKey(preKeyId: Int) = preKeyStore.removePreKey(preKeyId)
  override fun markAllOneTimeEcPreKeysStaleIfNecessary(staleTime: Long) = preKeyStore.markAllOneTimeEcPreKeysStaleIfNecessary(staleTime)
  override fun deleteAllStaleOneTimeEcPreKeys(threshold: Long, minCount: Int) = preKeyStore.deleteAllStaleOneTimeEcPreKeys(threshold, minCount)

  // Signed pre-keys
  override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord = preKeyStore.loadSignedPreKey(signedPreKeyId)
  override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = preKeyStore.loadSignedPreKeys()
  override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) = preKeyStore.storeSignedPreKey(signedPreKeyId, record)
  override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = preKeyStore.containsSignedPreKey(signedPreKeyId)
  override fun removeSignedPreKey(signedPreKeyId: Int) = preKeyStore.removeSignedPreKey(signedPreKeyId)

  // Kyber pre-keys
  override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord = preKeyStore.loadKyberPreKey(kyberPreKeyId)
  override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = preKeyStore.loadKyberPreKeys()
  override fun loadLastResortKyberPreKeys(): List<KyberPreKeyRecord> = preKeyStore.loadLastResortKyberPreKeys()
  override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) = preKeyStore.storeKyberPreKey(kyberPreKeyId, record)
  override fun storeLastResortKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) = preKeyStore.storeLastResortKyberPreKey(kyberPreKeyId, record)
  override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = preKeyStore.containsKyberPreKey(kyberPreKeyId)
  override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedKeyId: Int, publicKey: ECPublicKey) = preKeyStore.markKyberPreKeyUsed(kyberPreKeyId, signedKeyId, publicKey)
  override fun removeKyberPreKey(kyberPreKeyId: Int) = preKeyStore.removeKyberPreKey(kyberPreKeyId)
  override fun markAllOneTimeKyberPreKeysStaleIfNecessary(staleTime: Long) = preKeyStore.markAllOneTimeKyberPreKeysStaleIfNecessary(staleTime)
  override fun deleteAllStaleOneTimeKyberPreKeys(threshold: Long, minCount: Int) = preKeyStore.deleteAllStaleOneTimeKyberPreKeys(threshold, minCount)

  // Sender keys
  override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) = senderKeyStore.storeSenderKey(sender, distributionId, record)
  override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? = senderKeyStore.loadSenderKey(sender, distributionId)
  override fun getSenderKeySharedWith(distributionId: DistributionId): Set<SignalProtocolAddress> = senderKeyStore.getSenderKeySharedWith(distributionId)
  override fun markSenderKeySharedWith(distributionId: DistributionId, addresses: Collection<SignalProtocolAddress>) = senderKeyStore.markSenderKeySharedWith(distributionId, addresses)
  override fun clearSenderKeySharedWith(addresses: Collection<SignalProtocolAddress>) = senderKeyStore.clearSenderKeySharedWith(addresses)

  // Multi-device
  override fun isMultiDevice(): Boolean = false
}
