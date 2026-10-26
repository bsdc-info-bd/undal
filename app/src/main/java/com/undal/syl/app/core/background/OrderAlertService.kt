package com.undal.syl.app.core.background

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
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
import androidx.core.content.getSystemService
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.undal.syl.app.MainActivity
import com.undal.syl.app.R
import com.undal.syl.app.core.fcm.AlertDeliveryJournal
import com.undal.syl.app.core.fcm.AlarmChannels
import com.undal.syl.app.core.fcm.AlertVolume
import com.undal.syl.app.core.util.OrderStatus
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

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
 * The sound has to be impossible to lose
 * --------------------------------------
 * Three things can silence a phone that is otherwise perfectly armed, and each
 * one is closed here rather than assumed away:
 *
 *   · **the alarm stream at zero** — the stream both the channel and the player
 *     use. [AlertVolume.arm] raises it for the duration of the ring and puts the
 *     owner's own value back when the ring ends;
 *   · **attributes set after `prepare()`** — the platform then resets the player
 *     and `start()` throws. The player is built attributes-first for that reason;
 *   · **a resource that will not open** — the tone is then read again from a copy
 *     in the app's own cache, and if even that fails the phone's own alarm tone
 *     rings in its place. A fallback ring is worse than the Undal sound and far
 *     better than a missed order;
 *   · **a player that stops on its own** — an audio server that restarts, a ROM
 *     that reclaims the session, a decoder that gives up on the file. Nothing
 *     tells the service; the notification stays and the room goes quiet. So the
 *     ring is *watched*: every ten seconds while an order waits, [keepRinging]
 *     asks whether anything is actually making noise and rebuilds it if not.
 *     A phone that is visibly alerting and audibly silent is the one state this
 *     service must never be in.
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

    /** The phone's own alarm tone. Only ever used if the bundled sound will not decode. */
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var liveReference: com.google.firebase.database.DatabaseReference? = null

    /**
     * How many times the bundled tone has failed since this ring started, and
     * when the fallback tone was last tried.
     *
     * Together they make a bad decoder quiet rather than busy: the bundled sound
     * gets [MAX_BUNDLED_ATTEMPTS] goes, then the phone's own alarm tone takes
     * over — and *that* is retried no faster than once per watchdog tick. A
     * rebuild loop on the main thread would turn "no order sound" into "no app".
     */
    private var soundFailures = 0
    private var lastFallbackAtMillis = 0L
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

    /**
     * A `Ringtone` plays once and stops; an unanswered order must not. This
     * re-plays the phone's own alarm tone for as long as anything is waiting.
     */
    private val replayRinger: Runnable = object : Runnable {
        override fun run() {
        if (waiting.isEmpty()) return
        val tone = ringtone ?: return
        runCatching { tone.stop() }
        runCatching { tone.play() }
        handler.postDelayed(this, RINGER_REPLAY_MILLIS)
        }
    }

    /**
     * The ring's own watchdog.
     *
     * `MediaPlayer` can stop without telling anybody: an audio server restart, a
     * decoder that errors out, a ROM that reclaims the session, a file whose
     * descriptor is withdrawn. The order is still unanswered, the notification is
     * still on the lock screen — and the kitchen is silent, which is worse than
     * either failure on its own, because it *looks* handled.
     *
     * Ten seconds, because that is the point where a silent phone is still a
     * phone somebody will pick up. The check costs nothing: it reads two fields.
     */
    private val soundWatchdog: Runnable = object : Runnable {
        override fun run() {
            if (waiting.isEmpty()) return
            keepRinging()
            handler.postDelayed(this, SOUND_CHECK_MILLIS)
        }
    }

    /**
     * Makes sure *something* is ringing, without restarting a healthy tone.
     *
     * Called when the head of the queue changes and every ten seconds by
     * [soundWatchdog]. A player that is playing, or a fallback ringtone that the
     * replay loop owns, is left completely alone: nothing here is allowed to
     * stutter the sound or it would be audible.
     */
    private fun keepRinging() {
        if (waiting.isEmpty()) return
        val playing = runCatching { player?.isPlaying == true }.getOrDefault(false)
        if (ringtone == null && !playing) {
            // Whatever was making the noise is gone. Release what is left of it
            // first — a dead player still holds an audio session and the partial
            // wake lock it took — then build the ring again from scratch.
            player?.let { dead -> runCatching { dead.release() } }
            player = null
            if (soundFailures < MAX_BUNDLED_ATTEMPTS) {
                startSound()
            } else {
                // The bundled tone is not playing on this phone tonight. Fall
                // back to the device's own alarm, and try at most once a tick.
                val now = System.currentTimeMillis()
                if (now - lastFallbackAtMillis >= SOUND_CHECK_MILLIS) {
                    lastFallbackAtMillis = now
                    playSystemRinger()
                }
            }
        }
        if ((runCatching { player?.isPlaying == true }.getOrDefault(false) || ringtone != null) && vibrator == null) startVibration()
    }

    /** Exactly one watchdog chain, however many times the ring is rebuilt. */
    private fun watchSound() {
        handler.removeCallbacks(soundWatchdog)
        handler.postDelayed(soundWatchdog, SOUND_CHECK_MILLIS)
    }

    private data class Waiting(
        val orderId: String,
        val shortId: String,
        val title: String,
        val body: String,
        val isTest: Boolean
    )

    private var liveListener: ValueEventListener? = null

    // Persist only order identifiers and display data, scoped to the signed-in user.
    // Tests are deliberately not restored after a process death.
    private fun persistQueue() {
        val records = JSONArray()
        waiting.values.filterNot { it.isTest }.forEach { order ->
            records.put(JSONObject().put("id", order.orderId).put("short", order.shortId)
                .put("title", order.title).put("body", order.body))
        }
        getSharedPreferences("order_alert_queue", MODE_PRIVATE).edit()
            .putString("uid", com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid)
            .putLong("savedAt", System.currentTimeMillis())
            .putString("orders", records.toString()).commit()
    }

    private fun restoreQueue() {
        val prefs = getSharedPreferences("order_alert_queue", MODE_PRIVATE)
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (prefs.getString("uid", null) != uid ||
            System.currentTimeMillis() - prefs.getLong("savedAt", 0) > 15 * 60_000L) return
        runCatching {
            val records = JSONArray(prefs.getString("orders", "[]"))
            for (index in 0 until records.length()) {
                val row = records.getJSONObject(index)
                beginAlert(row.getString("id"), row.getString("short"),
                    row.getString("title"), row.getString("body"), false)
            }
        }.onFailure { Log.w(TAG, "Could not restore alert queue", it) }
    }

    /**
     * The alert is playing and the task is being taken off the screen. The sound
     * keeps running (the service is not tied to the task), but if this phone's
     * ROM kills the process anyway, the watchdog revives the watcher and the
     * heartbeat — and the persisted queue means the ring resumes where it was.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        AlertWatchdog.arm(this, AlertWatchdog.SWIPE_GRACE_MILLIS)
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AlarmChannels.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A restart with no intent is the system bringing the service back for the
        // orders it was ringing for, so the queue is read before the foreground is
        // taken — the notice that goes up has to be the true one.
        if (intent == null) restoreQueue()

        // The foreground is taken before this call's meaning is acted on, every
        // time. Three of the branches below end in `stopSelf()`, and a service
        // started with `startForegroundService()` that stops without ever taking
        // the foreground is killed by the platform from the main thread — a crash
        // the caller cannot catch and the log cannot explain.
        enterForeground()
        AlertDeliveryJournal.record(this, "Alarm service started", if (intent == null) "Restored after process death" else "Foreground service active")

        if (intent == null) {
            if (waiting.isEmpty()) {
                // Nothing left to ring for, so the notice goes as well.
                stopQuietly()
                return START_NOT_STICKY
            }
            return START_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> {
                stopAlert(intent.getStringExtra(EXTRA_ORDER_ID))
                return if (waiting.isEmpty()) START_NOT_STICKY else START_STICKY
            }
            ACTION_RING, null -> {
                val orderId = intent.getStringExtra(EXTRA_ORDER_ID)
                val shortId = intent.getStringExtra(EXTRA_SHORT_ID).orEmpty()
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "New order" }
                val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
                val isTest = intent.getBooleanExtra(EXTRA_IS_TEST, false)
                if (orderId.isNullOrBlank()) {
                    stopQuietly()
                    return START_NOT_STICKY
                }
                if (waiting.isEmpty()) restoreQueue()
                beginAlert(orderId, shortId, title, body, isTest)
            }
            else -> stopQuietly()
        }
        return START_STICKY
    }

    /**
     * Stops with nothing to ring for. The notice this call has just posted goes
     * with it — a bare `stopSelf()` would leave a foreground notification on the
     * screen with no service behind it, which Android then removes on its own
     * schedule and the owner reads as an order that never arrived.
     */
    private fun stopQuietly() {
        UndalForeground.leave(this, AlarmChannels.ORDER_ALERT_NOTIFICATION_ID)
        stopSelf()
    }

    /**
     * Keeps Android's foreground promise with whatever is actually waiting: the
     * head of the queue when an order is unanswered, the plainest honest notice
     * when nothing is. It is never skipped, because the branches that have
     * nothing to ring for are exactly the ones that stop, and stopping without
     * this is what turns a bad push payload into a dead process.
     */
    private fun enterForeground() {
        val head = waiting.entries.firstOrNull()?.value
        val notification = if (head == null) idleNotification() else buildNotification(head)
        UndalForeground.enter(
            this,
            AlarmChannels.ORDER_ALERT_NOTIFICATION_ID,
            notification,
            foregroundType()
        )
    }

    /** What the notice says when this service is up with nothing to ring for. */
    private fun idleNotification(): Notification =
        NotificationCompat.Builder(this, AlarmChannels.VENDOR_GENERAL)
            .setSmallIcon(R.drawable.ic_undal_mark)
            .setContentTitle(getString(R.string.watch_title))
            .setContentText(getString(R.string.watch_body))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setShowWhen(false)
            .build()

    private fun beginAlert(orderId: String, shortId: String, title: String, body: String, isTest: Boolean) {
        // Idempotent: three routes can deliver the same order, and the re-alert
        // sweep delivers it again every minute. One entry per order, and a repeat
        // only refreshes what the notification says.
        val alreadyWaiting = waiting.containsKey(orderId)

        // Foreground first, before any of the work below: Android 14/15 kills a
        // service that plays audio before it calls startForeground, and a kill
        // here is a missed order. The very first ring has to establish it, which
        // is why this comes before the early return for a repeat.
        val ringing = Waiting(orderId, shortId, title, body, isTest)
        if (!alreadyWaiting) {
            // Newest last: `linkedMapOf` keeps arrival order, so the head stays
            // the oldest unanswered order even when a second order arrives while
            // the phone is ringing for the first.
            waiting[orderId] = ringing
        } else {
            waiting[orderId] = ringing.copy(shortId = shortId, title = title, body = body)
        }

        val foregroundOrder = waiting.entries.first().value
        UndalForeground.enter(
            this,
            AlarmChannels.ORDER_ALERT_NOTIFICATION_ID,
            buildNotification(foregroundOrder),
            foregroundType()
        )
        persistQueue()

        if (alreadyWaiting) {
            // Already ringing for this order (or for an older one): refresh the
            // text, keep the sound. Never restart the player — that is audible.
            publishNotification(foregroundOrder)
            return
        }

        // A ring that starts now is a new attempt: yesterday's dead decoder must
        // not push tonight's order straight to the fallback tone.
        soundFailures = 0
        lastFallbackAtMillis = 0L

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
        watchSound()
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
        persistQueue()
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
        publishNotification(next)
        watchOrderLive(next.orderId)
        keepRinging()
        AlertDeliveryJournal.record(this, "Alarm playback requested", "${waiting.size} order(s) queued. Player active: ${runCatching { player?.isPlaying == true }.getOrDefault(false)}; fallback selected: ${ringtone != null}")
        watchSound()
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
        if (player != null || ringtone != null) return

        // Both the notification's sound and this player use the ALARM stream, so
        // a phone whose alarm volume is at zero is a phone that rings silently.
        // Raise it for the duration of the ring, and only if it has to be raised.
        val raised = AlertVolume.arm(this)
        if (raised) {
            Log.i(TAG, "alarm volume was below the audible line and was raised for this order")
        }

        player = createOrderPlayer()
        if (player == null) {
            Log.e(TAG, "the bundled order sound would not start; using the phone's own alarm tone")
            soundFailures += 1
            lastFallbackAtMillis = System.currentTimeMillis()
            playSystemRinger()
        }
    }

    /**
     * A prepared, looping, alarm-routed player — or null, never a half-open one.
     *
     * `setAudioAttributes` is called **before** `prepare()` on purpose. The
     * platform documents that the attributes only take effect immediately when
     * they are set first; set afterwards, the player is reset underneath you and
     * `start()` throws, which is how a kitchen ends up with a notification and no
     * sound at all. That was this app's bug, so the order here is not cosmetic.
     */
    private fun buildPlayer(configure: MediaPlayer.() -> Unit): MediaPlayer? {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        var opened: MediaPlayer? = null
        return runCatching {
            MediaPlayer().apply {
                opened = this
                val self = this
                setAudioAttributes(attributes)
                // The player holds a partial wake lock for us, which is exactly
                // what is needed for the sound to survive a locked screen.
                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                configure()
                isLooping = true
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "order sound failed (what=$what extra=$extra)")
                    // A player that errored will not play again, so holding on to
                    // it would silence the ring for good — and leaking it would
                    // hold an audio session and a wake lock until the process
                    // died. Swap it out on the main thread and let the ring
                    // rebuild itself immediately rather than at the next tick.
                    val dead = self
                    handler.post {
                        if (player === dead) {
                            runCatching { dead.release() }
                            player = null
                        }
                        soundFailures += 1
                        keepRinging()
                        watchSound()
                    }
                    true
                }
                prepare()
                start()
            }
        }.onFailure { error ->
            Log.e(TAG, "the order sound would not start", error)
            runCatching { opened?.release() }
        }.getOrNull()
    }

    /**
     * Two independent ways to the same tone, in order of cost:
     *
     *   1. straight off the packaged resource, through its file descriptor;
     *   2. a copy of that resource in this app's own cache, played as a plain
     *      file — which also covers a build in which the tone was compressed,
     *      where `openRawResourceFd` hands back nothing.
     */
    private fun createOrderPlayer(): MediaPlayer? =
        buildPlayer {
            val afd = resources.openRawResourceFd(R.raw.undal_order_alert)
            if (afd != null) {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
            } else {
                setDataSource(cachedOrderSound().absolutePath)
            }
        } ?: buildPlayer { setDataSource(cachedOrderSound().absolutePath) }

    /** The packaged tone written once into this app's cache and reused. */
    private fun cachedOrderSound(): File {
        val packed = File(cacheDir, "undal_order_alert.mp3")
        if (packed.length() > 0L) return packed
        val partial = File(cacheDir, "undal_order_alert.part")
        resources.openRawResource(R.raw.undal_order_alert).use { input ->
            partial.outputStream().use { output -> input.copyTo(output) }
        }
        if (!partial.renameTo(packed)) {
            partial.copyTo(packed, overwrite = true)
            partial.delete()
        }
        return packed
    }

    /**
     * The last resort, and the reason a kitchen phone is never silent: the
     * phone's own alarm tone, re-played until the queue is empty. It is not the
     * Undal sound, but a missed order costs more than a wrong ringtone.
     */
    private fun playSystemRinger() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        if (uri == null) {
            Log.e(TAG, "this phone has no alarm or ringtone to fall back to")
            return
        }
        val tone = runCatching { RingtoneManager.getRingtone(applicationContext, uri) }.getOrNull()
        if (tone == null) {
            Log.e(TAG, "the phone's alarm tone could not be opened")
            return
        }
        tone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        ringtone = tone
        val playing = runCatching { tone.play() }.isSuccess
        if (playing) {
            handler.postDelayed(replayRinger, RINGER_REPLAY_MILLIS)
        } else {
            Log.e(TAG, "the phone's alarm tone refused to play")
            ringtone = null
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
        liveListener?.let { liveReference?.removeEventListener(it) }
        liveReference = FirebaseDatabase.getInstance().getReference("orderLive").child(orderId)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.child("status").getValue(String::class.java)
                // Capture this subscription's id. A late callback from the previous
                // head must never silence a different order.
                if (status != null && status != OrderStatus.PLACED.wire &&
                    waiting.remove(orderId) != null) {
                    if (waiting.isEmpty()) stopAlert(orderId) else ringHead()
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "orderLive listener cancelled: ${error.message}")
            }
        }
        liveListener = listener
        liveReference?.addValueEventListener(listener)
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
        AlertDeliveryJournal.record(this, "Alarm queue updated", if (orderId == null) "All alerts cleared" else "An order was answered or removed")
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

        persistQueue()
        liveListener?.let { liveReference?.removeEventListener(it) }
        liveReference = null

        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null

        handler.removeCallbacks(soundWatchdog)
        handler.removeCallbacks(replayRinger)
        runCatching { ringtone?.stop() }
        ringtone = null
        soundFailures = 0
        lastFallbackAtMillis = 0L

        // Whatever the ring had to change is put back now that nothing is ringing.
        AlertVolume.restore(this)

        runCatching { vibrator?.cancel() }
        vibrator = null

        handler.removeCallbacks(stopTestRunnable)

        OrderAlertController.stop(orderId ?: currentOrderId)
        currentOrderId = null

        UndalForeground.leave(this, AlarmChannels.ORDER_ALERT_NOTIFICATION_ID)
        stopSelf()
    }

    override fun onDestroy() {
        liveListener?.let { liveReference?.removeEventListener(it) }
        runCatching { player?.release() }
        player = null
        handler.removeCallbacks(soundWatchdog)
        handler.removeCallbacks(replayRinger)
        runCatching { ringtone?.stop() }
        ringtone = null
        AlertVolume.restore(this)
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

        /**
         * How often the ring checks that it is still audible. Short enough that a
         * phone which went quiet is ringing again before anybody notices the
         * silence; long enough that the check itself is nothing.
         */
        private const val SOUND_CHECK_MILLIS = 10_000L

        /**
         * How many times the bundled tone is rebuilt before the phone's own alarm
         * tone is used instead. Two, because the first failure is usually
         * transient (an audio server restarting) and the second is usually the
         * device telling the truth.
         */
        private const val MAX_BUNDLED_ATTEMPTS = 2

        /** How long a "ring my phone" test rings before it stops itself. */
        private const val TEST_SECONDS = 45

        /**
         * How often the phone's own alarm tone is restarted while an order waits.
         * A `Ringtone` cannot loop, so the loop is ours: twenty seconds is longer
         * than the gap between two alarm tones and short enough that a phone
         * whose fallback tone is brief still sounds like a ringing phone.
         */
        private const val RINGER_REPLAY_MILLIS = 20_000L

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
            AlertDeliveryJournal.record(context, "Ring requested", "Source: $source; diagnostic test: $isTest")
            if (orderId.isBlank()) {
                // Nothing to ring for, so nothing is started. A service asked for
                // the foreground with no order in its payload would have to take
                // it and give it straight back, which is a crash on some ROMs;
                // a bad push payload must cost a log line, not the process.
                Log.w(TAG, "a ring arrived with no order id; nothing was started")
                return
            }
            val intent = Intent(context, OrderAlertService::class.java).apply {
                action = ACTION_RING
                putExtra(EXTRA_ORDER_ID, orderId)
                putExtra(EXTRA_SHORT_ID, shortId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
                putExtra(EXTRA_IS_TEST, isTest)
            }
            if (!UndalForeground.start(context, intent)) {
                // Android may downgrade a push or deny background FGS starts.
                // Keep a visible, actionable alert rather than crashing the FCM callback.
                Log.w(TAG, "Android refused the alarm service; posting the notification instead")
                AlertDeliveryJournal.record(context, "Background start blocked", "Android denied the looping service. Notification fallback requested; open Undal and check battery/autostart settings.")
                AlarmChannels.ensureChannels(context)
                val open = PendingIntent.getActivity(context, orderId.hashCode(),
                    MainActivity.intentFor(context, "vendor/order/$orderId"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notification = NotificationCompat.Builder(context, AlarmChannels.VENDOR_NEW_ORDER)
                    .setSmallIcon(R.drawable.ic_undal_mark)
                    .setContentTitle(title).setContentText(body)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setContentIntent(open).setAutoCancel(true).build()
                runCatching { NotificationManagerCompat.from(context)
                    .notify(orderId.hashCode() and 0x7FFFFFFF, notification) }
            }
        }

        /** Silences the loop. Called when an order is answered anywhere. */
        fun silence(context: Context, orderId: String? = null) {
            // A stop is never a foreground start: `startService`, not
            // `startForegroundService`, because a service asked for the foreground
            // only to give it straight back is the shape Android answers with a
            // dead process.
            //
            // The saved queue is corrected first, and whether the service is woken
            // at all then depends on there being anything left to correct. An order
            // answered while this phone's alert service was not running is the
            // common case — the second kitchen device took it — and starting a
            // process in order to tell it to stop is a notification flash and a
            // battery wake for nothing.
            val prefs = context.getSharedPreferences("order_alert_queue", Context.MODE_PRIVATE)
            val stillWaiting = if (orderId == null) {
                prefs.edit().remove("orders").apply()
                false
            } else {
                runCatching {
                    val remaining = JSONArray()
                    val records = JSONArray(prefs.getString("orders", "[]"))
                    for (index in 0 until records.length()) {
                        val row = records.getJSONObject(index)
                        if (row.getString("id") != orderId) remaining.put(row)
                    }
                    prefs.edit().putString("orders", remaining.toString()).apply()
                    remaining.length() > 0
                }.getOrDefault(false)
            }

            if (OrderAlertController.ringing.value == null && !stillWaiting) return

            val intent = Intent(context, OrderAlertService::class.java).apply {
                action = ACTION_STOP
                if (orderId != null) putExtra(EXTRA_ORDER_ID, orderId)
            }
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "the alert could not be told to stop", it) }
        }
    }
}
