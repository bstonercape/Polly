package org.thoughtcrime.securesms.account

import android.app.Application
import org.signal.core.util.logging.Log

/**
 * Manages the lifecycle of [BackgroundAccountReceiver] instances for all non-active accounts.
 *
 * Responsibilities:
 * - Start receivers for all background accounts on app launch
 * - Stop/start receivers when accounts are switched (demote old active → background, promote new active → full stack)
 * - Wake all receivers on FCM push
 * - Stop all receivers on shutdown
 *
 * Thread safety: all methods synchronize on [receivers] to avoid races between
 * FCM wakes, account switches, and app lifecycle events.
 */
object BackgroundAccountManager {

  private val TAG = Log.tag(BackgroundAccountManager::class.java)

  private val receivers = mutableMapOf<String, BackgroundAccountReceiver>()

  /**
   * Starts background receivers for all non-active accounts that have credentials.
   * Called during app initialization after the account registry and network are ready.
   */
  @JvmStatic
  fun startAll(application: Application) {
    val registry = AccountRegistry.getInstance(application)
    val activeId = registry.getActiveAccount()?.accountId
    val accounts = registry.getAllAccounts()

    if (accounts.size <= 1) return

    synchronized(receivers) {
      for (account in accounts) {
        if (account.accountId != activeId && account.hasCredentials && !receivers.containsKey(account.accountId)) {
          startReceiver(application, account)
        }
      }
    }

    Log.i(TAG, "Started ${receivers.size} background receiver(s)")
  }

  /**
   * Stops all background receivers. Called on app shutdown or when multi-account is disabled.
   */
  @JvmStatic
  fun stopAll() {
    synchronized(receivers) {
      Log.i(TAG, "Stopping ${receivers.size} background receiver(s)")
      receivers.values.forEach { it.stop() }
      receivers.clear()
    }
  }

  /**
   * Handles an account switch. Must be called BEFORE [AccountSwitcher.switchToAccount]
   * tears down the singletons, so the background receiver for the new active account
   * can release its database handle first.
   *
   * @param application The application context
   * @param oldActiveId The account that was active (will become a background receiver)
   * @param newActiveId The account becoming active (its background receiver will be stopped)
   */
  @JvmStatic
  fun onAccountSwitch(application: Application, oldActiveId: String?, newActiveId: String) {
    synchronized(receivers) {
      // Stop receiver for the account that's about to become active —
      // it needs to release its DB handle before SignalDatabase.reinit() opens the same file.
      val removed = receivers.remove(newActiveId)
      if (removed != null) {
        Log.i(TAG, "Stopping background receiver for newly-active account $newActiveId")
        removed.stop()
      }

      // Start a receiver for the account that's being demoted to background.
      // We do this after the switch completes (called from AccountSwitcher after reinit),
      // but we record the intent here. The actual start is in onAccountSwitchComplete().
    }
  }

  /**
   * Called AFTER [AccountSwitcher.switchToAccount] has finished reinitializing the
   * singletons for the new active account. Starts a background receiver for the
   * previously-active account.
   */
  @JvmStatic
  fun onAccountSwitchComplete(application: Application, oldActiveId: String?) {
    if (oldActiveId == null) return

    synchronized(receivers) {
      if (receivers.containsKey(oldActiveId)) return

      val registry = AccountRegistry.getInstance(application)
      val oldAccount = registry.getAllAccounts().find { it.accountId == oldActiveId }
      if (oldAccount != null && oldAccount.hasCredentials) {
        Log.i(TAG, "Starting background receiver for demoted account $oldActiveId")
        startReceiver(application, oldAccount)
      }
    }
  }

  /**
   * Wakes all background receivers. Called on FCM push to ensure all accounts
   * reconnect their WebSockets and drain any pending messages.
   */
  @JvmStatic
  fun wakeAll() {
    synchronized(receivers) {
      if (receivers.isNotEmpty()) {
        Log.d(TAG, "Waking ${receivers.size} background receiver(s)")
        receivers.values.forEach { it.wake() }
      }
    }
  }

  /**
   * Stops a single receiver (e.g., when an account is removed).
   */
  @JvmStatic
  fun stopReceiver(accountId: String) {
    synchronized(receivers) {
      receivers.remove(accountId)?.stop()
    }
  }

  private fun startReceiver(application: Application, account: AccountRegistry.AccountEntry) {
    if (!AccountFileManager.accountHasData(application, account.accountId)) {
      Log.w(TAG, "[${account.accountId}] Database not yet initialized — skipping receiver start")
      return
    }
    try {
      val receiver = BackgroundAccountReceiver(application, account)
      receivers[account.accountId] = receiver
      receiver.start()
    } catch (e: Exception) {
      Log.w(TAG, "[${account.accountId}] Failed to start background receiver", e)
    }
  }
}
