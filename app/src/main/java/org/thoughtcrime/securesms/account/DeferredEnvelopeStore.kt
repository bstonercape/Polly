package org.thoughtcrime.securesms.account

import android.app.Application
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.crypto.DatabaseSecretProvider
import org.thoughtcrime.securesms.database.SqlCipherLibraryLoader
import org.whispersystems.signalservice.internal.push.Envelope
import java.io.Closeable

/**
 * Manages the `deferred_envelopes` table in a background account's signal.db.
 * Stores raw serialized envelopes received while the account is inactive, to be
 * processed through the full [MessageContentProcessor] pipeline when the account
 * becomes active.
 *
 * The table is created on first use (no schema migration needed).
 */
class DeferredEnvelopeStore(
  application: Application,
  private val accountId: String
) : Closeable {

  private val db: SQLiteDatabase

  init {
    SqlCipherLibraryLoader.load()
    val databaseSecret = DatabaseSecretProvider.getOrCreateDatabaseSecret(application)
    val dbPath = AccountFileManager.getAccountDatabasePath(application, accountId, "signal.db")

    db = SQLiteDatabase.openDatabase(
      dbPath,
      databaseSecret.asString(),
      null,
      SQLiteDatabase.OPEN_READWRITE,
      null,
      null
    )

    ensureTable()
  }

  /**
   * Stores a raw envelope for later processing.
   */
  fun insert(envelope: Envelope, serverDeliveredTimestamp: Long) {
    db.compileStatement(
      "INSERT INTO $TABLE_NAME ($ENVELOPE, $SERVER_TIMESTAMP, $RECEIVED_AT) VALUES (?, ?, ?)"
    ).use { stmt ->
      stmt.bindBlob(1, envelope.encode())
      stmt.bindLong(2, serverDeliveredTimestamp)
      stmt.bindLong(3, System.currentTimeMillis())
      stmt.execute()
    }
  }

  /**
   * Returns all deferred envelopes ordered by receipt time.
   */
  fun getAll(): List<DeferredEnvelope> {
    val results = mutableListOf<DeferredEnvelope>()
    db.query(TABLE_NAME, null, null, null, null, null, "$RECEIVED_AT ASC").use { cursor ->
      while (cursor.moveToNext()) {
        try {
          results.add(
            DeferredEnvelope(
              id = cursor.getLong(cursor.getColumnIndexOrThrow(ID)),
              envelope = Envelope.ADAPTER.decode(cursor.getBlob(cursor.getColumnIndexOrThrow(ENVELOPE))),
              serverDeliveredTimestamp = cursor.getLong(cursor.getColumnIndexOrThrow(SERVER_TIMESTAMP)),
              receivedAt = cursor.getLong(cursor.getColumnIndexOrThrow(RECEIVED_AT))
            )
          )
        } catch (e: Exception) {
          Log.w(TAG, "Failed to deserialize deferred envelope", e)
        }
      }
    }
    return results
  }

  /**
   * Deletes a deferred envelope after successful processing.
   */
  fun delete(id: Long) {
    db.delete(TABLE_NAME, "$ID = ?", arrayOf(id.toString()))
  }

  /**
   * Deletes all deferred envelopes (e.g., after a full drain).
   */
  fun deleteAll() {
    db.delete(TABLE_NAME, null, null)
  }

  /**
   * Returns the count of pending deferred envelopes.
   */
  fun count(): Int {
    db.query(TABLE_NAME, arrayOf("COUNT(*)"), null, null, null, null, null).use { cursor ->
      if (cursor.moveToFirst()) return cursor.getInt(0)
    }
    return 0
  }

  override fun close() {
    if (db.isOpen) {
      db.close()
    }
  }

  private fun ensureTable() {
    db.execSQL(CREATE_TABLE)
  }

  data class DeferredEnvelope(
    val id: Long,
    val envelope: Envelope,
    val serverDeliveredTimestamp: Long,
    val receivedAt: Long
  )

  companion object {
    private val TAG = Log.tag(DeferredEnvelopeStore::class.java)

    private const val TABLE_NAME = "deferred_envelopes"
    private const val ID = "_id"
    private const val ENVELOPE = "envelope"
    private const val SERVER_TIMESTAMP = "server_timestamp"
    private const val RECEIVED_AT = "received_at"

    private const val CREATE_TABLE = """
      CREATE TABLE IF NOT EXISTS $TABLE_NAME (
        $ID INTEGER PRIMARY KEY AUTOINCREMENT,
        $ENVELOPE BLOB NOT NULL,
        $SERVER_TIMESTAMP INTEGER NOT NULL,
        $RECEIVED_AT INTEGER NOT NULL
      )
    """
  }
}
