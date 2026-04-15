package org.thoughtcrime.securesms.account

import android.app.Application
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.crypto.DatabaseSecretProvider
import org.thoughtcrime.securesms.database.SqlCipherLibraryLoader
import java.io.Closeable

/**
 * Opens a background account's signal.db directly via SQLCipher, independent of the
 * [SignalDatabase] singleton. This gives raw SQL access to the account's message,
 * session, identity, pre-key, and sender key tables for background decryption.
 *
 * Must be [close]d when no longer needed to release the database connection.
 */
class BackgroundDatabaseHandle(
  application: Application,
  accountId: String
) : Closeable {

  val db: SQLiteDatabase

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

    Log.i(TAG, "Opened background database handle for $accountId")
  }

  override fun close() {
    if (db.isOpen) {
      db.close()
      Log.i(TAG, "Closed background database handle")
    }
  }

  companion object {
    private val TAG = Log.tag(BackgroundDatabaseHandle::class.java)
  }
}
