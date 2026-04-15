package org.thoughtcrime.securesms.account

import android.app.Application
import android.content.ContentValues
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.crypto.DatabaseSecret
import org.thoughtcrime.securesms.crypto.DatabaseSecretProvider
import org.thoughtcrime.securesms.database.SqlCipherDatabaseHook
import org.thoughtcrime.securesms.database.SqlCipherLibraryLoader

/**
 * Lightweight database that lives outside any account's directory.
 * It stores metadata about all registered accounts and tracks which
 * one is currently active. This database is never moved during an
 * account switch -- it's the stable anchor for multi-account state.
 */
class AccountRegistry private constructor(
  application: Application,
  databaseSecret: DatabaseSecret
) : SQLiteOpenHelper(
  application,
  DATABASE_NAME,
  databaseSecret.asString(),
  null,
  DATABASE_VERSION,
  0,
  null,
  SqlCipherDatabaseHook(),
  true
) {

  companion object {
    private val TAG = Log.tag(AccountRegistry::class.java)

    private const val DATABASE_VERSION = 3
    const val DATABASE_NAME = "account-registry.db"

    private const val TABLE_NAME = "accounts"
    private const val ID = "_id"
    private const val ACCOUNT_ID = "account_id"
    private const val ACI = "aci"
    private const val PNI = "pni"
    private const val E164 = "e164"
    private const val DISPLAY_NAME = "display_name"
    private const val IS_ACTIVE = "is_active"
    private const val CREATED_AT = "created_at"
    private const val SERVICE_PASSWORD = "service_password"
    private const val DEVICE_ID = "device_id"
    private const val REGISTRATION_ID = "registration_id"
    private const val ACI_IDENTITY_PUBLIC_KEY = "aci_identity_public_key"
    private const val ACI_IDENTITY_PRIVATE_KEY = "aci_identity_private_key"
    private const val PNI_IDENTITY_PUBLIC_KEY = "pni_identity_public_key"
    private const val PNI_IDENTITY_PRIVATE_KEY = "pni_identity_private_key"

    private const val CREATE_TABLE = """
      CREATE TABLE $TABLE_NAME (
        $ID INTEGER PRIMARY KEY AUTOINCREMENT,
        $ACCOUNT_ID TEXT UNIQUE NOT NULL,
        $ACI TEXT,
        $PNI TEXT,
        $E164 TEXT,
        $DISPLAY_NAME TEXT,
        $IS_ACTIVE INTEGER NOT NULL DEFAULT 0,
        $CREATED_AT INTEGER NOT NULL DEFAULT 0,
        $SERVICE_PASSWORD TEXT,
        $DEVICE_ID INTEGER NOT NULL DEFAULT 1,
        $REGISTRATION_ID INTEGER NOT NULL DEFAULT 0,
        $ACI_IDENTITY_PUBLIC_KEY BLOB,
        $ACI_IDENTITY_PRIVATE_KEY BLOB,
        $PNI_IDENTITY_PUBLIC_KEY BLOB,
        $PNI_IDENTITY_PRIVATE_KEY BLOB
      )
    """

    @Volatile
    private var instance: AccountRegistry? = null

    @JvmStatic
    fun getInstance(application: Application): AccountRegistry {
      if (instance == null) {
        synchronized(AccountRegistry::class.java) {
          if (instance == null) {
            SqlCipherLibraryLoader.load()
            instance = AccountRegistry(application, DatabaseSecretProvider.getOrCreateDatabaseSecret(application))
          }
        }
      }
      return instance!!
    }
  }

  override fun onCreate(db: net.zetetic.database.sqlcipher.SQLiteDatabase) {
    Log.i(TAG, "onCreate()")
    db.execSQL(CREATE_TABLE)
  }

  override fun onUpgrade(db: net.zetetic.database.sqlcipher.SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    Log.i(TAG, "onUpgrade($oldVersion, $newVersion)")

    if (oldVersion < 2) {
      db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $PNI TEXT")
      db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $SERVICE_PASSWORD TEXT")
      db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $DEVICE_ID INTEGER NOT NULL DEFAULT 1")
      db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $REGISTRATION_ID INTEGER NOT NULL DEFAULT 0")
    }

    if (oldVersion < 3) {
      db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $ACI_IDENTITY_PUBLIC_KEY BLOB")
      db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $ACI_IDENTITY_PRIVATE_KEY BLOB")
      db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $PNI_IDENTITY_PUBLIC_KEY BLOB")
      db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $PNI_IDENTITY_PRIVATE_KEY BLOB")
    }
  }

  /**
   * Returns all registered accounts, ordered by creation time.
   */
  fun getAllAccounts(): List<AccountEntry> {
    val accounts = mutableListOf<AccountEntry>()
    readableDatabase.query(TABLE_NAME, null, null, null, null, null, "$CREATED_AT ASC").use { cursor ->
      while (cursor.moveToNext()) {
        accounts.add(readAccountEntry(cursor))
      }
    }
    return accounts
  }

  /**
   * Returns the currently active account, or null if none is set.
   */
  fun getActiveAccount(): AccountEntry? {
    readableDatabase.query(TABLE_NAME, null, "$IS_ACTIVE = 1", null, null, null, null).use { cursor ->
      if (cursor.moveToFirst()) {
        return readAccountEntry(cursor)
      }
    }
    return null
  }

  /**
   * Registers a new account and optionally sets it as active.
   * Returns the generated account ID (e.g., "account-0", "account-1").
   */
  fun addAccount(setActive: Boolean = false): String {
    val db = writableDatabase
    val accountId = "account-${getNextAccountIndex()}"

    db.beginTransaction()
    try {
      if (setActive) {
        db.execSQL("UPDATE $TABLE_NAME SET $IS_ACTIVE = 0")
      }

      val values = ContentValues().apply {
        put(ACCOUNT_ID, accountId)
        put(IS_ACTIVE, if (setActive) 1 else 0)
        put(CREATED_AT, System.currentTimeMillis())
      }
      db.insert(TABLE_NAME, null, values)
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }

    Log.i(TAG, "Added account: $accountId, active: $setActive")
    return accountId
  }

  /**
   * Sets the given account as the active one, deactivating all others.
   */
  fun setActiveAccount(accountId: String) {
    val db = writableDatabase
    db.beginTransaction()
    try {
      db.execSQL("UPDATE $TABLE_NAME SET $IS_ACTIVE = 0")
      val values = ContentValues().apply { put(IS_ACTIVE, 1) }
      db.update(TABLE_NAME, values, "$ACCOUNT_ID = ?", arrayOf(accountId))
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    Log.i(TAG, "Set active account: $accountId")
  }

  /**
   * Updates the identity and credentials for an account. Called after registration
   * and on every account activation via [AccountSwitcher.syncRegistryWithActiveAccount].
   */
  fun updateAccountIdentity(
    accountId: String,
    aci: String?,
    pni: String? = null,
    e164: String?,
    displayName: String?,
    servicePassword: String? = null,
    deviceId: Int? = null,
    registrationId: Int? = null,
    aciIdentityPublicKey: ByteArray? = null,
    aciIdentityPrivateKey: ByteArray? = null,
    pniIdentityPublicKey: ByteArray? = null,
    pniIdentityPrivateKey: ByteArray? = null
  ) {
    val values = ContentValues().apply {
      put(ACI, aci)
      put(PNI, pni)
      put(E164, e164)
      put(DISPLAY_NAME, displayName)
      if (servicePassword != null) put(SERVICE_PASSWORD, servicePassword)
      if (deviceId != null) put(DEVICE_ID, deviceId)
      if (registrationId != null) put(REGISTRATION_ID, registrationId)
      if (aciIdentityPublicKey != null) put(ACI_IDENTITY_PUBLIC_KEY, aciIdentityPublicKey)
      if (aciIdentityPrivateKey != null) put(ACI_IDENTITY_PRIVATE_KEY, aciIdentityPrivateKey)
      if (pniIdentityPublicKey != null) put(PNI_IDENTITY_PUBLIC_KEY, pniIdentityPublicKey)
      if (pniIdentityPrivateKey != null) put(PNI_IDENTITY_PRIVATE_KEY, pniIdentityPrivateKey)
    }
    writableDatabase.update(TABLE_NAME, values, "$ACCOUNT_ID = ?", arrayOf(accountId))
    Log.i(TAG, "Updated identity for $accountId")
  }

  /**
   * Removes an account from the registry.
   */
  fun removeAccount(accountId: String) {
    writableDatabase.delete(TABLE_NAME, "$ACCOUNT_ID = ?", arrayOf(accountId))
    Log.i(TAG, "Removed account: $accountId")
  }

  /**
   * Returns the number of registered accounts.
   */
  fun getAccountCount(): Int {
    readableDatabase.query(TABLE_NAME, arrayOf("COUNT(*)"), null, null, null, null, null).use { cursor ->
      if (cursor.moveToFirst()) {
        return cursor.getInt(0)
      }
    }
    return 0
  }

  /**
   * Returns true if any accounts are registered.
   */
  fun hasAccounts(): Boolean = getAccountCount() > 0

  private fun getNextAccountIndex(): Int {
    readableDatabase.query(TABLE_NAME, arrayOf("MAX(CAST(REPLACE($ACCOUNT_ID, 'account-', '') AS INTEGER))"), null, null, null, null, null).use { cursor ->
      if (cursor.moveToFirst() && !cursor.isNull(0)) {
        return cursor.getInt(0) + 1
      }
    }
    return 0
  }

  private fun readAccountEntry(cursor: android.database.Cursor): AccountEntry {
    return AccountEntry(
      accountId = cursor.getString(cursor.getColumnIndexOrThrow(ACCOUNT_ID)),
      aci = cursor.getString(cursor.getColumnIndexOrThrow(ACI)),
      pni = cursor.getString(cursor.getColumnIndexOrThrow(PNI)),
      e164 = cursor.getString(cursor.getColumnIndexOrThrow(E164)),
      displayName = cursor.getString(cursor.getColumnIndexOrThrow(DISPLAY_NAME)),
      isActive = cursor.getInt(cursor.getColumnIndexOrThrow(IS_ACTIVE)) == 1,
      createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(CREATED_AT)),
      servicePassword = cursor.getString(cursor.getColumnIndexOrThrow(SERVICE_PASSWORD)),
      deviceId = cursor.getInt(cursor.getColumnIndexOrThrow(DEVICE_ID)),
      registrationId = cursor.getInt(cursor.getColumnIndexOrThrow(REGISTRATION_ID)),
      aciIdentityPublicKey = cursor.getBlob(cursor.getColumnIndexOrThrow(ACI_IDENTITY_PUBLIC_KEY)),
      aciIdentityPrivateKey = cursor.getBlob(cursor.getColumnIndexOrThrow(ACI_IDENTITY_PRIVATE_KEY)),
      pniIdentityPublicKey = cursor.getBlob(cursor.getColumnIndexOrThrow(PNI_IDENTITY_PUBLIC_KEY)),
      pniIdentityPrivateKey = cursor.getBlob(cursor.getColumnIndexOrThrow(PNI_IDENTITY_PRIVATE_KEY))
    )
  }

  data class AccountEntry(
    val accountId: String,
    val aci: String?,
    val pni: String?,
    val e164: String?,
    val displayName: String?,
    val isActive: Boolean,
    val createdAt: Long,
    val servicePassword: String? = null,
    val deviceId: Int = 1,
    val registrationId: Int = 0,
    val aciIdentityPublicKey: ByteArray? = null,
    val aciIdentityPrivateKey: ByteArray? = null,
    val pniIdentityPublicKey: ByteArray? = null,
    val pniIdentityPrivateKey: ByteArray? = null
  ) {
    /** Returns true if this entry has enough credentials to authenticate a WebSocket. */
    val hasCredentials: Boolean
      get() = aci != null && servicePassword != null

    /** Returns true if this entry has identity keys needed for message decryption. */
    val hasIdentityKeys: Boolean
      get() = aciIdentityPublicKey != null && aciIdentityPrivateKey != null
  }
}
