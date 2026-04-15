package org.thoughtcrime.securesms.account.protocol

import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.signal.core.models.ServiceId
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.InvalidSessionException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.SessionRecord
import org.whispersystems.signalservice.api.SignalServiceSessionStore
import java.io.IOException

/**
 * Lightweight [SignalServiceSessionStore] that reads/writes directly from a raw SQLCipher
 * database handle, independent of the [SignalDatabase] singleton.
 */
class BackgroundSessionStore(
  private val db: SQLiteDatabase,
  private val serviceId: ServiceId
) : SignalServiceSessionStore {

  override fun loadSession(address: SignalProtocolAddress): SessionRecord {
    return loadSessionOrNull(address) ?: SessionRecord()
  }

  override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> {
    val results = mutableListOf<SessionRecord>()
    for (address in addresses) {
      val session = loadSessionOrNull(address) ?: throw NoSessionException("No session for $address")
      results.add(session)
    }
    return results
  }

  override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
    db.compileStatement(
      "INSERT INTO sessions (account_id, address, device, record) VALUES (?, ?, ?, ?) " +
        "ON CONFLICT (account_id, address, device) DO UPDATE SET record = excluded.record"
    ).use { stmt ->
      stmt.bindString(1, serviceId.toString())
      stmt.bindString(2, address.name)
      stmt.bindLong(3, address.deviceId.toLong())
      stmt.bindBlob(4, record.serialize())
      stmt.execute()
    }
  }

  override fun containsSession(address: SignalProtocolAddress): Boolean {
    val session = loadSessionOrNull(address) ?: return false
    return session.hasSenderChain()
  }

  override fun deleteSession(address: SignalProtocolAddress) {
    db.delete("sessions", "account_id = ? AND address = ? AND device = ?",
      arrayOf(serviceId.toString(), address.name, address.deviceId.toString()))
  }

  override fun deleteAllSessions(name: String) {
    db.delete("sessions", "account_id = ? AND address = ?",
      arrayOf(serviceId.toString(), name))
  }

  override fun getSubDeviceSessions(name: String): List<Int> {
    val results = mutableListOf<Int>()
    db.query("sessions", arrayOf("device"), "account_id = ? AND address = ?",
      arrayOf(serviceId.toString(), name), null, null, null).use { cursor ->
      while (cursor.moveToNext()) {
        results.add(cursor.getInt(0))
      }
    }
    return results
  }

  override fun archiveSession(address: SignalProtocolAddress) {
    val session = loadSessionOrNull(address) ?: return
    session.archiveCurrentState()
    storeSession(address, session)
  }

  override fun getAllAddressesWithActiveSessions(addressNames: List<String>): Map<SignalProtocolAddress, SessionRecord> {
    // Not needed for background decryption — return empty
    return emptyMap()
  }

  private fun loadSessionOrNull(address: SignalProtocolAddress): SessionRecord? {
    db.query("sessions", arrayOf("record"),
      "account_id = ? AND address = ? AND device = ?",
      arrayOf(serviceId.toString(), address.name, address.deviceId.toString()),
      null, null, null).use { cursor ->
      if (cursor.moveToFirst()) {
        try {
          return SessionRecord(cursor.getBlob(0))
        } catch (e: IOException) {
          Log.w(TAG, "Failed to deserialize session", e)
        } catch (e: InvalidSessionException) {
          Log.w(TAG, "Invalid session record", e)
        }
      }
    }
    return null
  }

  companion object {
    private val TAG = Log.tag(BackgroundSessionStore::class.java)
  }
}
