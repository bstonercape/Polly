package org.thoughtcrime.securesms.account.protocol

import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.whispersystems.signalservice.api.SignalServiceSenderKeyStore
import org.whispersystems.signalservice.api.push.DistributionId
import java.util.UUID

/**
 * Lightweight [SignalServiceSenderKeyStore] for group message decryption.
 * Reads/writes sender key state directly from a raw SQLCipher handle.
 */
class BackgroundSenderKeyStore(
  private val db: SQLiteDatabase
) : SignalServiceSenderKeyStore {

  override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
    db.compileStatement(
      "INSERT OR REPLACE INTO sender_keys (address, device, distribution_id, record, created_at) VALUES (?, ?, ?, ?, ?)"
    ).use { stmt ->
      stmt.bindString(1, sender.name)
      stmt.bindLong(2, sender.deviceId.toLong())
      stmt.bindString(3, distributionId.toString())
      stmt.bindBlob(4, record.serialize())
      stmt.bindLong(5, System.currentTimeMillis())
      stmt.execute()
    }
  }

  override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? {
    db.query("sender_keys", arrayOf("record"),
      "address = ? AND device = ? AND distribution_id = ?",
      arrayOf(sender.name, sender.deviceId.toString(), distributionId.toString()),
      null, null, null).use { cursor ->
      if (cursor.moveToFirst()) {
        try {
          return SenderKeyRecord(cursor.getBlob(0))
        } catch (e: InvalidMessageException) {
          Log.w(TAG, "Failed to deserialize sender key", e)
        }
      }
    }
    return null
  }

  override fun getSenderKeySharedWith(distributionId: DistributionId): Set<SignalProtocolAddress> {
    // Not needed for background decryption (only used when sending)
    return emptySet()
  }

  override fun markSenderKeySharedWith(distributionId: DistributionId, addresses: Collection<SignalProtocolAddress>) {
    // Not needed for background decryption
  }

  override fun clearSenderKeySharedWith(addresses: Collection<SignalProtocolAddress>) {
    // Not needed for background decryption
  }

  companion object {
    private val TAG = Log.tag(BackgroundSenderKeyStore::class.java)
  }
}
