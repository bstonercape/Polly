# Molly Encrypted Database Feature Analysis

Reference implementation: `~/code/mollyim-android`

This document describes how Molly's passphrase-lock / encrypted-database feature works, and what it would take to bring the same capability to Polly with multi-account support.

---

## High-Level Architecture

Molly uses a **two-key system**:

| Key | Purpose | Derived from | Stored how |
|-----|---------|-------------|------------|
| **DatabaseSecret** | Encrypts SQLite databases via SQLCipher | 32 random bytes (generated once) | Encrypted by AndroidKeyStore, persisted in SharedPreferences |
| **MasterSecret** | Gates user access; cached in memory while unlocked | User's passphrase via Argon2id + HMAC | Encrypted by passphrase-derived key, persisted in SharedPreferences |

The DatabaseSecret already exists in stock Signal (and Polly). It transparently encrypts `signal.db` and `signal-key-value.db` via SQLCipher. The user never interacts with it.

The MasterSecret is Molly's addition. It acts as a gatekeeper: when the app is "locked," the MasterSecret is cleared from memory, preventing the app from accessing any sensitive state (database, keys, etc.) until the user re-enters their passphrase.

---

## Key Derivation Chain

```
User passphrase (char[])
    |
    v
PKCS12PasswordToBytes()                            // MasterSecretUtil.java:99-113
    |
    v
Argon2id (V13)                                     // PassphraseBasedKdf.java:73-81
    params: iterations, memory, parallelism
    salt: 16 random bytes (stored in SharedPrefs)
    target: ~3000ms on the device (benchmarked at setup time)
    |
    v
HMAC-SHA256 post-processing (API 23+)              // PassphraseBasedKdf.java:83-87
    key: hardware-backed HMAC key in AndroidKeyStore
    |
    v
AES key (SecureSecretKeySpec, 32 bytes)
    |
    v
AES-GCM decrypt "master_secret" from SharedPrefs   // MasterSecretUtil.java:166
    |
    v
MasterSecret = { 32-byte encryption key, 32-byte MAC key }
    cached in KeyCachingService.masterSecret (static field)
```

### Key Files

| File | Role |
|------|------|
| `crypto/MasterSecretUtil.java` | Create, change, verify passphrase; encrypt/decrypt MasterSecret |
| `crypto/PassphraseBasedKdf.java` | Argon2id derivation + HMAC post-processing |
| `crypto/Argon2Benchmark.java` | Benchmarks device to pick Argon2 params targeting ~3s |
| `crypto/KeyStoreHelper.java` | AndroidKeyStore seal/unseal (AES-GCM) and HMAC key management |
| `crypto/DatabaseSecretProvider.java` | Random DB key, sealed by AndroidKeyStore (same as stock Signal) |

---

## Lock / Unlock Lifecycle

### Lock Triggers

| Trigger | Mechanism |
|---------|-----------|
| Screen OFF | `KeyCachingService` screen receiver starts a countdown via `AlarmManager.setExactAndAllowWhileIdle()` |
| Timeout expires | Alarm fires `PASSPHRASE_EXPIRED_EVENT` -> `handleClearKey(true)` |
| Manual lock | User taps "Lock" in notification -> broadcasts `CLEAR_KEY_ACTION` |
| App backgrounded | `ScreenLockController.onAppBackgrounded()` sets `lockScreenAtStart = true` with a 700ms grace period |
| Immediate (timeout=0) | Screen OFF -> locks immediately without alarm |

### What Happens on Lock

1. `KeyCachingService.clearMasterSecret()` zeros and nulls the static `masterSecret` field
2. Broadcast `CLEAR_KEY_EVENT` sent to all activities
3. `ScreenLockController.blankScreen()` walks every window's view hierarchy, setting `android.R.id.content` views to `INVISIBLE`
4. `FLAG_SECURE` set on windows (prevents screenshots in recents)
5. Notifications are updated to hide message content (show "Locked message" instead)
6. `lockScreenAtStart = true` so next activity resume shows `PassphrasePromptActivity`

### What Happens on Unlock

1. User enters passphrase in `PassphrasePromptActivity`
2. Optional biometric gate (if enrolled)
3. Background thread runs Argon2 derivation (~3s), derives key, decrypts MasterSecret
4. `KeyCachingService.setMasterSecret(masterSecret)` caches key in memory
5. `ApplicationContext.onUnlock()` runs full app initialization (databases, job manager, network, etc.)
6. `ScreenLockController.unBlankScreen()` restores all views
7. UI proceeds normally

### Key Files

| File | Role |
|------|------|
| `service/KeyCachingService.java` | Foreground service holding `masterSecret` in memory; manages timeouts via AlarmManager; broadcasts lock events |
| `ScreenLockController.kt` | Blanks/unblanks all windows; manages lock-at-start state and grace periods |
| `PassphrasePromptActivity.java` | Lock screen UI: passphrase entry, biometric, progress animation |
| `PassphraseCreateActivity.java` | Initial setup: enable or skip passphrase |
| `ChangePassphraseDialogFragment.java` | Create / change / disable passphrase (3 modes) |
| `BaseActivity.java` | Checks `KeyCachingService.isLocked()` on resume; routes to lock screen |

---

## What Gets Encrypted

### Protected by DatabaseSecret (SQLCipher) -- same as stock Signal

- `signal.db` (messages, recipients, groups, threads, all 75+ tables)
- `signal-key-value.db` (app settings, registration state)
- `signal-jobmanager.db`

### Protected by AttachmentSecret (separate random key, also in AndroidKeyStore)

- Attachment files on disk (images, videos, documents)

### Protected by the Passphrase Lock (Molly addition)

The passphrase doesn't add a new encryption layer. Instead it gates **access**: when locked, the MasterSecret is cleared from memory, and the app refuses to proceed past the lock screen. All the above databases remain encrypted at rest by their respective keys. The passphrase lock prevents the **app process** from using them.

### NOT protected by passphrase

- Avatar files in `app_avatars/` (no encryption at rest beyond filesystem)
- Glide/image cache
- SharedPreferences XML (contains encrypted secrets but the XML itself is plaintext)
- Log files

---

## Notification Handling When Locked

When `KeyCachingService.isLocked() == true`:

- Messages still arrive (push + `ServiceWorker` still run)
- Messages are stored in the encrypted database
- Notifications display but with redacted content:
  - Message body replaced with "Locked message" (`NotificationItem.kt:229`)
  - Sender name hidden
  - Conversation title hidden
  - No thumbnails or large icons
  - Reply action stripped from notifications

---

## Passphrase Setup / Change / Disable

**Create** (`ChangePassphraseDialogFragment` MODE_ENABLE):
1. Generate random MasterSecret (32-byte encryption + 32-byte MAC)
2. Benchmark Argon2 on device (target ~3s)
3. Generate AndroidKeyStore HMAC key (UUID alias)
4. Derive AES key from passphrase
5. AES-GCM encrypt MasterSecret, store ciphertext + salt + IV + params in SharedPrefs
6. Passphrase validator (nbvcxz) estimates time-to-crack and suggests improvements

**Change** (`MODE_CHANGE`):
1. Decrypt MasterSecret with old passphrase
2. Re-benchmark Argon2, generate new salt/IV/HMAC key
3. Re-encrypt same MasterSecret with new passphrase-derived key
4. Delete old KeyStore entry

**Disable** (`MODE_DISABLE`):
1. Re-encrypt MasterSecret with the hardcoded `"unencrypted"` passphrase
2. App auto-unlocks at startup by detecting this special passphrase

---

## What It Would Take to Add This to Polly

### Complexity Assessment

Molly's passphrase lock touches approximately **8-10 core files** and many smaller integration points. The feature itself is relatively self-contained, but the lock/unlock lifecycle has tendrils throughout the app (every Activity needs to check lock state, notifications need to redact, etc.).

### Multi-Account Complications

Polly's multi-account model introduces several design questions that Molly doesn't face:

#### 1. Passphrase Scope: Per-App vs Per-Account

**Option A: Single passphrase for the whole app** (recommended for simplicity)
- One MasterSecret gates access to the entire app, all accounts
- Lock/unlock affects everything at once
- Simpler to implement -- closest to Molly's existing model
- Downside: can't share the phone with someone who should see Account A but not Account B

**Option B: Per-account passphrases**
- Each account has its own MasterSecret, salt, Argon2 params, KeyStore entry
- Could allow selective unlock (view Account A without unlocking Account B)
- Dramatically more complex: need per-account KeyCachingService state, per-account lock screens, per-account notification redaction
- The `SharedPreferences` keys would need account-scoping (e.g., `master_secret_account-0`)
- Probably not worth the complexity for a hackathon

#### 2. AccountRegistry Access While Locked

Polly's `account-registry.db` lives outside any account directory and uses the shared `DatabaseSecret`. When the app is locked:
- The registry DB still needs to be accessible (to know which accounts exist, show the lock screen, etc.)
- The registry already uses SQLCipher via `DatabaseSecretProvider`, and the `DatabaseSecret` is independent of any passphrase -- so this works as-is
- The lock screen could show account names/numbers from the registry without unlocking anything sensitive

#### 3. Account Switch While Locked

Should the user be able to switch accounts from the lock screen? Probably not -- the switch requires database teardown/reinit (`AccountSwitcher.switchToAccount`), which needs the databases to be accessible. The lock should apply to the app as a whole.

#### 4. Per-Account DatabaseSecret

Currently Polly uses a **single** `DatabaseSecretProvider.getOrCreateDatabaseSecret()` for all accounts. Each account's `signal.db` is encrypted with the same key. This is fine for the passphrase lock (which gates access, not encryption), but if you wanted true per-account encryption isolation, each account would need its own `DatabaseSecret`. This is a separate concern from the passphrase feature and probably not necessary.

### Implementation Plan

#### Phase 1: Core Passphrase Infrastructure

Port from Molly (create or adapt):

| File to create | Source in Molly | Notes |
|---|---|---|
| `crypto/MasterSecret.java` | Same | Holds encryption + MAC key pair in memory |
| `crypto/MasterSecretUtil.java` | Same | Passphrase create/change/verify/encrypt/decrypt |
| `crypto/MasterCipher.java` | Same | AES encryption using MasterSecret |
| `crypto/PassphraseBasedKdf.java` | Same | Argon2id KDF + HMAC post-processing |
| `crypto/Argon2Benchmark.java` | Same | Device-specific parameter tuning |
| `crypto/SecureSecretKeySpec.java` | Same | Zeroizable SecretKeySpec wrapper |

Dependencies to add:
- `org.signal:argon2` (Argon2 native library -- check if already in Polly's dependency tree via Signal upstream)

#### Phase 2: Lock State Management

Port from Molly:

| File | Notes |
|---|---|
| `service/KeyCachingService.java` | Foreground service holding MasterSecret; timeout management. Polly already has a `hasPassphrase` field in `MainToolbarState` -- wire it up |
| `ScreenLockController.kt` | Window blanking. Can port almost directly |
| `BaseActivity.java` changes | Add `isLocked()` check in `onPostResume()` to route to lock screen |

#### Phase 3: Lock Screen UI

Port from Molly:

| File | Notes |
|---|---|
| `PassphrasePromptActivity.java` | Lock screen with passphrase entry + biometric |
| `PassphraseCreateActivity.java` | First-time setup |
| `ChangePassphraseDialogFragment.java` | Settings UI for create/change/disable |
| Layout XMLs | `prompt_passphrase_activity.xml`, related drawables |

#### Phase 4: Integration Points

These are the scattered changes throughout the app:

- **App startup** (`ApplicationContext.onCreate`): Check if passphrase is initialized; if locked, defer full initialization until unlock
- **Activity routing** (`PassphraseRequiredActivity`): Route to lock screen before any content activity
- **Notifications** (`NotificationItem.kt`, `DefaultMessageNotifier.kt`): Redact content when `KeyCachingService.isLocked()`
- **Settings screen**: Add passphrase lock toggle, timeout picker, trigger picker (screen-off / app-backgrounded)
- **Notification channels**: Add a `LOCKED_STATUS` channel for the "unlocked" persistent notification

#### Phase 5: Multi-Account-Specific Work

New work (not in Molly):

- Ensure `AccountRegistry` remains accessible while locked (it should, since it uses `DatabaseSecret` not `MasterSecret`)
- Prevent account switching while locked (disable the switcher UI or redirect to lock screen)
- Test that `AccountSwitcher.switchToAccount()` works correctly after unlock (databases reinit properly)
- Decide whether the lock screen shows which account was last active, or just a generic lock
- Ensure `AvatarHelper.writeSelfAvatarCopy()` and other avatar operations don't run while locked

### Estimated Scope

| Phase | Effort | Files touched |
|-------|--------|--------------|
| Phase 1: Core crypto | Small | ~6 new files, mostly ported directly |
| Phase 2: Lock state | Medium | ~3 files ported + wiring |
| Phase 3: Lock UI | Medium | ~3 activities + layouts |
| Phase 4: Integration | Large | Scattered changes across 10-15 files |
| Phase 5: Multi-account | Small-Medium | ~3-5 files, mostly guards and edge cases |

The hardest part isn't any single piece -- it's Phase 4, the integration points. Every activity needs to respect the lock state, notifications need redaction, and the startup sequence needs to be bifurcated into "pre-unlock" and "post-unlock" phases. Molly has refined this over years of development.

### Recommended Approach for a Hackathon

If the goal is a working prototype quickly:

1. **Start with Option A** (single app-wide passphrase)
2. **Port Phases 1-3** mostly verbatim from Molly
3. **For Phase 4**, do the minimum: lock screen routing in `BaseActivity`, notification redaction can be deferred
4. **For Phase 5**, just disable the account switcher button while locked
5. Skip biometric support initially -- passphrase-only is simpler
6. Skip the passphrase strength validator (nbvcxz) initially

This gets you a working lock screen with Argon2-backed passphrase protection in a focused effort, with the more polish-oriented pieces (notification redaction, biometric, strength meter) as follow-ups.
