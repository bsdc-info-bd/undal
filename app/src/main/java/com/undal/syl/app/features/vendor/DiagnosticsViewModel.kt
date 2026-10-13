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
import com.google.firebase.messaging.FirebaseMessaging
import com.undal.syl.app.R
import com.undal.syl.app.core.background.PresenceHeartbeat
import com.undal.syl.app.core.fcm.AlarmChannels
import com.undal.syl.app.core.fcm.TokenRegistrar
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
    private val heartbeat: PresenceHeartbeat
) : ViewModel() {

    enum class Status { OK, ATTENTION, MISSING }

    data class Check(
        val id: String,
        val title: String,
        val detail: String,
        val status: Status
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
        val exactAlarmsAllowed: Boolean = true
    )

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
                status = if (notifications) Status.OK else Status.MISSING
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
                status = if (channelHigh) Status.OK else Status.ATTENTION
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
                status = if (battery) Status.OK else Status.MISSING
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

    private fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun batteryExempt(context: Context): Boolean {
        val power = context.getSystemService<PowerManager>() ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
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
