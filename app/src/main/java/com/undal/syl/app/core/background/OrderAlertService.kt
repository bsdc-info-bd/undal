package com.undal.syl.app.core.background

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.undal.syl.app.MainActivity
import com.undal.syl.app.R
import com.undal.syl.app.core.fcm.AlarmChannels
import com.undal.syl.app.core.util.OrderStatus

/**
 * The order alert (PART I-2).
 *
 * When a new order reaches a locked, sleeping kitchen phone this service:
 *   1. plays the official Undal order sound on the ALARM audio stream, looping,
 *      with a partial wake lock held by the player itself,
 *   2. vibrates an answer-the-phone pattern,
 *   3. posts an ongoing HIGH-importance notification with a full-screen intent,
 *      so the phone lights up and the lock screen shows Accept / Reject / Open,
 *   4. watches the Realtime Database mirror and stops the moment any device —
 *      this phone or the other kitchen phone — answers the order.
 *
 * The sound stops ONLY when no order is left waiting.
 *
 * Two orders are not one order
 * ----------------------------
 * This service used to ring for whichever order arrived last: a second order
 * arriving a minute later cancelled the first one's alert, and the first order
 * went silent with nobody having looked at it. That is the single most expensive
 * bug a kitchen can have, so the alert is now a **queue**:
 *
 *   · the oldest unanswered order is the one that rings, because it is the one
 *     that has waited longest;
 *   · a second order does not interrupt it — it joins the queue and the
 *     notification says how many are waiting;
 *   · the sound stops when the last order is answered, and not before.
 *
 * Alerts arrive from three independent routes (a push from the Worker, the live
 * Firestore watcher, or the Worker's re-alert sweep), so `ring` is idempotent per
 * order: the same order can arrive three times and ring once.
 */
class OrderAlertService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var liveReference: com.google.firebase.database.DatabaseReference? = null
    private var currentOrderId: String? = null
    private var currentShortId: String = ""

    /**
     * Every order waiting for an answer, in the order it arrived. The head of the
     * list is the order the phone is ringing for.
     */
    private val waiting = linkedMapOf<String, Waiting>()

    /** A test alert stops itself; a real order never does. */
    private val stopTestRunnable = Runnable {
        val idle = waiting.entries.firstOrNull { it.value.isTest }?.key
        if (idle != null) {
            waiting.remove(idle)
            if (waiting.isEmpty()) stopAlert(null) else ringHead()
        }
    }

    private data class Waiting(
        val orderId: String,
        val shortId: String,
        val title: String,
        val body: String,
        val isTest: Boolean
    )

    private val liveListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val status = snapshot.child("status").getValue(String::class.java)
            // Anything other than `placed` means somebody answered the order —
            // possibly the other kitchen phone. Drop it from the queue and, if
            // that was the order that was ringing, start the next one.
            if (status != null && status != OrderStatus.PLACED.wire) {
                val orderId = currentOrderId
                if (orderId != null && waiting.remove(orderId) != null) {
                    if (waiting.isEmpty()) stopAlert(orderId) else ringHead()
                }
            }
        }

        override fun onCancelled(error: DatabaseError) {
            Log.w(TAG, "orderLive listener cancelled: ${error.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AlarmChannels.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAlert(intent.getStringExtra(EXTRA_ORDER_ID))
                return START_NOT_STICKY
            }
            ACTION_RING, null -> {
                val orderId = intent?.getStringExtra(EXTRA_ORDER_ID)
                val shortId = intent?.getStringExtra(EXTRA_SHORT_ID).orEmpty()
                val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "New order" }
                val body = intent?.getStringExtra(EXTRA_BODY).orEmpty()
                val isTest = intent?.getBooleanExtra(EXTRA_IS_TEST, false) ?: false
                if (orderId.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                beginAlert(orderId, shortId, title, body, isTest)
            }
            else -> stopSelf()
        }
        return START_STICKY
    }

    private fun beginAlert(orderId: String, shortId: String, title: String, body: String, isTest: Boolean) {
        // Idempotent: three routes can deliver the same order, and the re-alert
        // sweep delivers it again every minute. One entry per order, and a repeat
        // only refreshes what the notification says.
        val alreadyWaiting = waiting.containsKey(orderId)

        // Foreground first, before any of the work below: Android 14/15 kills a
        // service that plays audio before it calls startForeground, and a kill
        // here is a missed order. The very first ring has to establish it, which
        // is why this comes before the early return for a repeat.
        val head = waiting.entries.firstOrNull()?.value
        val ringing = if (alreadyWaiting && head != null) head else Waiting(orderId, shortId, title, body, isTest)
        if (!alreadyWaiting) {
            // Newest last: `linkedMapOf` keeps arrival order, so the head stays
            // the oldest unanswered order even when a second order arrives while
            // the phone is ringing for the first.
            waiting[orderId] = ringing
        } else {
            waiting[orderId] = ringing.copy(shortId = shortId, title = title, body = body)
        }

        val foregroundOrder = waiting.entries.first().value
        ServiceCompat.startForeground(
            this,
            AlarmChannels.ORDER_ALERT_NOTIFICATION_ID,
            buildNotification(foregroundOrder),
            foregroundType()
        )

        if (alreadyWaiting) {
            // Already ringing for this order (or for an older one): refresh the
            // text, keep the sound. Never restart the player — that is audible.
            publishNotification(foregroundOrder)
            return
        }

        currentOrderId = foregroundOrder.orderId
        currentShortId = foregroundOrder.shortId
        OrderAlertController.start(
            OrderAlertController.RingingOrder(
                orderId = foregroundOrder.orderId,
                shortId = foregroundOrder.shortId,
                title = foregroundOrder.title,
                body = ringBody(foregroundOrder),
                startedAtMillis = System.currentTimeMillis()
            )
        )
        publishNotification(foregroundOrder)

        startSound()
        startVibration()
        watchOrderLive(foregroundOrder.orderId)

        if (isTest) {
            // A test must never ring until somebody drives to the kitchen. It
            // stops itself, and it is the only alert that does.
            handler.removeCallbacks(stopTestRunnable)
            handler.postDelayed(stopTestRunnable, TEST_SECONDS * 1000L)
        }
    }

    /** Moves the alert to the head of the queue, keeping the sound running. */
    private fun ringHead() {
        val next = waiting.entries.firstOrNull()?.value
        if (next == null) {
            stopAlert(null)
            return
        }
        currentOrderId = next.orderId
        currentShortId = next.shortId
        OrderAlertController.start(
            OrderAlertController.RingingOrder(
                orderId = next.orderId,
                shortId = next.shortId,
                title = next.title,
                body = ringBody(next),
                startedAtMillis = System.currentTimeMillis()
            )
        )
        notifyManager().notify(AlarmChannels.ORDER_ALERT_NOTIFICATION_ID, buildNotification(next))
        watchOrderLive(next.orderId)
        if (player?.isPlaying != true) startSound()
        if (player?.isPlaying == true && vibrator == null) startVibration()
    }

    /** "2 orders waiting" when the queue is longer than one. */
    private fun ringBody(order: Waiting): String {
        val others = waiting.size - 1
        return if (others <= 0) order.body else "${order.body} · $others more waiting"
    }

    private fun foregroundType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    } else {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
    }

    private fun startSound() {
        if (player != null) return
        player = runCatching {
            MediaPlayer.create(this, R.raw.undal_order_alert)?.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                // The player holds a partial wake lock for us, which is exactly
                // what is needed for the sound to survive a locked screen.
                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "order sound failed (what=$what extra=$extra)")
                    true
                }
                start()
            }
        }.getOrElse { error ->
            Log.e(TAG, "could not start the order sound", error)
            null
        }
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService<VibratorManager>()?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        runCatching {
            vibrator?.vibrate(
                VibrationEffect.createWaveform(VIBRATION_PATTERN, 0)
            )
        }
    }

    private fun watchOrderLive(orderId: String) {
        liveReference?.removeEventListener(liveListener)
        liveReference = FirebaseDatabase.getInstance().getReference("orderLive").child(orderId)
        liveReference?.addValueEventListener(liveListener)
    }

    private fun buildNotification(order: Waiting): Notification {
        val orderId = order.orderId
        val openIntent = PendingIntent.getActivity(
            this,
            requestCode(orderId, "open"),
            MainActivity.intentFor(this, "vendor/order/$orderId"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val acceptIntent = PendingIntent.getActivity(
            this,
            requestCode(orderId, "accept"),
            MainActivity.intentFor(this, "vendor/order/$orderId?sheet=accept"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val rejectIntent = PendingIntent.getActivity(
            this,
            requestCode(orderId, "reject"),
            MainActivity.intentFor(this, "vendor/order/$orderId?sheet=reject"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val isTest = order.isTest
        val waiting = waiting.size

        val title = when {
            isTest -> "TEST · ${getString(R.string.alert_test_title)}"
            order.title.isNotBlank() -> order.title
            order.shortId.isNotBlank() -> "New order ${order.shortId}"
            else -> "New order"
        }
        val body = when {
            isTest -> getString(R.string.alert_test_body)
            waiting > 1 -> ringBody(order)
            order.body.isNotBlank() -> order.body
            else -> "Tap to review and accept"
        }

        return NotificationCompat.Builder(this, AlarmChannels.VENDOR_NEW_ORDER)
            .setSmallIcon(R.drawable.ic_undal_mark)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .setSound(AlarmChannels.orderSoundUri(this))
            .setVibrate(VIBRATION_PATTERN)
            .setFullScreenIntent(openIntent, true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_undal_mark, "Accept", acceptIntent)
            .addAction(R.drawable.ic_undal_mark, "Reject", rejectIntent)
            .addAction(R.drawable.ic_undal_mark, "Open", openIntent)
            .build()
    }

    private fun publishNotification(order: Waiting) {
        if (!notifyManager().areNotificationsEnabled()) {
            // The sound is played by this service, not by the notification, so a
            // revoked notification permission must not silence the kitchen. It is
            // reported by the readiness screen instead of being hidden.
            Log.i(TAG, "notifications are disabled; the alarm is still ringing")
            return
        }
        runCatching {
            notifyManager().notify(AlarmChannels.ORDER_ALERT_NOTIFICATION_ID, buildNotification(order))
        }
    }

    private fun notifyManager(): NotificationManagerCompat = NotificationManagerCompat.from(this)

    /**
     * Silences an order and, when it was the one ringing, starts the next one.
     * `orderId == null` silences everything, which is what the sign-out and
     * destroyed-service paths want.
     */
    private fun stopAlert(orderId: String?) {
        if (orderId == null) {
            waiting.clear()
        } else {
            waiting.remove(orderId)
            if (waiting.isNotEmpty()) {
                // Somebody answered an order while another is still unanswered.
                ringHead()
                return
            }
        }

        liveReference?.removeEventListener(liveListener)
        liveReference = null

        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null

        runCatching { vibrator?.cancel() }
        vibrator = null

        handler.removeCallbacks(stopTestRunnable)

        OrderAlertController.stop(orderId ?: currentOrderId)
        currentOrderId = null

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notifyManager().cancel(AlarmChannels.ORDER_ALERT_NOTIFICATION_ID)
        stopSelf()
    }

    override fun onDestroy() {
        liveReference?.removeEventListener(liveListener)
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        handler.removeCallbacks(stopTestRunnable)
        OrderAlertController.stop(currentOrderId)
        super.onDestroy()
    }

    private fun requestCode(orderId: String, action: String): Int =
        (orderId + action).hashCode() and 0x7FFFFFFF

    companion object {
        private const val TAG = "UndalAlert"
        const val ACTION_RING = "com.undal.syl.app.action.RING_ORDER"
        const val ACTION_STOP = "com.undal.syl.app.action.STOP_ALERT"
        const val EXTRA_ORDER_ID = "orderId"
        const val EXTRA_SHORT_ID = "shortId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_IS_TEST = "isTest"

        /** Which route started the alert; written to the log so a support call is short. */
        const val SOURCE_PUSH = "push"
        const val SOURCE_WATCH = "watcher"

        /** How long a "ring my phone" test rings before it stops itself. */
        private const val TEST_SECONDS = 45

        private val VIBRATION_PATTERN = longArrayOf(0, 500, 250, 500, 250, 500)

        /** Starts (or refreshes) the alert loop from an FCM callback or a UI action. */
        fun ring(
            context: Context,
            orderId: String,
            shortId: String,
            title: String,
            body: String,
            isTest: Boolean = false,
            source: String = SOURCE_PUSH
        ) {
            Log.i(TAG, "ring $orderId via $source (test=$isTest)")
            val intent = Intent(context, OrderAlertService::class.java).apply {
                action = ACTION_RING
                putExtra(EXTRA_ORDER_ID, orderId)
                putExtra(EXTRA_SHORT_ID, shortId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
                putExtra(EXTRA_IS_TEST, isTest)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Silences the loop. Called when an order is answered anywhere. */
        fun silence(context: Context, orderId: String? = null) {
            val intent = Intent(context, OrderAlertService::class.java).apply {
                action = ACTION_STOP
                if (orderId != null) putExtra(EXTRA_ORDER_ID, orderId)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
