package org.thoughtcrime.securesms.account

import android.app.Application
import android.database.sqlite.SQLiteException
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.crypto.DatabaseSecretProvider
import org.thoughtcrime.securesms.database.SqlCipherLibraryLoader
import org.whispersystems.signalservice.internal.push.Envelope
import java.io.Closeable
import java.io.File

/**
 * Lightweight store for raw envelopes received by the background receiver while
 * an account is inactive. Lives in its own dedicated `deferred-envelopes.db` file
 * (NOT inside signal.db), so it works independently of the account's main database
 * state and can be safely created even before the account has fully initialized.
 *
 * The file is created on first open and the single table is created on first use —
 * no schema migrations needed. If the file is corrupted it is deleted and recreated.
 *
 * Envelopes are processed through the full [MessageContentProcessor] pipeline when
 * the account becomes active via [IncomingMessageObserver.drainDeferredEnvelopes].
 */
class DeferredEnvelopeStore(
  application: Application,
  private val accountId: String
) : Closeable {

  private val db: SQLiteDatabase

  init {
    SqlCipherLibraryLoader.load()
    val databaseSecret = DatabaseSecretProvider.getOrCreateDatabaseSecret(application)
    val dbFile = File(AccountFileManager.getAccountDir(application, accountId), DB_NAME)

    db = openOrRecreate(dbFile, databaseSecret.asString())
    ensureTable()
  }

  /**
   * Opens the database, recreating it from scratch if the file is corrupt.
   */
  private fun openOrRecreate(file: File, key: String): SQLiteDatabase {
    return try {
      SQLiteDatabase.openDatabase(
        file.absolutePath,
        key,
        null,
        SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
        null,
        null
      )
    } catch (e: SQLiteException) {
      Log.w(TAG, "[$accountId] Deferred store corrupt, deleting and recreating: ${file.name}", e)
      file.delete()
      SQLiteDatabase.openDatabase(
        file.absolutePath,
        key,
        null,
        SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
        null,
        null
      )
    }
  }

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

  fun delete(id: Long) {
    db.delete(TABLE_NAME, "$ID = ?", arrayOf(id.toString()))
  }

  fun deleteAll() {
    db.delete(TABLE_NAME, null, null)
  }

  fun count(): Int {
    db.query(TABLE_NAME, arrayOf("COUNT(*)"), null, null, null, null, null).use { cursor ->
      if (cursor.moveToFirst()) return cursor.getInt(0)
    }
    return 0
  }

  override fun close() {
    if (db.isOpen) db.close()
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

    const val DB_NAME = "deferred-envelopes.db"

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
