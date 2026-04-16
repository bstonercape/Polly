/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.registration.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.ActivityNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BaseActivity
import org.thoughtcrime.securesms.MainActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.account.AccountSwitcher
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.registration.sms.SmsRetrieverReceiver
import org.thoughtcrime.securesms.registration.util.RegistrationUtil
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme

/**
 * Activity to hold the entire registration process.
 */
class RegistrationActivity : BaseActivity() {

  private val TAG = Log.tag(RegistrationActivity::class.java)

  private val dynamicTheme = DynamicNoActionBarTheme()
  val sharedViewModel: RegistrationViewModel by viewModels()

  private var smsRetrieverReceiver: SmsRetrieverReceiver? = null

  init {
    lifecycle.addObserver(SmsRetrieverObserver())
  }

  val isAddingAccount: Boolean
    get() = intent.hasExtra(ADD_ACCOUNT_NEW_ID_EXTRA)

  override fun onCreate(savedInstanceState: Bundle?) {
    dynamicTheme.onCreate(this)

    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_registration_navigation_v3)

    sharedViewModel.isReregister = intent.getBooleanExtra(RE_REGISTRATION_EXTRA, false)

    sharedViewModel.checkpoint.observe(this) {
      if (it >= RegistrationCheckpoint.LOCAL_REGISTRATION_COMPLETE) {
        RegistrationUtil.maybeMarkRegistrationComplete()
        handleSuccessfulVerify()
      }
    }
  }

  /**
   * Cancels an in-progress "add account" flow. Switches back to the previous account,
   * removes the partially-created account from the registry, and deletes its directory.
   */
  fun cancelAddAccount() {
    val newAccountId = intent.getStringExtra(ADD_ACCOUNT_NEW_ID_EXTRA) ?: return
    val previousAccountId = intent.getStringExtra(ADD_ACCOUNT_PREVIOUS_ID_EXTRA)

    lifecycleScope.launch(Dispatchers.IO) {
      if (previousAccountId != null) {
        AccountSwitcher.switchToAccount(application, previousAccountId)
      }
      AccountSwitcher.removeAccount(application, newAccountId)
      withContext(Dispatchers.Main) {
        startActivity(MainActivity.clearTop(this@RegistrationActivity))
        finish()
      }
    }
  }

  override fun onResume() {
    super.onResume()
    dynamicTheme.onResume(this)
  }

  private fun handleSuccessfulVerify() {
    if (SignalStore.account.isPrimaryDevice && SignalStore.account.isMultiDevice) {
      SignalStore.misc.shouldShowLinkedDevicesReminder = sharedViewModel.isReregister
    }

    lifecycleScope.launch(Dispatchers.IO) {
      // Persist the newly-registered account's credentials (ACI, e164, servicePassword) into
      // the AccountRegistry before we navigate away. Without this, a process restart before the
      // account switcher is opened would trigger cleanupPartialAccounts() to see this account as
      // credential-less and permanently delete it.
      AccountSwitcher.syncRegistryWithActiveAccount(application)
      withContext(Dispatchers.Main) {
        // When adding a new account, omit SINGLE_TOP so Android destroys the existing
        // MainActivity instance (standard launchMode) and creates a fresh one. This
        // ensures ViewModels are re-initialized for the newly active account rather
        // than showing stale data from the previous account.
        val intent = if (isAddingAccount) {
          MainActivity.clearTopForNewAccount(this@RegistrationActivity)
        } else {
          MainActivity.clearTop(this@RegistrationActivity)
        }
        startActivity(intent)
        finish()
        ActivityNavigator.applyPopAnimationsToPendingTransition(this@RegistrationActivity)
      }
    }
  }

  private inner class SmsRetrieverObserver : DefaultLifecycleObserver {
    override fun onCreate(owner: LifecycleOwner) {
      smsRetrieverReceiver = SmsRetrieverReceiver(application)
      smsRetrieverReceiver?.registerReceiver()
    }

    override fun onDestroy(owner: LifecycleOwner) {
      smsRetrieverReceiver?.unregisterReceiver()
      smsRetrieverReceiver = null
    }
  }

  companion object {
    const val RE_REGISTRATION_EXTRA: String = "re_registration"
    const val ADD_ACCOUNT_NEW_ID_EXTRA: String = "add_account_new_id"
    const val ADD_ACCOUNT_PREVIOUS_ID_EXTRA: String = "add_account_previous_id"

    @JvmStatic
    fun newIntentForNewRegistration(context: Context, originalIntent: Intent): Intent {
      return Intent(context, RegistrationActivity::class.java).apply {
        putExtra(RE_REGISTRATION_EXTRA, false)
        setData(originalIntent.data)
      }
    }

    @JvmStatic
    fun newIntentForAddAccount(context: Context, originalIntent: Intent, newAccountId: String, previousAccountId: String?): Intent {
      return Intent(context, RegistrationActivity::class.java).apply {
        putExtra(RE_REGISTRATION_EXTRA, false)
        putExtra(ADD_ACCOUNT_NEW_ID_EXTRA, newAccountId)
        previousAccountId?.let { putExtra(ADD_ACCOUNT_PREVIOUS_ID_EXTRA, it) }
        setData(originalIntent.data)
      }
    }

    @JvmStatic
    fun newIntentForReRegistration(context: Context): Intent {
      return Intent(context, RegistrationActivity::class.java).apply {
        putExtra(RE_REGISTRATION_EXTRA, true)
      }
    }
  }
}
