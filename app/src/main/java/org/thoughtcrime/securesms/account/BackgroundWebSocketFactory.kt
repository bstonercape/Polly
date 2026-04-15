package org.thoughtcrime.securesms.account

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.net.DeviceTransferBlockingInterceptor
import org.thoughtcrime.securesms.net.SignalWebSocketHealthMonitor
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.api.websocket.WebSocketFactory
import org.whispersystems.signalservice.api.util.SleepTimer
import org.whispersystems.signalservice.api.util.UptimeSleepTimer
import org.whispersystems.signalservice.internal.websocket.LibSignalChatConnection
import java.util.concurrent.TimeUnit

/**
 * Creates standalone [SignalWebSocket.AuthenticatedWebSocket] instances for background
 * accounts, independent of [NetworkDependenciesModule].
 *
 * Each background WebSocket authenticates with cached credentials from [AccountRegistry]
 * and shares the app-global [Network] and TLS configuration. This avoids loading the
 * full singleton stack (SignalStore, SignalDatabase, etc.) for the background account.
 */
object BackgroundWebSocketFactory {

  private val TAG = Log.tag(BackgroundWebSocketFactory::class.java)

  private const val DISCONNECT_TIMEOUT_SECONDS = 30L

  /**
   * Creates an authenticated WebSocket for a background account.
   *
   * @param entry The account entry with cached credentials (must have [AccountRegistry.AccountEntry.hasCredentials] == true)
   * @return A ready-to-connect [SignalWebSocket.AuthenticatedWebSocket], or null if credentials are insufficient
   */
  @JvmStatic
  fun create(entry: AccountRegistry.AccountEntry): SignalWebSocket.AuthenticatedWebSocket? {
    val credentialsProvider = BackgroundCredentialsProvider.fromAccountEntry(entry)
    if (credentialsProvider == null) {
      Log.w(TAG, "Cannot create WebSocket for ${entry.accountId}: insufficient credentials")
      return null
    }

    val sleepTimer: SleepTimer = UptimeSleepTimer()
    val healthMonitor = SignalWebSocketHealthMonitor(sleepTimer)

    val connectionFactory = WebSocketFactory {
      val network = AppDependencies.libsignalNetwork
      LibSignalChatConnection(
        "background-${entry.accountId}",
        network,
        credentialsProvider,
        false, // receiveStories — background receivers defer complex messages
        healthMonitor
      )
    }

    val webSocket = SignalWebSocket.AuthenticatedWebSocket(
      connectionFactory,
      { !SignalStore.misc.isClientDeprecated && !DeviceTransferBlockingInterceptor.getInstance().isBlockingNetwork },
      sleepTimer,
      TimeUnit.SECONDS.toMillis(DISCONNECT_TIMEOUT_SECONDS)
    )

    healthMonitor.monitor(webSocket)

    Log.i(TAG, "Created background WebSocket for ${entry.accountId}")
    return webSocket
  }
}
