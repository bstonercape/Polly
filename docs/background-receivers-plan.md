# Plan: Background Receivers for Multi-Account Push Notifications

Moving from Approach 1 (hot-swap only) to Approach 3 (hybrid with lightweight background receivers).

---

## Goals

1. All accounts receive push notifications in real time, not just the active one
2. Tapping a notification for a background account switches to that account and opens the conversation
3. The active account continues running the full singleton stack unchanged
4. Background receivers are lightweight — WebSocket + decrypt + insert + notify, nothing more

---

## Current State

What already exists (Approach 1):

| Component | Status | File |
|-----------|--------|------|
| Account registry | Done | `account/AccountRegistry.kt` |
| Per-account directories | Done | `account/AccountFileManager.kt` |
| Singleton re-init (hot swap) | Done | `account/AccountSwitcher.kt` |
| FCM drain cycling | Done (crude) | `gcm/FcmFetchManager.kt:195-236` |
| Account switcher UI | Done | `account/AccountSwitcherBottomSheet.kt` |

The current `drainBackgroundAccounts()` in `FcmFetchManager` does a full `AccountSwitcher.switchToAccount()` for each background account — tearing down and rebuilding the entire singleton stack. This is slow, battery-intensive, and delays notifications.

---

## Target Architecture

```
┌─────────────────────────────────────────────────────┐
│                    Polly App Process                  │
│                                                       │
│  ┌──────────────────────────────────────────────┐    │
│  │         Active Account (account-0)            │    │
│  │                                                │    │
│  │  SignalDatabase ← signal.db                    │    │
│  │  SignalStore ← signal-key-value.db             │    │
│  │  IncomingMessageObserver ← auth WebSocket      │    │
│  │  JobManager, MessageContentProcessor, etc.     │    │
│  │                                                │    │
│  │  (Full singleton stack — unchanged from today)  │    │
│  └──────────────────────────────────────────────┘    │
│                                                       │
│  ┌──────────────────────────────────────────────┐    │
│  │    BackgroundReceiver (account-1)             │    │
│  │                                                │    │
│  │  rawDb ← direct SQLCipher handle to signal.db  │    │
│  │  credentials ← cached ACI + password           │    │
│  │  webSocket ← standalone auth WebSocket         │    │
│  │  protocolStore ← lightweight, reads from rawDb │    │
│  │                                                │    │
│  │  Receives → decrypts → inserts → notifies      │    │
│  │  Complex messages → deferred envelope queue     │    │
│  └──────────────────────────────────────────────┘    │
│                                                       │
│  ┌──────────────────────────────────────────────┐    │
│  │    BackgroundReceiver (account-2)             │    │
│  │           (same structure)                     │    │
│  └──────────────────────────────────────────────┘    │
│                                                       │
│  ┌──────────────────────────────────────────────┐    │
│  │    BackgroundAccountManager                    │    │
│  │                                                │    │
│  │  Owns all BackgroundReceivers                  │    │
│  │  Starts/stops on account switch                │    │
│  │  Wakes all receivers on FCM push               │    │
│  └──────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────┘
```

---

## Phase 1: Credential Caching

Before we can run background receivers, we need each account's authentication credentials available without loading its full singleton stack. Currently, credentials live in each account's `signal-key-value.db` (accessed via `SignalStore.account`), which is only readable when that account is the active one.

### 1a. Extend AccountRegistry with credentials

Add columns to the `accounts` table in `account-registry.db`:

```sql
ALTER TABLE accounts ADD COLUMN service_password TEXT;
ALTER TABLE accounts ADD COLUMN pni TEXT;
ALTER TABLE accounts ADD COLUMN device_id INTEGER NOT NULL DEFAULT 1;
ALTER TABLE accounts ADD COLUMN registration_id INTEGER NOT NULL DEFAULT 0;
```

These are the minimum fields needed to authenticate a WebSocket connection.

**Files to change:**
- `account/AccountRegistry.kt` — bump `DATABASE_VERSION` to 2, add `onUpgrade` migration, add columns to `AccountEntry`, update `updateAccountIdentity()` to also store these fields
- `account/AccountSwitcher.kt` — in `syncRegistryWithActiveAccount()`, read credentials from `SignalStore.account` and write them to the registry

### 1b. Sync credentials on every switch

Every time `syncRegistryWithActiveAccount()` runs (which happens on startup and on account switch), also capture:
- `SignalStore.account.servicePassword`
- `SignalStore.account.pni`
- `SignalStore.account.deviceId`
- `SignalStore.account.registrationId`

This keeps the registry up to date. Credentials rarely change, but syncing on every activation ensures freshness.

**Risk:** The service password is sensitive. The `account-registry.db` is already encrypted via SQLCipher with the same `DatabaseSecret` used by the main databases, so this is acceptable.

---

## Phase 2: Standalone WebSocket for Background Accounts

Create a WebSocket connection that authenticates with cached credentials, independent of the singleton stack.

### 2a. BackgroundCredentialsProvider

A simple `CredentialsProvider` implementation that reads from the cached credentials in `AccountRegistry`, rather than from `SignalStore`:

```kotlin
class BackgroundCredentialsProvider(
    private val aci: ACI,
    private val pni: PNI,
    private val e164: String,
    private val deviceId: Int,
    private val password: String
) : CredentialsProvider {
    override fun getAci(): ACI = aci
    override fun getPni(): PNI = pni
    override fun getE164(): String = e164
    override fun getDeviceId(): Int = deviceId
    override fun getPassword(): String = password
}
```

**New file:** `account/BackgroundCredentialsProvider.kt`

### 2b. Standalone WebSocket creation

The `OkHttpWebSocketConnection` (Signal's WebSocket implementation) can be instantiated directly — it's not a singleton. It needs:
- Server URL (from `SignalServiceNetworkAccess`)
- Trust store
- Credentials provider
- Health monitor

We need a factory that creates a standalone authenticated WebSocket for a given account's credentials, without touching `NetworkDependenciesModule`.

**New file:** `account/BackgroundWebSocketFactory.kt`

**Key dependency:** The WebSocket URL and TLS configuration come from `SignalServiceNetworkAccess`, which depends on the censorship circumvention state. This is app-global (not per-account), so we can read it from the existing singleton. The WebSocket itself just needs the URL + creds.

---

## Phase 3: Lightweight Protocol Store

The background receiver needs to decrypt messages. Decryption requires the Signal Protocol store (sessions, identity keys, pre-keys, sender keys). These are stored in `signal.db` tables, currently accessed via `SignalDatabase` singletons.

### 3a. Direct database handle

Open each background account's `signal.db` directly via SQLCipher, independent of `SignalDatabase`:

```kotlin
class BackgroundDatabaseHandle(
    context: Context,
    accountId: String,
    databaseSecret: DatabaseSecret
) {
    val db: SQLiteDatabase = SQLiteDatabase.openDatabase(
        AccountFileManager.getAccountDatabasePath(context, accountId, "signal.db"),
        databaseSecret.asString(),
        null,
        SQLiteDatabase.OPEN_READWRITE
    )
}
```

This gives us raw SQL access to the account's database without going through the `SignalDatabase` singleton.

**New file:** `account/BackgroundDatabaseHandle.kt`

### 3b. Background protocol stores

Create lightweight implementations of the protocol store interfaces that read/write directly from the raw database handle rather than from `SignalDatabase`:

- `BackgroundSessionStore` — reads/writes `sessions` table
- `BackgroundIdentityKeyStore` — reads `identities` table and the account's own identity key from KV store
- `BackgroundPreKeyStore` — reads `one_time_prekeys`, `signed_prekeys`, `kyber_prekeys`
- `BackgroundSenderKeyStore` — reads `sender_keys`, `sender_key_shared`

These wrap the same SQL queries as their singleton counterparts (`TextSecureSessionStore`, etc.) but operate on the raw DB handle. This is the most tedious part — essentially extracting the SQL logic from the existing stores into standalone versions.

**New files:**
- `account/protocol/BackgroundSessionStore.kt`
- `account/protocol/BackgroundIdentityKeyStore.kt`
- `account/protocol/BackgroundPreKeyStore.kt`
- `account/protocol/BackgroundSenderKeyStore.kt`
- `account/protocol/BackgroundProtocolStore.kt` (composes the above)

**Shortcut consideration:** Instead of reimplementing each store, we could open the background account's DB and temporarily inject it as the backing store for the existing store implementations via an interface. This reduces code duplication but increases coupling. For the plan, I'll assume standalone implementations — they're more isolated and safer.

### 3c. Own identity key access

The account's own identity key pair is stored in `signal-key-value.db`, not `signal.db`. The background receiver needs to read this for decryption. Two options:

**Option A:** Also open `signal-key-value.db` directly and read the identity key.

**Option B:** Cache the identity key in `AccountRegistry` at sync time (alongside credentials).

Option B is simpler and avoids opening a second database. The identity key changes extremely rarely (only on re-registration). Add an `identity_key` blob column to the registry.

**File to change:** `account/AccountRegistry.kt` — add `identity_key` column, update sync

---

## Phase 4: BackgroundAccountReceiver

The core new component that ties everything together.

### 4a. Receiver class

```kotlin
class BackgroundAccountReceiver(
    private val context: Context,
    private val accountId: String,
    private val credentials: BackgroundCredentialsProvider,
    private val dbHandle: BackgroundDatabaseHandle,
    private val protocolStore: BackgroundProtocolStore
) {
    private var webSocket: WebSocketConnection? = null
    private var receiverThread: Thread? = null
    private var running = false

    fun start() {
        // 1. Create standalone WebSocket with credentials
        // 2. Connect WebSocket
        // 3. Start receiver thread (similar to MessageRetrievalThread)
        // 4. Loop: read envelope batch → decrypt → insert → notify
    }

    fun stop() {
        // 1. Set running = false
        // 2. Disconnect WebSocket
        // 3. Close database handle
        // 4. Join receiver thread
    }

    fun wake() {
        // Called on FCM push — reconnect WebSocket if disconnected
    }

    private fun processEnvelope(envelope: Envelope, serverTimestamp: Long) {
        // 1. Decrypt using BackgroundProtocolStore
        val result = MessageDecryptor.decrypt(context, protocolStore, envelope, serverTimestamp)

        when (result) {
            is Result.Success -> {
                if (isSimpleDataMessage(result.content)) {
                    insertMessageDirect(result)
                    postNotification(result)
                } else {
                    // Group updates, sync messages, etc. — defer
                    storeDeferredEnvelope(envelope, serverTimestamp)
                }
            }
            is Result.Error -> {
                storeDeferredEnvelope(envelope, serverTimestamp)
            }
        }
    }
}
```

**New file:** `account/BackgroundAccountReceiver.kt`

### 4b. Direct message insertion

For simple data messages (text, basic media), insert directly into the account's `message` table via the raw DB handle. This bypasses `MessageContentProcessor` and all the complex processing (group state validation, profile fetches, job scheduling, etc.).

The insertion needs to write enough for the message to appear correctly when the account becomes active:

```sql
INSERT INTO message (
    thread_id, from_recipient_id, to_recipient_id, date_sent, date_received,
    date_server, body, type, read, ...
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ...)
```

We also need to update the thread table (snippet, unread count, timestamp).

**Key concern:** The `message` table schema is complex (40+ columns). We only need to fill the essential ones for display. When the account becomes active, `MessageContentProcessor` doesn't re-process — the message is already in the DB. But any side effects that `MessageContentProcessor` normally handles (profile key updates, group state checks, story processing, etc.) won't have happened. This is acceptable — they'll be caught up on next activation.

**New file:** `account/BackgroundMessageInserter.kt`

### 4c. Deferred envelope queue

For messages the background receiver can't handle (group updates, sync messages, edit messages, story messages, call messages, etc.), store the raw encrypted envelope for processing when the account becomes active:

```sql
CREATE TABLE deferred_envelopes (
    _id INTEGER PRIMARY KEY,
    envelope BLOB NOT NULL,       -- serialized Envelope protobuf
    server_timestamp INTEGER NOT NULL,
    received_at INTEGER NOT NULL
)
```

Add this table to each account's `signal.db` (via a schema migration, or create it on first use in the background receiver).

When the account becomes active, a startup job drains this table through the normal `MessageContentProcessor` pipeline, then deletes processed entries.

**Files to change:**
- `account/BackgroundAccountReceiver.kt` — write deferred envelopes
- `account/AccountSwitcher.kt` — after switch, schedule deferred envelope drain
- New: `jobs/ProcessDeferredEnvelopesJob.kt` — drains deferred queue through `MessageContentProcessor`

### 4d. What counts as "simple" vs "deferred"

| Envelope content | Background receiver handles? | Notes |
|-----------------|------------------------------|-------|
| Text DataMessage (1:1) | Yes — decrypt, insert, notify | Core use case |
| Media DataMessage (1:1) | Partially — insert message record, defer attachment download | Show notification with "[Image]" or similar |
| Text DataMessage (group) | Yes if group state is current | Insert into existing thread |
| Group state update | No — defer | Requires GroupsV2StateProcessor |
| SyncMessage | No — defer | Requires full sync pipeline |
| ReceiptMessage | No — defer | Read/delivery receipts |
| TypingMessage | No — drop | Ephemeral, not worth storing |
| CallMessage | No — defer | Requires call subsystem |
| StoryMessage | No — defer | Requires story processing |
| EditMessage | No — defer | Complex merge logic |
| SenderKeyDistribution | Yes — update sender key store | Needed for future group decryption |

---

## Phase 5: BackgroundAccountManager

Orchestrates the lifecycle of all background receivers.

```kotlin
object BackgroundAccountManager {
    private val receivers = mutableMapOf<String, BackgroundAccountReceiver>()

    fun startAll(context: Context) {
        val registry = AccountRegistry.getInstance(context as Application)
        val activeId = registry.getActiveAccount()?.accountId
        for (account in registry.getAllAccounts()) {
            if (account.accountId != activeId && account.aci != null) {
                startReceiver(context, account)
            }
        }
    }

    fun stopAll() {
        receivers.values.forEach { it.stop() }
        receivers.clear()
    }

    fun onAccountSwitch(context: Context, oldActiveId: String, newActiveId: String) {
        // Stop receiver for newly-active account (it's now running the full stack)
        receivers.remove(newActiveId)?.stop()
        // Start receiver for previously-active account (it's now in the background)
        val registry = AccountRegistry.getInstance(context as Application)
        val oldAccount = registry.getAllAccounts().find { it.accountId == oldActiveId }
        if (oldAccount != null) {
            startReceiver(context, oldAccount)
        }
    }

    fun wakeAll() {
        receivers.values.forEach { it.wake() }
    }
}
```

**New file:** `account/BackgroundAccountManager.kt`

**Integration points:**
- `ApplicationContext.onCreate()` — call `BackgroundAccountManager.startAll()` after app initialization
- `AccountSwitcher.switchToAccount()` — call `BackgroundAccountManager.onAccountSwitch()` during step 1 (before teardown)
- `FcmFetchManager.retrieveMessages()` — call `BackgroundAccountManager.wakeAll()` instead of `drainBackgroundAccounts()`

---

## Phase 6: Notifications with Account Context

### 6a. Per-account notification channel groups

Android notification channel groups let us visually separate notifications by account.

```kotlin
// Create a channel group per account
val groupId = "account_${account.accountId}"
val groupName = account.displayName ?: account.e164 ?: account.accountId
notificationManager.createNotificationChannelGroup(
    NotificationChannelGroup(groupId, groupName)
)

// Create message channel within the group
val channel = NotificationChannel(
    "messages_${account.accountId}",
    "Messages",
    NotificationManager.IMPORTANCE_HIGH
).apply {
    group = groupId
}
```

**File to change:** `notifications/NotificationChannels.java` — add per-account channel group creation, called when accounts are added/synced

### 6b. Account-aware notification posting

The background receiver posts notifications directly (it can't use `DefaultMessageNotifier`, which depends on the active account's database). Build a lightweight notification:

```kotlin
fun postNotification(accountId: String, senderName: String, body: String, threadId: Long, recipientId: Long) {
    val intent = ConversationIntents.createBuilderSync(context, RecipientId.from(recipientId), threadId)
        .build()
        .putExtra("account_id", accountId)  // NEW: account context

    val notification = NotificationCompat.Builder(context, "messages_$accountId")
        .setContentTitle(senderName)
        .setContentText(body)
        .setSmallIcon(R.drawable.ic_notification)
        .setSubText(accountDisplayName)  // Shows which account
        .setGroup("messages_$accountId")
        .setContentIntent(TaskStackBuilder.create(context)
            .addNextIntentWithParentStack(intent)
            .getPendingIntent(notificationId, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()

    notificationManager.notify(notificationId, notification)
}
```

**New file:** `account/BackgroundNotificationPoster.kt`

**Key decisions:**
- `setSubText(accountDisplayName)` shows the account name/number below the notification, so the user knows which account received the message
- Notification IDs must be namespaced per-account to avoid collisions (e.g., `accountIndex * 100000 + threadId`)
- The notification group is per-account so they stack separately

### 6c. Sender name resolution

The background receiver doesn't have access to the active account's `LiveRecipientCache`. But it does have the raw database handle to the background account's `signal.db`, which contains the `recipient` table. Look up the sender's display name directly:

```sql
SELECT COALESCE(
    system_joined_name,
    profile_joined_name,
    nickname_joined_name,
    e164,
    'Unknown'
) FROM recipient WHERE aci = ?
```

**Added to:** `account/BackgroundMessageInserter.kt` or a separate `BackgroundRecipientResolver.kt`

---

## Phase 7: Notification Tap → Account Switch → Conversation

### 7a. Add account_id to ConversationIntents

Extend the intent builder to carry an account ID:

```java
// ConversationIntents.java
private static final String EXTRA_ACCOUNT_ID = "account_id";

public static class Builder {
    private String accountId;

    public Builder withAccountId(String accountId) {
        this.accountId = accountId;
        return this;
    }

    public Intent build() {
        Intent intent = ...;
        if (accountId != null) {
            intent.putExtra(EXTRA_ACCOUNT_ID, accountId);
        }
        return intent;
    }
}
```

**File to change:** `conversation/ConversationIntents.java`

### 7b. Handle account switch in MainActivity

When `MainActivity` receives a conversation intent with an `account_id` that doesn't match the active account, trigger a switch:

```kotlin
// MainActivity.kt
private fun handleConversationIntent(intent: Intent) {
    if (!ConversationIntents.isConversationIntent(intent)) return

    val targetAccountId = intent.getStringExtra("account_id")
    val activeAccountId = AccountRegistry.getInstance(application).getActiveAccount()?.accountId

    if (targetAccountId != null && targetAccountId != activeAccountId) {
        // Show a brief loading state
        showAccountSwitchLoading()

        // Switch on background thread, then navigate
        lifecycleScope.launch(Dispatchers.IO) {
            AccountSwitcher.switchToAccount(application, targetAccountId)
            withContext(Dispatchers.Main) {
                hideAccountSwitchLoading()
                navigateToConversation(intent)
            }
        }
    } else {
        navigateToConversation(intent)
    }
}
```

**File to change:** `MainActivity.kt` — modify `handleConversationIntent()`

### 7c. Update notification action receivers

The mark-as-read and remote-reply actions also need account context:

- `MarkReadReceiver` — needs to mark messages read in the correct account's DB. If background, either switch briefly or write directly to the background DB handle.
- `RemoteReplyReceiver` — needs to send a reply from the correct account. This requires a full account switch (or at least loading that account's message sender). For v1, tapping "Reply" on a background account notification could switch to the account and open the compose box.

**Files to change:**
- `notifications/MarkReadReceiver.java` — add account_id handling
- `notifications/RemoteReplyReceiver.java` — add account_id handling
- `notifications/v2/NotificationConversation.kt` — pass account_id in action intents

---

## Phase 8: FCM Token Registration for All Accounts

Currently, only the active account registers its FCM token with the Signal server. Background accounts need the same token registered so the server sends FCM wakes for their messages too.

### 8a. Register token for all accounts on refresh

When the FCM token is refreshed (`FcmReceiveService.onNewToken()`), register it with all accounts' servers, not just the active one.

For background accounts, we can make the registration API call using the cached credentials from `AccountRegistry` — it's a simple `PUT /v1/accounts/gcm` call that doesn't require the full singleton stack.

```kotlin
fun registerTokenForAllAccounts(context: Context, token: String) {
    val registry = AccountRegistry.getInstance(context as Application)
    for (account in registry.getAllAccounts()) {
        if (account.aci != null && account.servicePassword != null) {
            // Direct HTTP call to register FCM token
            registerFcmToken(account.aci, account.servicePassword, account.deviceId, token)
        }
    }
}
```

**Files to change:**
- `gcm/FcmReceiveService.java` — call registration for all accounts in `onNewToken()`
- New: `account/BackgroundFcmRegistration.kt` — direct API call for FCM registration
- `account/AccountSwitcher.kt` — register FCM token when adding a new account

### 8b. Periodic re-registration

FCM tokens can expire or be refreshed by Google. Add a periodic check (e.g., daily) that ensures all accounts have the current token registered. This could be a simple job that iterates accounts and re-registers.

**New file:** `jobs/FcmTokenSyncJob.kt`

---

## Phase 9: Clean Handoff on Account Switch

When the user switches accounts (either via the switcher UI or via notification tap), the transition between active and background states must be clean.

### Sequence:

```
1. BackgroundAccountManager.stopReceiver(newActiveId)
   - Stop the background receiver for the account becoming active
   - Close its WebSocket and DB handle
   - It's about to get the full singleton stack

2. AccountSwitcher.switchToAccount(newActiveId)
   - Existing hot-swap flow (tear down → reinit → reconnect)

3. BackgroundAccountManager.startReceiver(oldActiveId)
   - Start a background receiver for the account becoming inactive
   - Open its DB handle, create WebSocket, start receiver thread

4. ProcessDeferredEnvelopesJob.enqueue()
   - Drain any deferred envelopes from when the newly-active account
     was in the background
   - Processes through the full MessageContentProcessor pipeline
```

**Critical timing:** The background receiver for the new active account MUST be stopped BEFORE `AccountSwitcher` opens its `signal.db` via `SignalDatabase.reinit()`. Two open handles to the same SQLCipher database from different threads is safe (SQLCipher handles locking), but we should avoid it for clarity and to prevent WAL conflicts.

**Files to change:**
- `account/AccountSwitcher.kt` — integrate with `BackgroundAccountManager` lifecycle
- New: `jobs/ProcessDeferredEnvelopesJob.kt`

---

## Execution Order

| Order | Phase | Dependency | Effort |
|-------|-------|------------|--------|
| 1 | Phase 1: Credential caching | None | Small |
| 2 | Phase 8: FCM token registration | Phase 1 (needs cached creds) | Small |
| 3 | Phase 2: Standalone WebSocket | Phase 1 | Small |
| 4 | Phase 3: Lightweight protocol store | None (parallel with 1-2) | Medium-Large |
| 5 | Phase 4: BackgroundAccountReceiver | Phases 1, 2, 3 | Medium |
| 6 | Phase 5: BackgroundAccountManager | Phase 4 | Small |
| 7 | Phase 6: Notifications with account context | Phase 4 | Medium |
| 8 | Phase 7: Notification tap → switch | Phase 6 | Small-Medium |
| 9 | Phase 9: Clean handoff | Phases 5, 7 | Small |

Phases 1, 2, and 8 can be done first as small incremental steps. Phase 3 (protocol stores) is the largest single piece of work and can be developed in parallel. Everything comes together in Phases 4-5.

---

## New Files Summary

| File | Purpose |
|------|---------|
| `account/BackgroundCredentialsProvider.kt` | CredentialsProvider backed by AccountRegistry |
| `account/BackgroundWebSocketFactory.kt` | Creates standalone auth WebSocket for a given account |
| `account/BackgroundDatabaseHandle.kt` | Opens a background account's signal.db directly |
| `account/BackgroundAccountReceiver.kt` | Core receiver: WebSocket → decrypt → insert → notify |
| `account/BackgroundAccountManager.kt` | Lifecycle management for all receivers |
| `account/BackgroundMessageInserter.kt` | Direct SQL insertion into background account's message table |
| `account/BackgroundNotificationPoster.kt` | Posts notifications with account context |
| `account/BackgroundFcmRegistration.kt` | Registers FCM token for background accounts |
| `account/protocol/BackgroundSessionStore.kt` | Session store reading from raw DB handle |
| `account/protocol/BackgroundIdentityKeyStore.kt` | Identity key store reading from raw DB handle |
| `account/protocol/BackgroundPreKeyStore.kt` | Pre-key store reading from raw DB handle |
| `account/protocol/BackgroundSenderKeyStore.kt` | Sender key store reading from raw DB handle |
| `account/protocol/BackgroundProtocolStore.kt` | Composes all background protocol stores |
| `jobs/ProcessDeferredEnvelopesJob.kt` | Drains deferred envelopes on account activation |
| `jobs/FcmTokenSyncJob.kt` | Periodic FCM token re-registration for all accounts |

## Modified Files Summary

| File | Change |
|------|--------|
| `account/AccountRegistry.kt` | Add credential + identity key columns, bump schema version |
| `account/AccountSwitcher.kt` | Sync credentials, integrate BackgroundAccountManager lifecycle, schedule deferred drain |
| `gcm/FcmFetchManager.kt` | Replace `drainBackgroundAccounts()` with `BackgroundAccountManager.wakeAll()` |
| `gcm/FcmReceiveService.java` | Register FCM token for all accounts on refresh |
| `conversation/ConversationIntents.java` | Add `account_id` extra |
| `MainActivity.kt` | Handle account switch on notification tap |
| `notifications/NotificationChannels.java` | Per-account notification channel groups |
| `notifications/v2/NotificationConversation.kt` | Pass account_id in action intents |
| `notifications/MarkReadReceiver.java` | Account-aware mark-as-read |
| `notifications/RemoteReplyReceiver.java` | Account-aware reply |
| `ApplicationContext.java` | Start BackgroundAccountManager on app init |

---

## Privacy and Security Tradeoffs

### Server-Side Account Linkage via FCM Token

**Severity: High.** This is the most significant privacy concern in the entire multi-account design, not just the background receiver phase.

Every account must register the same FCM token with the Signal server (`PUT /v1/accounts/gcm`). The server can trivially observe that multiple ACIs share a token and conclude they are on the same device. If the purpose of separate accounts is to maintain unlinkable identities (e.g., work vs. personal, or pseudonymous use), this defeats that goal at the server level.

This is inherent to any approach that delivers push notifications for multiple accounts in a single app installation. The only mitigation would be running accounts in separate Android user profiles (Approach 5 in the multi-account model doc), which gets separate FCM tokens but loses the unified UX.

**Users should be informed** that their accounts are linkable to the Signal server. A disclosure in the add-account flow ("The Signal server will be able to see that these accounts are on the same device") is appropriate.

### Credential Centralization in AccountRegistry

**Severity: Medium.** Phase 1 caches each account's service password, ACI, PNI, device ID, and (optionally) identity key in `account-registry.db`. Today these live in each account's individual `signal-key-value.db`, so compromising one account's KV store doesn't expose another's credentials. After this change, `account-registry.db` becomes a high-value target — a single breach exposes all accounts.

**Mitigations:**
- The registry is already encrypted via SQLCipher with the same `DatabaseSecret` backed by AndroidKeyStore. This is the same protection level as the individual KV stores.
- Don't cache the identity private key in the registry if avoidable. Instead, open the background account's `signal-key-value.db` on demand to read it. This is more expensive but keeps the most sensitive key (identity key) in its per-account store.
- If the Molly passphrase-lock feature is added later (see `docs/molly-encrypted-database.md`), the registry should be gated behind the lock just like the main databases.

### Multiple Persistent WebSocket Connections Enable Traffic Correlation

**Severity: Medium.** Each background account maintains a persistent TCP+TLS connection to the Signal server. An adversary performing traffic analysis on the network (ISP, VPN provider, nation-state) can observe multiple concurrent WebSocket connections from the same IP to Signal's servers. Even without seeing the encrypted content, the connection pattern reveals that this device hosts multiple accounts.

Furthermore, the timing correlation between incoming messages on different connections could reveal relationships between the accounts (e.g., "both accounts received a message within 100ms of each other" might indicate a group both belong to).

**Mitigations:**
- This is partially mitigated by the fact that Signal uses a single server hostname — multiple connections to the same host are common for many reasons.
- For high-threat-model users, consider a "stealth mode" that falls back to FCM-only delivery for background accounts (no persistent WebSocket), accepting higher latency in exchange for less traffic fingerprinting. This is essentially what the current Approach 1 drain cycling does.

### Expanded Notification Attack Surface

**Severity: Medium.** With hot-swap only, notifications display for the active account only. Background receivers add notifications for all accounts — meaning message previews, sender names, and account identifiers appear on the lock screen for every account simultaneously.

This matters in scenarios where someone has physical access to the device (briefly seeing a locked phone) or where a notification listener app (accessibility service, companion device) is present.

**Mitigations:**
- Respect Android's notification privacy settings (`VISIBILITY_PRIVATE` / `VISIBILITY_SECRET`). The background notification poster should honor the same "notification content" privacy preference that the main notifier uses.
- Per-account notification privacy settings — let users configure one account to show full previews and another to show "New message" only.
- If the Molly passphrase lock is active, background receiver notifications should be redacted (show "Locked message") just like Molly does for the main account.

### Background Receiver Bypasses Full Security Checks

**Severity: Medium.** The full `MessageContentProcessor` pipeline performs several security-relevant checks that the lightweight background receiver skips:

| Check | Full pipeline | Background receiver |
|-------|--------------|-------------------|
| Identity key change detection ("safety number changed") | Yes — warns user | No — decrypts silently |
| Sealed sender verification | Yes — validates certificate | Partial — decrypts but may not fully validate |
| Group access control (is sender allowed to post?) | Yes — checks group state | No — inserts message regardless |
| Blocked contact filtering | Yes — drops messages from blocked contacts | Needs explicit check |
| Profile key validation | Yes — verifies and stores | No |
| Spam/abuse heuristics | Yes | No |

The most concerning gap is **identity key change detection**. If a contact's identity key changes while an account is in the background, the background receiver will silently accept messages encrypted with the new key. The user won't see a "safety number changed" warning until they switch to that account and the deferred processing catches up. In the intervening period, the user might trust a notification preview from what is actually a compromised or impersonated contact.

**Mitigations:**
- The background receiver should check the identity key store before decryption. If the sender's identity key has changed since last seen, mark the notification as unverified or add a warning indicator.
- At minimum, the background receiver must check the blocked contacts list before posting a notification. This is a simple query on the `recipient` table (`blocked = 1`).
- When deferred envelopes are drained on account activation, any security warnings from full processing should be shown retroactively.

### Cryptographic Material in Memory for All Accounts

**Severity: Low-Medium.** Background receivers keep session keys, identity keys, and sender keys in memory for all accounts simultaneously. In the hot-swap model, only the active account's keys are in memory; others are on disk (encrypted). With background receivers, an attacker who can dump process memory (rooted device, cold boot attack, memory forensics) captures keys for all accounts in one shot.

**Mitigations:**
- This is somewhat inherent — you can't decrypt without keys in memory.
- The `BufferedProtocolStore` used during decryption only loads keys on demand and can be cleared after each batch. Don't keep a warm cache of all sessions — load per-envelope, flush after.
- If the Molly passphrase lock is implemented, locking should clear background receiver key material as well (stop all receivers, zero key buffers).

### Deferred Envelope Queue Stores Metadata at Rest

**Severity: Low.** The `deferred_envelopes` table stores raw `Envelope` protobufs. While the message content within is encrypted (it hasn't been decrypted by the background receiver — that's why it's deferred), the envelope metadata is not:
- Sender service ID (ACI)
- Sender device ID
- Server timestamp
- Envelope type (which reveals whether it's a 1:1 message, group message, sync, call, etc.)

This metadata is already in `signal.db` for processed messages, so the incremental exposure is small. But it's a new table that could be overlooked during data-at-rest security reviews.

**Mitigation:** The deferred envelopes table lives in the account's `signal.db`, which is already SQLCipher-encrypted. No additional encryption is needed, but the table should be documented in any security audit scope.

### Direct SQL Insertion Bypasses Data Integrity Checks

**Severity: Low.** `BackgroundMessageInserter` writes directly to the `message` and `thread` tables via raw SQL. The full pipeline (`MessageTable.insertMessageInbox()`) has integrity checks, constraint enforcement, and trigger-based side effects (thread snippet updates, unread count maintenance, etc.) that the direct insert may miss.

A malformed insertion could cause crashes, UI glitches, or data inconsistency when the account becomes active and the full stack reads those rows.

**Mitigations:**
- The background inserter should be as conservative as possible — only insert the minimum fields, use `INSERT OR IGNORE` to avoid constraint violations, and explicitly update thread metadata.
- Integration tests should verify that messages inserted by the background path display correctly when loaded by the full stack.

### FCM Token Registration via Cached Credentials

**Severity: Low.** `BackgroundFcmRegistration` makes direct HTTP calls to the Signal server using cached credentials. These calls must use the same TLS certificate pinning and censorship circumvention (domain fronting, reflector endpoints) as the main `PushServiceSocket`. If the background registration uses a simpler HTTP client that doesn't enforce pinning, it's vulnerable to MITM — an attacker could intercept the request and learn the account's ACI and device ID.

**Mitigation:** Reuse the existing `SignalServiceNetworkAccess` TLS configuration (trust store, pinned certificates) when constructing HTTP clients for background API calls. Don't create a standalone `OkHttpClient` without pinning.

---

### Summary of Tradeoffs

| Concern | Severity | Inherent to multi-account? | Mitigable? |
|---------|----------|---------------------------|------------|
| Server-side account linkage (FCM token) | High | Yes — any single-app multi-account design | Only via separate OS profiles |
| Credential centralization | Medium | No — design choice | Yes — don't cache identity key, gate behind passphrase lock |
| Traffic correlation (multiple WebSockets) | Medium | Partially — persistent connections are optional | Yes — fall back to FCM-only for background accounts |
| Expanded notification surface | Medium | Yes — this is the feature | Yes — per-account privacy settings, lock screen redaction |
| Bypassed security checks (identity key changes) | Medium | No — design choice | Yes — add identity key change check to background receiver |
| Keys in memory for all accounts | Low-Medium | Mostly — can't decrypt without keys | Partially — load on demand, clear after batch |
| Deferred envelope metadata | Low | No — new table | Already encrypted by SQLCipher |
| Direct SQL integrity | Low | No — design choice | Yes — conservative inserts, integration tests |
| Background HTTP without TLS pinning | Low | No — implementation risk | Yes — reuse existing TLS config |

---

## Open Questions

1. **Battery impact of N persistent WebSockets.** Each background account holds a TCP+TLS connection. For 2-3 accounts this is probably fine. For more, we may want a strategy where background WebSockets disconnect after idle periods and rely on FCM wakes to reconnect.

2. **WAL mode and concurrent DB access.** SQLCipher in WAL mode allows concurrent reads and a single writer. The background receiver writes to the DB while the full stack might also write during a switch transition. Need to verify that SQLCipher's locking handles this correctly, or serialize access during handoff.

3. **Pre-key exhaustion.** The background receiver consumes pre-keys when decrypting pre-key messages (first messages from a new contact). It doesn't replenish them (that's a `RefreshPreKeysJob` in the full stack). If a background account receives many first messages, it could exhaust pre-keys. Mitigation: schedule pre-key refresh on next account activation, or have the background receiver do a simple pre-key upload via direct API call.

4. **Schema drift in BackgroundMessageInserter.** The direct SQL insertion must match the `message` table schema exactly. If the schema changes in a Signal update, the background inserter breaks silently. Mitigation: share column constants with `MessageTable`, or better, have the inserter use the same `ContentValues` builder that `MessageTable` uses (extracted into a shared utility).

5. **Sender key rotation in background.** Group messages use sender keys, which rotate. The background receiver handles `SenderKeyDistributionMessage` to update its sender key store, but if a rotation is missed (e.g., receiver was disconnected), subsequent group messages will fail to decrypt. These should be deferred gracefully.

6. **Should notification-tap reply work for background accounts?** The simplest v1 is: tapping Reply switches to the account and opens the compose box. Direct inline reply from a background account notification would require temporarily activating the account's message sender — possible but complex. Recommend deferring to a later iteration.
