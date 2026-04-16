package org.thoughtcrime.securesms.account

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.reactivex.rxjava3.disposables.Disposable
import org.signal.core.util.PendingIntentFlags.immutable
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.MainActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.notifications.NotificationIds
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.internal.push.Envelope
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight receiver for a background (non-active) account. Maintains a WebSocket
 * connection, reads incoming envelopes, stores them for deferred processing, and posts
 * a notification so the user knows a message arrived.
 *
 * This receiver intentionally does NOT decrypt messages. The [MessageDecryptor] and
 * [MessageContentProcessor] are tightly coupled to the singleton stack (SignalStore,
 * SignalDatabase, AppDependencies) which points to the active account. All envelopes
 * received in the background are stored raw and processed through the full pipeline
 * when the user switches to this account.
 *
 * Lifecycle is managed by [BackgroundAccountManager].
 */
class BackgroundAccountReceiver(
  private val application: Application,
  private val entry: AccountRegistry.AccountEntry
) : Closeable {

  private val running = AtomicBoolean(false)
  private var webSocket: SignalWebSocket.AuthenticatedWebSocket? = null
  private var receiverThread: Thread? = null
  private var deferredStore: DeferredEnvelopeStore? = null

  // Stable per-account notification ID in the BACKGROUND_ACCOUNT_MESSAGE range.
  // Account IDs are "account-0", "account-1", etc. — parse the index directly.
  private val notificationId: Int = run {
    val index = entry.accountId.removePrefix("account-").toIntOrNull() ?: 0
    NotificationIds.BACKGROUND_ACCOUNT_MESSAGE + index
  }

  /**
   * Starts the receiver: creates the WebSocket, connects, and begins the
   * envelope reading loop on a background thread.
   */
  fun start() {
    if (running.getAndSet(true)) {
      Log.w(TAG, "[${entry.accountId}] Already running")
      return
    }

    val ws = BackgroundWebSocketFactory.create(entry)
    if (ws == null) {
      Log.w(TAG, "[${entry.accountId}] Could not create WebSocket — insufficient credentials")
      running.set(false)
      return
    }
    webSocket = ws
    try {
      deferredStore = DeferredEnvelopeStore(application, entry.accountId)
    } catch (e: Exception) {
      Log.w(TAG, "[${entry.accountId}] Failed to open deferred envelope store", e)
      running.set(false)
      ws.disconnect()
      webSocket = null
      return
    }

    receiverThread = Thread({
      Log.i(TAG, "[${entry.accountId}] Receiver thread started")
      ws.registerKeepAliveToken(KEEP_ALIVE_TOKEN)

      // Log WebSocket state transitions so we can confirm it actually connects.
      val stateDisposable = ws.state.subscribe { state ->
        Log.i(TAG, "[${entry.accountId}] WebSocket state → $state")
      }

      ws.connect()
      Log.i(TAG, "[${entry.accountId}] connect() called, waiting for messages…")

      var totalReceived = 0
      while (running.get()) {
        try {
          Log.d(TAG, "[${entry.accountId}] readMessageBatch() blocking (timeout=${WEBSOCKET_READ_TIMEOUT_MS}ms)")
          val hasMore = ws.readMessageBatch(WEBSOCKET_READ_TIMEOUT_MS, BATCH_SIZE) { batch ->
            Log.i(TAG, "[${entry.accountId}] Batch received: ${batch.size} envelope(s)")
            for (response in batch) {
              totalReceived++
              Log.i(TAG, "[${entry.accountId}] Storing envelope #$totalReceived (type=${response.envelope.type})")
              storeAndNotify(response.envelope, response.serverDeliveredTimestamp)
              try {
                ws.sendAck(response)
                Log.d(TAG, "[${entry.accountId}] ACKed envelope #$totalReceived")
              } catch (e: Exception) {
                Log.w(TAG, "[${entry.accountId}] Failed to ack envelope #$totalReceived", e)
              }
            }
          }

          if (!hasMore && running.get()) {
            Log.d(TAG, "[${entry.accountId}] Queue drained (total received so far: $totalReceived), sleeping ${IDLE_SLEEP_MS}ms")
            Thread.sleep(IDLE_SLEEP_MS)
          }
        } catch (e: java.util.concurrent.TimeoutException) {
          // No messages arrived in the read window — normal idle behavior, just loop.
        } catch (e: Exception) {
          if (running.get()) {
            Log.w(TAG, "[${entry.accountId}] Error reading from WebSocket (total received: $totalReceived), will retry in ${RETRY_SLEEP_MS}ms", e)
            Thread.sleep(RETRY_SLEEP_MS)
          }
        }
      }

      stateDisposable.dispose()
      Log.i(TAG, "[${entry.accountId}] Receiver thread exiting (total received: $totalReceived)")
    }, "bg-receiver-${entry.accountId}").apply {
      isDaemon = true
      start()
    }

    Log.i(TAG, "[${entry.accountId}] Background receiver started")
  }

  /**
   * Stops the receiver: disconnects the WebSocket, stops the thread, and
   * closes the deferred store.
   */
  fun stop() {
    if (!running.getAndSet(false)) return

    Log.i(TAG, "[${entry.accountId}] Stopping background receiver")
    webSocket?.removeKeepAliveToken(KEEP_ALIVE_TOKEN)
    webSocket?.disconnect()
    receiverThread?.interrupt()
    try {
      receiverThread?.join(THREAD_JOIN_TIMEOUT_MS)
    } catch (_: InterruptedException) { }
    deferredStore?.close()
    deferredStore = null
    webSocket = null
    receiverThread = null
  }

  /**
   * Called on FCM push — reconnects the WebSocket if it's been disconnected.
   */
  fun wake() {
    if (!running.get()) return
    try {
      webSocket?.connect()
    } catch (e: Exception) {
      Log.w(TAG, "[${entry.accountId}] Failed to reconnect on wake", e)
    }
  }

  override fun close() = stop()

  private fun storeAndNotify(envelope: Envelope, serverDeliveredTimestamp: Long) {
    deferredStore?.insert(envelope, serverDeliveredTimestamp)
    postNotification()
  }

  private fun postNotification() {
    val permissionGranted = ContextCompat.checkSelfPermission(application, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    Log.i(TAG, "[${entry.accountId}] postNotification: POST_NOTIFICATIONS granted=$permissionGranted, notificationId=$notificationId")
    if (!permissionGranted) {
      return
    }

    val channelId = try {
      NotificationChannels.getInstance().ADDITIONAL_MESSAGE_NOTIFICATIONS
    } catch (e: Exception) {
      Log.w(TAG, "[${entry.accountId}] Failed to get notification channel", e)
      return
    }
    Log.i(TAG, "[${entry.accountId}] Using channel: $channelId")

    val notificationManager = NotificationManagerCompat.from(application)
    val areEnabled = notificationManager.areNotificationsEnabled()
    Log.i(TAG, "[${entry.accountId}] areNotificationsEnabled=$areEnabled")
    if (!areEnabled) {
      Log.w(TAG, "[${entry.accountId}] Notifications disabled at app level, skipping")
      return
    }

    val accountLabel = entry.displayName ?: entry.e164 ?: entry.accountId
    val tapIntent = PendingIntent.getActivity(
      application,
      notificationId,
      MainActivity.backgroundAccountNotification(application, entry.accountId),
      immutable()
    )

    val notification = NotificationCompat.Builder(application, channelId)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(accountLabel)
      .setContentText(application.getString(R.string.FcmFetchManager__you_may_have_messages))
      .setCategory(NotificationCompat.CATEGORY_MESSAGE)
      .setContentIntent(tapIntent)
      .setVibrate(longArrayOf(0))
      .setOnlyAlertOnce(true)
      .setAutoCancel(true)
      .build()

    Log.i(TAG, "[${entry.accountId}] Posting notification id=$notificationId for '$accountLabel'")
    notificationManager.notify(notificationId, notification)
    Log.i(TAG, "[${entry.accountId}] notify() returned (notification posted)")
  }

  companion object {
    private val TAG = Log.tag(BackgroundAccountReceiver::class.java)

    private val WEBSOCKET_READ_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30)
    private const val BATCH_SIZE = 10
    private const val IDLE_SLEEP_MS = 1000L
    private const val RETRY_SLEEP_MS = 5000L
    private const val THREAD_JOIN_TIMEOUT_MS = 5000L
    private const val KEEP_ALIVE_TOKEN = "BackgroundAccountReceiver"
  }
}
