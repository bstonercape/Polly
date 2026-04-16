package org.thoughtcrime.securesms.account.protocol

import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.signal.core.models.ServiceId
import org.signal.core.util.Base64
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import org.whispersystems.signalservice.api.SignalServicePreKeyStore
import org.whispersystems.signalservice.api.SignalServiceKyberPreKeyStore

/**
 * Lightweight pre-key store that reads/writes one-time, signed, and Kyber pre-keys
 * directly from a raw SQLCipher handle. Independent of [SignalDatabase].
 */
class BackgroundPreKeyStore(
  private val db: SQLiteDatabase,
  private val serviceId: ServiceId
) : SignalServicePreKeyStore, SignedPreKeyStore, SignalServiceKyberPreKeyStore {

  private val accountId: String = when (serviceId) {
    is ServiceId.ACI -> serviceId.toString()
    is ServiceId.PNI -> "PNI"
  }

  // --- One-time pre-keys ---

  override fun loadPreKey(preKeyId: Int): PreKeyRecord {
    db.query("one_time_prekeys", arrayOf("public_key", "private_key"),
      "account_id = ? AND key_id = ?", arrayOf(accountId, preKeyId.toString()),
      null, null, null).use { cursor ->
      if (cursor.moveToFirst()) {
        try {
          val publicKey = ECPublicKey(Base64.decode(cursor.getString(0)))
          val privateKey = ECPrivateKey(Base64.decode(cursor.getString(1)))
          return PreKeyRecord(preKeyId, ECKeyPair(publicKey, privateKey))
        } catch (e: Exception) {
          Log.w(TAG, "Failed to load pre-key $preKeyId", e)
        }
      }
    }
    throw InvalidKeyIdException("No one-time pre-key: $preKeyId")
  }

  override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
    db.compileStatement(
      "INSERT OR REPLACE INTO one_time_prekeys (account_id, key_id, public_key, private_key) VALUES (?, ?, ?, ?)"
    ).use { stmt ->
      stmt.bindString(1, accountId)
      stmt.bindLong(2, preKeyId.toLong())
      stmt.bindString(3, Base64.encodeWithPadding(record.keyPair.publicKey.serialize()))
      stmt.bindString(4, Base64.encodeWithPadding(record.keyPair.privateKey.serialize()))
      stmt.execute()
    }
  }

  override fun containsPreKey(preKeyId: Int): Boolean {
    db.query("one_time_prekeys", arrayOf("key_id"),
      "account_id = ? AND key_id = ?", arrayOf(accountId, preKeyId.toString()),
      null, null, null).use { cursor ->
      return cursor.moveToFirst()
    }
  }

  override fun removePreKey(preKeyId: Int) {
    db.delete("one_time_prekeys", "account_id = ? AND key_id = ?",
      arrayOf(accountId, preKeyId.toString()))
  }

  override fun markAllOneTimeEcPreKeysStaleIfNecessary(staleTime: Long) {
    // Not needed for background decryption
  }

  override fun deleteAllStaleOneTimeEcPreKeys(threshold: Long, minCount: Int) {
    // Not needed for background decryption
  }

  // --- Signed pre-keys ---

  override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
    db.query("signed_prekeys", arrayOf("public_key", "private_key", "signature", "timestamp"),
      "account_id = ? AND key_id = ?", arrayOf(accountId, signedPreKeyId.toString()),
      null, null, null).use { cursor ->
      if (cursor.moveToFirst()) {
        try {
          val publicKey = ECPublicKey(Base64.decode(cursor.getString(0)))
          val privateKey = ECPrivateKey(Base64.decode(cursor.getString(1)))
          val signature = Base64.decode(cursor.getString(2))
          val timestamp = cursor.getLong(3)
          return SignedPreKeyRecord(signedPreKeyId, timestamp, ECKeyPair(publicKey, privateKey), signature)
        } catch (e: Exception) {
          Log.w(TAG, "Failed to load signed pre-key $signedPreKeyId", e)
        }
      }
    }
    throw InvalidKeyIdException("No signed pre-key: $signedPreKeyId")
  }

  override fun loadSignedPreKeys(): List<SignedPreKeyRecord> {
    val results = mutableListOf<SignedPreKeyRecord>()
    db.query("signed_prekeys", arrayOf("key_id", "public_key", "private_key", "signature", "timestamp"),
      "account_id = ?", arrayOf(accountId), null, null, null).use { cursor ->
      while (cursor.moveToNext()) {
        try {
          val keyId = cursor.getInt(0)
          val publicKey = ECPublicKey(Base64.decode(cursor.getString(1)))
          val privateKey = ECPrivateKey(Base64.decode(cursor.getString(2)))
          val signature = Base64.decode(cursor.getString(3))
          val timestamp = cursor.getLong(4)
          results.add(SignedPreKeyRecord(keyId, timestamp, ECKeyPair(publicKey, privateKey), signature))
        } catch (e: Exception) {
          Log.w(TAG, "Failed to deserialize signed pre-key", e)
        }
      }
    }
    return results
  }

  override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
    db.compileStatement(
      "INSERT OR REPLACE INTO signed_prekeys (account_id, key_id, public_key, private_key, signature, timestamp) VALUES (?, ?, ?, ?, ?, ?)"
    ).use { stmt ->
      stmt.bindString(1, accountId)
      stmt.bindLong(2, signedPreKeyId.toLong())
      stmt.bindString(3, Base64.encodeWithPadding(record.keyPair.publicKey.serialize()))
      stmt.bindString(4, Base64.encodeWithPadding(record.keyPair.privateKey.serialize()))
      stmt.bindString(5, Base64.encodeWithPadding(record.signature))
      stmt.bindLong(6, record.timestamp)
      stmt.execute()
    }
  }

  override fun containsSignedPreKey(signedPreKeyId: Int): Boolean {
    db.query("signed_prekeys", arrayOf("key_id"),
      "account_id = ? AND key_id = ?", arrayOf(accountId, signedPreKeyId.toString()),
      null, null, null).use { cursor ->
      return cursor.moveToFirst()
    }
  }

  override fun removeSignedPreKey(signedPreKeyId: Int) {
    db.delete("signed_prekeys", "account_id = ? AND key_id = ?",
      arrayOf(accountId, signedPreKeyId.toString()))
  }

  // --- Kyber pre-keys ---

  override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
    db.query("kyber_prekey", arrayOf("serialized"),
      "account_id = ? AND key_id = ?", arrayOf(accountId, kyberPreKeyId.toString()),
      null, null, null).use { cursor ->
      if (cursor.moveToFirst()) {
        return KyberPreKeyRecord(cursor.getBlob(0))
      }
    }
    throw InvalidKeyIdException("No Kyber pre-key: $kyberPreKeyId")
  }

  override fun loadKyberPreKeys(): List<KyberPreKeyRecord> {
    val results = mutableListOf<KyberPreKeyRecord>()
    db.query("kyber_prekey", arrayOf("serialized"),
      "account_id = ?", arrayOf(accountId), null, null, null).use { cursor ->
      while (cursor.moveToNext()) {
        results.add(KyberPreKeyRecord(cursor.getBlob(0)))
      }
    }
    return results
  }

  override fun loadLastResortKyberPreKeys(): List<KyberPreKeyRecord> {
    val results = mutableListOf<KyberPreKeyRecord>()
    db.query("kyber_prekey", arrayOf("serialized"),
      "account_id = ? AND last_resort = 1", arrayOf(accountId), null, null, null).use { cursor ->
      while (cursor.moveToNext()) {
        results.add(KyberPreKeyRecord(cursor.getBlob(0)))
      }
    }
    return results
  }

  override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
    db.compileStatement(
      "INSERT OR REPLACE INTO kyber_prekey (account_id, key_id, timestamp, last_resort, serialized) VALUES (?, ?, ?, 0, ?)"
    ).use { stmt ->
      stmt.bindString(1, accountId)
      stmt.bindLong(2, kyberPreKeyId.toLong())
      stmt.bindLong(3, System.currentTimeMillis())
      stmt.bindBlob(4, record.serialize())
      stmt.execute()
    }
  }

  override fun storeLastResortKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
    db.compileStatement(
      "INSERT OR REPLACE INTO kyber_prekey (account_id, key_id, timestamp, last_resort, serialized) VALUES (?, ?, ?, 1, ?)"
    ).use { stmt ->
      stmt.bindString(1, accountId)
      stmt.bindLong(2, kyberPreKeyId.toLong())
      stmt.bindLong(3, System.currentTimeMillis())
      stmt.bindBlob(4, record.serialize())
      stmt.execute()
    }
  }

  override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean {
    db.query("kyber_prekey", arrayOf("key_id"),
      "account_id = ? AND key_id = ?", arrayOf(accountId, kyberPreKeyId.toString()),
      null, null, null).use { cursor ->
      return cursor.moveToFirst()
    }
  }

  override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedKeyId: Int, publicKey: ECPublicKey) {
    // No-op for background — full processing handles this on account activation
  }

  override fun removeKyberPreKey(kyberPreKeyId: Int) {
    db.delete("kyber_prekey", "account_id = ? AND key_id = ?",
      arrayOf(accountId, kyberPreKeyId.toString()))
  }

  override fun markAllOneTimeKyberPreKeysStaleIfNecessary(staleTime: Long) {
    // Not needed for background decryption
  }

  override fun deleteAllStaleOneTimeKyberPreKeys(threshold: Long, minCount: Int) {
    // Not needed for background decryption
  }

  companion object {
    private val TAG = Log.tag(BackgroundPreKeyStore::class.java)
  }
}
