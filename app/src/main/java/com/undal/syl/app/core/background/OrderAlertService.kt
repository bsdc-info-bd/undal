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
 * The sound stops ONLY when the order leaves `placed`. A missed order cannot
 * quietly stay silent: silence is reserved for a resolved order.
 */
class OrderAlertService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var liveReference: com.google.firebase.database.DatabaseReference? = null
    private var currentOrderId: String? = null
    private var currentShortId: String = ""

    private val liveListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val status = snapshot.child("status").getValue(String::class.java)
            // Anything other than `placed` means somebody answered the order.
            if (status != null && status != "placed") {
                stopAlert(currentOrderId)
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
                if (orderId.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                beginAlert(orderId, shortId, title, body)
            }
            else -> stopSelf()
        }
        return START_STICKY
    }

    private fun beginAlert(orderId: String, shortId: String, title: String, body: String) {
        // Idempotent: a re-fired push must not stack two players or two alarms.
        if (currentOrderId == orderId && player?.isPlaying == true) {
            publishNotification(orderId, shortId, title, body)
            return
        }
        if (currentOrderId != null && currentOrderId != orderId) {
            stopAlert(currentOrderId)
        }
        currentOrderId = orderId
        currentShortId = shortId

        OrderAlertController.start(
            OrderAlertController.RingingOrder(
                orderId = orderId,
                shortId = shortId,
                title = title,
                body = body,
                startedAtMillis = System.currentTimeMillis()
            )
        )

        // Foreground first: Android 14/15 kills a service that plays audio
        // before it calls startForeground, and a kill here is a missed order.
        val notification = buildNotification(orderId, shortId, title, body)
        ServiceCompat.startForeground(
            this,
            AlarmChannels.ORDER_ALERT_NOTIFICATION_ID,
            notification,
            foregroundType()
        )

        startSound()
        startVibration()
        watchOrderLive(orderId)
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

    private fun buildNotification(orderId: String, shortId: String, title: String, body: String): Notification {
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

        return NotificationCompat.Builder(this, AlarmChannels.VENDOR_NEW_ORDER)
            .setSmallIcon(R.drawable.ic_undal_mark)
            .setContentTitle(title.ifBlank { "New order $shortId" })
            .setContentText(body.ifBlank { "Tap to review and accept" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSound(AlarmChannels.orderSoundUri(this))
            .setVibrate(VIBRATION_PATTERN)
            .setFullScreenIntent(openIntent, true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_undal_mark, "Accept", acceptIntent)
            .addAction(R.drawable.ic_undal_mark, "Reject", rejectIntent)
            .addAction(R.drawable.ic_undal_mark, "Open", openIntent)
            .build()
    }

    private fun publishNotification(orderId: String, shortId: String, title: String, body: String) {
        val manager = NotificationManagerCompat.from(this)
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            runCatching {
                manager.notify(
                    AlarmChannels.ORDER_ALERT_NOTIFICATION_ID,
                    buildNotification(orderId, shortId, title, body)
                )
            }
        }
    }

    private fun stopAlert(orderId: String?) {
        if (orderId != null && currentOrderId != null && orderId != currentOrderId) return

        liveReference?.removeEventListener(liveListener)
        liveReference = null

        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null

        runCatching { vibrator?.cancel() }
        vibrator = null

        OrderAlertController.stop(currentOrderId)
        currentOrderId = null

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(AlarmChannels.ORDER_ALERT_NOTIFICATION_ID)
        stopSelf()
    }

    override fun onDestroy() {
        liveReference?.removeEventListener(liveListener)
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
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

        private val VIBRATION_PATTERN = longArrayOf(0, 500, 250, 500, 250, 500)

        /** Starts (or refreshes) the alert loop from an FCM callback or a UI action. */
        fun ring(
            context: Context,
            orderId: String,
            shortId: String,
            title: String,
            body: String
        ) {
            val intent = Intent(context, OrderAlertService::class.java).apply {
                action = ACTION_RING
                putExtra(EXTRA_ORDER_ID, orderId)
                putExtra(EXTRA_SHORT_ID, shortId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
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
