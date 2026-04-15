package org.thoughtcrime.securesms.account

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.notifications.NotificationChannels
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
    deferredStore = DeferredEnvelopeStore(application, entry.accountId)

    receiverThread = Thread({
      Log.i(TAG, "[${entry.accountId}] Receiver thread started")
      ws.connect()

      while (running.get()) {
        try {
          val hasMore = ws.readMessageBatch(WEBSOCKET_READ_TIMEOUT_MS, BATCH_SIZE) { batch ->
            for (response in batch) {
              storeAndNotify(response.envelope, response.serverDeliveredTimestamp)
            }
          }

          if (!hasMore && running.get()) {
            // Queue drained — wait for new messages
            Thread.sleep(IDLE_SLEEP_MS)
          }
        } catch (e: Exception) {
          if (running.get()) {
            Log.w(TAG, "[${entry.accountId}] Error reading from WebSocket, will retry", e)
            Thread.sleep(RETRY_SLEEP_MS)
          }
        }
      }

      Log.i(TAG, "[${entry.accountId}] Receiver thread exiting")
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
    // Store the raw envelope for processing when this account becomes active
    deferredStore?.insert(envelope, serverDeliveredTimestamp)

    // Post a lightweight notification (no decrypted content)
    postNotification()
  }

  private fun postNotification() {
    val accountLabel = entry.displayName ?: entry.e164 ?: entry.accountId
    val notificationManager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val notification = NotificationCompat.Builder(application, NotificationChannels.getInstance().messagesChannel)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(accountLabel)
      .setContentText(application.getString(R.string.FcmFetchManager__you_may_have_messages))
      .setAutoCancel(true)
      .setGroup("background_${entry.accountId}")
      .build()

    // Use a stable notification ID per account so repeated messages update the same notification
    val notificationId = NOTIFICATION_ID_BASE + entry.accountId.hashCode()
    notificationManager.notify(notificationId, notification)
  }

  companion object {
    private val TAG = Log.tag(BackgroundAccountReceiver::class.java)

    private val WEBSOCKET_READ_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30)
    private const val BATCH_SIZE = 10
    private const val IDLE_SLEEP_MS = 1000L
    private const val RETRY_SLEEP_MS = 5000L
    private const val THREAD_JOIN_TIMEOUT_MS = 5000L
    private const val NOTIFICATION_ID_BASE = 900000
  }
}
