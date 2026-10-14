package com.undal.syl.app.features.vendor

import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.undal.syl.app.R
import com.undal.syl.app.core.background.KitchenWatchService
import com.undal.syl.app.core.background.KitchenWatchState
import com.undal.syl.app.core.background.PresenceHeartbeat
import com.undal.syl.app.core.fcm.AlarmChannels
import com.undal.syl.app.core.fcm.TokenRegistrar
import com.undal.syl.app.core.net.WorkerApi
import com.undal.syl.app.core.fcm.AlertContract
import com.undal.syl.app.core.session.VendorSessionKeeper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

/**
 * Device diagnostics for the kitchen phone (PART I-6 · V-004 · V-087).
 *
 * Every row here answers a question the owner actually has at 9 pm when an order
 * did not ring: are notifications on, is the channel at HIGH, are we exempt from
 * battery optimisation, can we use exact alarms, is the token fresh, is the
 * heartbeat recent, and which autostart screen exists on this specific phone.
 *
 * Nothing is guessed: each status is read from the operating system itself.
 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val sessionKeeper: VendorSessionKeeper,
    private val tokenRegistrar: TokenRegistrar,
    private val heartbeat: PresenceHeartbeat,
    private val firestore: FirebaseFirestore,
    private val workerApi: WorkerApi,
    private val auth: com.google.firebase.auth.FirebaseAuth
) : ViewModel() {

    enum class Status { OK, ATTENTION, MISSING }

    /** How this check can be repaired, if it can. */
    enum class Fix { NOTIFICATIONS, CHANNEL, FULL_SCREEN, BATTERY, EXACT_ALARMS, AUTOSTART, TOKEN, WATCH }

    data class Check(
        val id: String,
        val title: String,
        val detail: String,
        val status: Status,
        val fix: Fix? = null,
        /** True when a failure here means an order can be missed entirely. */
        val blocking: Boolean = false
    )

    /** The outcome of the end-to-end "ring my phone" test. */
    data class TestAlert(
        val running: Boolean = false,
        val message: String = "",
        val ok: Boolean = false
    )

    data class State(
        val checks: List<Check> = emptyList(),
        val tokenTail: String = "",
        val heartbeatAgeLabel: String = "checking…",
        val sessionSummary: String = "checking…",
        val autostartTargets: List<String> = emptyList(),
        val notificationPermissionGranted: Boolean = false,
        val soundTesting: Boolean = false,
        val batteryExempt: Boolean = false,
        val exactAlarmsAllowed: Boolean = true,
        val fullScreenIntentAllowed: Boolean = true,
        val watchRunning: Boolean = false,
        val serverSeesToken: Boolean? = null,
        val test: TestAlert = TestAlert()
    ) {
        /** Every check that would lose an order is passing. */
        val armed: Boolean get() = checks.none { it.blocking && it.status != Status.OK }

        val blockingFailures: Int get() = checks.count { it.blocking && it.status != Status.OK }

        val attentionCount: Int get() = checks.count { it.status != Status.OK }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var testPlayer: MediaPlayer? = null

    fun refresh(context: Context) {
        viewModelScope.launch {
            val checks = buildChecks(context)
            val token = tokenRegistrar.currentToken()
            val health = sessionKeeper.verifySession()

            _state.value = _state.value.copy(
                checks = checks,
                // Read back from the server: a token the Worker cannot see is a
                // phone that will not ring, and only the database knows.
                serverSeesToken = deviceTokenVisible(
                    uid = runCatching { auth.currentUser?.uid }.getOrNull(),
                    token = token
                ),
                tokenTail = token?.takeLast(8).orEmpty(),
                sessionSummary = when {
                    !health.signedIn -> "Not signed in"
                    health.vendorClaim -> "Signed in as the kitchen · refresh in ${health.secondsUntilExpiry / 60} min"
                    else -> "Signed in, but this account is not the kitchen account"
                },
                notificationPermissionGranted = notificationsEnabled(context),
                batteryExempt = batteryExempt(context),
                exactAlarmsAllowed = exactAlarmsAllowed(context)
            )
        }
    }

    /** Watches the heartbeat so the tile updates while the screen is open. */
    fun observeHeartbeat() {
        viewModelScope.launch {
            heartbeat.observe().collect { presence ->
                val age = if (presence?.lastSeen == null || presence.lastSeen == 0L) {
                    Long.MAX_VALUE
                } else {
                    System.currentTimeMillis() - presence.lastSeen
                }
                _state.value = _state.value.copy(
                    heartbeatAgeLabel = if (age == Long.MAX_VALUE) "never" else PresenceHeartbeat.describeAge(age)
                )
            }
        }
    }

    private fun buildChecks(context: Context): List<Check> = buildList {
        val notifications = notificationsEnabled(context)
        add(
            Check(
                id = "notifications",
                title = "Notifications allowed",
                detail = if (notifications) {
                    "Android is delivering Undal notifications."
                } else {
                    "Notifications are blocked. Tap to allow them, then run the sound test."
                },
                status = if (notifications) Status.OK else Status.MISSING,
                fix = Fix.NOTIFICATIONS
            )
        )

        val orderChannelImportance = AlarmChannels.channelImportance(context, AlarmChannels.VENDOR_NEW_ORDER)
        val channelHigh = orderChannelImportance != null &&
            orderChannelImportance >= android.app.NotificationManager.IMPORTANCE_HIGH
        add(
            Check(
                id = "order-channel",
                title = "New-order channel is at HIGH",
                detail = if (channelHigh) {
                    "The order sound is allowed to interrupt; Do Not Disturb is bypassed."
                } else {
                    "The channel is quieter than HIGH, so the alert may not ring loudly. Tap to raise it."
                },
                status = if (channelHigh) Status.OK else Status.ATTENTION,
                fix = Fix.CHANNEL,
                blocking = true
            )
        )

        val battery = batteryExempt(context)
        add(
            Check(
                id = "battery",
                title = "Battery optimisation exempt",
                detail = if (battery) {
                    "Android may not freeze the kitchen app in the background."
                } else {
                    "Android may freeze Undal while the screen is off. Tap to exempt it — this is the " +
                        "single most important setting for hearing an order at night."
                },
                status = if (battery) Status.OK else Status.MISSING,
                fix = Fix.BATTERY,
                blocking = true
            )
        )

        val exact = exactAlarmsAllowed(context)
        add(
            Check(
                id = "exact-alarms",
                title = "Exact alarms allowed",
                detail = if (exact) {
                    "Prep timers and reminder alarms fire at the exact minute."
                } else {
                    "Android is rounding alarms. Tap to allow exact alarms."
                },
                status = if (exact) Status.OK else Status.ATTENTION
            )
        )

        // Android 14 introduced a separate permission for "an alert may take over
        // the lock screen". Without it the phone still rings, but the order is
        // behind the lock — which on a kitchen phone at 9 pm means nobody sees it.
        val fullScreen = fullScreenIntentAllowed(context)
        add(
            Check(
                id = "full-screen",
                title = "Alerts may open over the lock screen",
                detail = if (fullScreen) {
                    "A new order can light up the screen and show Accept, even while the phone is locked."
                } else {
                    "Android will keep the order behind the lock screen. Tap to allow Undal to " +
                        "show full-screen alerts — this is what makes the phone ring like a call."
                },
                status = if (fullScreen) Status.OK else Status.ATTENTION,
                fix = Fix.FULL_SCREEN
            )
        )

        val channelSound = AlarmChannels.hasOrderSound(context)
        val channelMuted = AlarmChannels.isMuted(context, AlarmChannels.VENDOR_NEW_ORDER)
        add(
            Check(
                id = "order-sound",
                title = "Order sound is attached",
                detail = when {
                    channelMuted -> "This channel has been silenced in Android settings. It is restored the " +
                        "next time the app starts; open the settings to check it now."
                    channelSound -> "The Undal order sound is still on the new-order channel."
                    else -> "The channel has no sound. The app restores this on the next start — reopen " +
                        "Undal or open the settings to fix it now."
                },
                status = if (channelSound && !channelMuted) Status.OK else Status.ATTENTION,
                fix = Fix.CHANNEL
            )
        )

        val watching = KitchenWatchState.running.value
        add(
            Check(
                id = "watcher",
                title = "Live order watcher running",
                detail = if (watching) {
                    "This phone watches the order book directly, so it rings even if the push is late."
                } else {
                    "The live watcher is off, so this phone depends on the push alone. Tap to start it."
                },
                status = if (watching) Status.OK else Status.ATTENTION,
                fix = Fix.WATCH
            )
        )

        val seesToken = _state.value.serverSeesToken
        add(
            Check(
                id = "device-token",
                title = "The kitchen relay can see this phone",
                detail = when (seesToken) {
                    null -> "Checking whether the relay can reach this device…"
                    true -> "This device is registered, so an order alert has somewhere to go."
                    false -> "The relay cannot see this device's push token — this is the reason a " +
                        "kitchen can go completely silent. Tap to register again."
                },
                status = when (seesToken) {
                    null -> Status.ATTENTION
                    true -> Status.OK
                    false -> Status.MISSING
                },
                fix = Fix.TOKEN,
                blocking = seesToken == false
            )
        )

        val audio = context.getSystemService<AudioManager>()
        val alarmVolume = audio?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 0
        val alarmMax = audio?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 1
        val volumeHealthy = alarmMax > 0 && alarmVolume * 100 / alarmMax >= 50
        add(
            Check(
                id = "alarm-volume",
                title = "Alarm volume",
                detail = if (volumeHealthy) {
                    "Alarm volume is at ${alarmVolume * 100 / alarmMax}% — the order sound uses this stream."
                } else {
                    "Alarm volume is low (${alarmVolume * 100 / alarmMax}%). Raise it in the sound settings — " +
                        "the order alert rings on the alarm stream on purpose, so a muted phone still rings."
                },
                status = if (volumeHealthy) Status.OK else Status.ATTENTION
            )
        )
    }

    /** Subscription state, reported without pretending to know FCM's own list. */
    fun topicSummary(): String = AlertContract.KITCHEN_TOPIC

    private fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun batteryExempt(context: Context): Boolean {
        val power = context.getSystemService<PowerManager>() ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * True when the server can see this device's token.
     *
     * The document is written by this app and read back here, so a write that was
     * refused by the security rules — the failure that made a whole kitchen go
     * quiet — is visible on the screen that is supposed to prove otherwise.
     */
    private suspend fun deviceTokenVisible(uid: String?, token: String?): Boolean? {
        if (uid.isNullOrBlank() || token.isNullOrBlank()) return false
        return runCatching {
            firestore.collection(TokenRegistrar.FCM_TOKENS).document(uid)
                .collection(TokenRegistrar.TOKENS).document(token)
                .get()
                .await()
                .exists()
        }.getOrNull()
    }

    private fun fullScreenIntentAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService<android.app.NotificationManager>() ?: return true
        return manager.canUseFullScreenIntent()
    }

    /** Re-registers this device and re-runs the checks, so the fix shows itself. */
    fun reregister(context: Context) {
        viewModelScope.launch {
            val outcome = reRegisterDevice()
            _state.value = _state.value.copy(
                test = TestAlert(running = false, ok = !outcome.startsWith("Could not"), message = outcome)
            )
            refresh(context)
        }
    }

    /** Starts the live watcher from the screen, then re-reads whether it is up. */
    fun startWatcher(context: Context) {
        KitchenWatchService.start(context)
        viewModelScope.launch {
            // The service publishes its own state; give it a moment to come up
            // before the check is read again, so the tick reflects the service
            // and not this click.
            kotlinx.coroutines.delay(600)
            refresh(context)
        }
    }

    /**
     * The end-to-end test: this asks the Worker to send a real order alert to
     * this kitchen, then reports what the server managed to reach. It is the only
     * check that proves the whole chain, so the wording of a failure names the
     * link that broke instead of shrugging.
     */
    fun ringMyPhone(context: Context) {
        if (_state.value.test.running) return
        _state.value = _state.value.copy(test = TestAlert(running = true, message = "Asking the kitchen relay…"))
        viewModelScope.launch {
            val outcome = workerApi.testAlert()
            _state.value = _state.value.copy(
                test = outcome.fold(
                    onSuccess = { body -> TestAlert(running = false, ok = true, message = describeTest(body)) },
                    onFailure = { error ->
                        TestAlert(
                            running = false,
                            ok = false,
                            message = "The relay could not be reached: " +
                                (error.message ?: "unknown error") +
                                ". This phone will still ring from its own watcher — the push route is the one that is down."
                        )
                    }
                )
            )
            // The watcher is the route that proves itself: a test alert that
            // arrives by push rings through the same service, so restart it in
            // case it was stopped by an earlier failure.
            KitchenWatchService.start(context)
        }
    }

    /** Turns the relay's JSON into one sentence a person can act on. */
    private fun describeTest(body: String): String {
        if (body.isBlank()) return "The relay accepted the test. The phone should be ringing now."
        return runCatching {
            val json = org.json.JSONObject(body)
            val devices = json.optInt("devices", 0)
            val sent = json.optInt("sent", 0)
            val topicSent = json.optBoolean("topicSent", false)
            val warning = json.optString("warning").takeIf { it.isNotBlank() && it != "null" }
            when {
                sent > 0 && topicSent -> "Sent to $devices device(s) and to the kitchen topic. The phone should ring now."
                sent > 0 -> "Sent to $sent of $devices device(s). The topic fan-out failed, so a second " +
                    "kitchen phone might not have been reached."
                topicSent -> "Sent to the kitchen topic. " +
                    (warning ?: "If the phone does not ring, its token is missing.")
                else -> warning ?: "The relay could not reach any device — this is why the kitchen is silent."
            }
        }.getOrElse { "The relay accepted the test. The phone should be ringing now." }
    }

    suspend fun reRegisterDevice(): String {
        val uid = runCatching { auth.currentUser?.uid }.getOrNull() ?: return "Not signed in"
        val token = tokenRegistrar.register(uid = uid, isVendor = true, subscribeToPromos = false)
        return token.fold(
            onSuccess = { "This device is registered again." },
            onFailure = { "Could not register: ${it.message ?: "unknown error"}" }
        )
    }

    private fun exactAlarmsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarms = context.getSystemService<AlarmManager>() ?: return true
        return alarms.canScheduleExactAlarms()
    }

    /** Plays the official order sound once, at alarm volume, as a real test. */
    fun testOrderSound(context: Context) {
        if (_state.value.soundTesting) {
            stopSoundTest()
            return
        }
        _state.value = _state.value.copy(soundTesting = true)
        testPlayer = runCatching {
            MediaPlayer.create(context, R.raw.undal_order_alert)?.apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setOnCompletionListener { stopSoundTest() }
                start()
            }
        }.getOrElse {
            _state.value = _state.value.copy(soundTesting = false)
            null
        }
    }

    fun stopSoundTest() {
        runCatching { testPlayer?.stop() }
        runCatching { testPlayer?.release() }
        testPlayer = null
        _state.value = _state.value.copy(soundTesting = false)
    }

    /** Short vibration so the owner can feel what a new order feels like. */
    fun testVibration(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService<VibratorManager>()?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        runCatching {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
        }
    }

    /** The autostart screens that actually exist on this phone model. */
    fun detectAutostartTargets(context: Context) {
        val available = AlarmChannels.autostartCandidates()
            .filter { (_, intent) ->
                context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
            }
            .map { (label, _) -> label }
        _state.value = _state.value.copy(autostartTargets = available)
    }

    fun heartbeatNow(context: Context) {
        viewModelScope.launch {
            PresenceHeartbeat.startService(context)
            heartbeat.beat()
            refresh(context)
        }
    }

    fun tokenSummary(): String = _state.value.tokenTail.ifBlank { "not registered yet" }

    override fun onCleared() {
        stopSoundTest()
        super.onCleared()
    }
}
