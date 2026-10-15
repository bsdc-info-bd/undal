package com.undal.syl.app.core.background

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.undal.syl.app.MainActivity
import com.undal.syl.app.R
import com.undal.syl.app.core.fcm.AlarmChannels
import com.undal.syl.app.core.util.TimeDhaka
import java.time.Instant

/**
 * The prep countdown (PART I-3 · V-034).
 *
 * After the kitchen accepts an order this sticky notification counts down to
 * `prepEndsAt` using the system chronometer — so the numbers keep ticking even
 * if the process is frozen — and plays a short synthesised tick every ten
 * seconds so a busy cook never loses the thread while the phone is in a pocket.
 *
 * Once the promise is broken the notification turns red and escalates; the
 * Worker sends the extra push from the other side.
 */
class PrepTimerService : Service() {

    private var soundPool: SoundPool? = null
    private var tickSoundId: Int = 0
    private val handler = Handler(Looper.getMainLooper())
    private var prepEndsAtMillis: Long = 0L
    private var orderId: String = ""
    private var shortId: String = ""

    private val ticker = object : Runnable {
        override fun run() {
            val overdue = System.currentTimeMillis() > prepEndsAtMillis
            publishNotification(shortId, overdue)
            handler.postDelayed(this, TICK_INTERVAL_MILLIS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AlarmChannels.ensureChannels(this)
        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        // A generated 180 ms two-tone tick ships with the app; the real Undal
        // sound is reserved for new orders so the kitchen can tell them apart
        // with the phone in a pocket.
        tickSoundId = soundPool?.load(this, R.raw.undal_prep_tick, 1) ?: 0
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START || action == null) {
            val id = intent?.getStringExtra(EXTRA_ORDER_ID).orEmpty()
            val endsAt = intent?.getLongExtra(EXTRA_PREP_ENDS_AT, 0L) ?: 0L
            if (id.isNotBlank() && endsAt > 0L) {
                orderId = id
                shortId = intent?.getStringExtra(EXTRA_SHORT_ID).orEmpty()
                prepEndsAtMillis = endsAt
            }
        }

        // The promise is kept before this call's meaning is acted on. A stop, a
        // payload with no order in it and an action this service does not know
        // all reach `stopSelf()` from here, and every one of them would otherwise
        // be a `startForegroundService()` that never called `startForeground()` —
        // a crash from the main thread a few seconds later, with nothing in the
        // log to say which order caused it.
        UndalForeground.enter(this, AlarmChannels.PREP_TIMER_NOTIFICATION_ID, timerNotification())

        return when {
            action == ACTION_STOP -> {
                stopTimer()
                START_NOT_STICKY
            }
            orderId.isBlank() || prepEndsAtMillis <= 0L -> {
                stopTimer()
                START_NOT_STICKY
            }
            else -> {
                startTimer()
                START_STICKY
            }
        }
    }

    /** The countdown as it stands right now, overdue or not. */
    private fun timerNotification(): Notification =
        buildNotification(orderId, shortId, overdue = prepEndsAtMillis < System.currentTimeMillis())

    private fun startTimer() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun buildNotification(id: String, short: String, overdue: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            id.hashCode() and 0x7FFFFFFF,
            MainActivity.intentFor(this, "vendor/order/$id"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val readyIntent = PendingIntent.getActivity(
            this,
            (id + "ready").hashCode() and 0x7FFFFFFF,
            MainActivity.intentFor(this, "vendor/order/$id?sheet=ready"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (overdue) "Order $short is over time" else "Cooking order $short"
        val text = if (overdue) {
            "The promised time has passed — tell the customer what is happening."
        } else {
            "Ready in"
        }

        return NotificationCompat.Builder(this, AlarmChannels.VENDOR_GENERAL)
            .setSmallIcon(R.drawable.ic_undal_mark)
            .setContentTitle(title)
            .setContentText(text)
            .setColor(if (overdue) 0xFFB3261E.toInt() else 0xFF0B5A2B.toInt())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(prepEndsAtMillis)
            .setUsesChronometer(!overdue)
            .setChronometerCountDown(!overdue)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_undal_mark, "It is ready", readyIntent)
            .build()
    }

    private fun publishNotification(short: String, overdue: Boolean) {
        val notification = buildNotification(orderId, short, overdue)
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(AlarmChannels.PREP_TIMER_NOTIFICATION_ID, notification)
        }
        if (!overdue && tickSoundId != 0) {
            soundPool?.play(tickSoundId, TICK_VOLUME, TICK_VOLUME, 1, 0, 1f)
        }
    }

    private fun stopTimer() {
        handler.removeCallbacks(ticker)
        UndalForeground.leave(this, AlarmChannels.PREP_TIMER_NOTIFICATION_ID)
        stopSelf()
    }

    /**
     * The same cleanup a stop intent asks for, because the honest way to stop
     * this service is `Context.stopService`, which never reaches `onStartCommand`
     * at all (see [UndalForeground.stop]).
     */
    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        UndalForeground.leave(this, AlarmChannels.PREP_TIMER_NOTIFICATION_ID)
        runCatching { soundPool?.release() }
        soundPool = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.undal.syl.app.action.START_PREP_TIMER"
        const val ACTION_STOP = "com.undal.syl.app.action.STOP_PREP_TIMER"
        const val EXTRA_ORDER_ID = "orderId"
        const val EXTRA_SHORT_ID = "shortId"
        const val EXTRA_PREP_ENDS_AT = "prepEndsAt"

        private const val TICK_INTERVAL_MILLIS = 10_000L
        private const val TICK_VOLUME = 0.65f

        fun start(context: Context, orderId: String, shortId: String, prepEndsAtMillis: Long) {
            val intent = Intent(context, PrepTimerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ORDER_ID, orderId)
                putExtra(EXTRA_SHORT_ID, shortId)
                putExtra(EXTRA_PREP_ENDS_AT, prepEndsAtMillis)
            }
            UndalForeground.start(context, intent)
        }

        /**
         * Stops the countdown. A plain `stopService`: this used to ask for the
         * foreground so that the service could immediately give it back, which is
         * the one shape Android answers with a dead process.
         */
        fun stop(context: Context) {
            UndalForeground.stop(context, PrepTimerService::class.java)
        }

        /** Convenience for callers that hold an ISO timestamp from Firestore. */
        fun startFromIso(context: Context, orderId: String, shortId: String, prepEndsAtIso: String?) {
            val instant = prepEndsAtIso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return
            start(context, orderId, shortId, instant.toEpochMilli())
        }

        fun labelFor(prepEndsAtMillis: Long): String =
            TimeDhaka.countdown(Instant.ofEpochMilli(prepEndsAtMillis))
    }
}
