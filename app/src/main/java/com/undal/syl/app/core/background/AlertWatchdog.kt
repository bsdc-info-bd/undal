package com.undal.syl.app.core.background

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.undal.syl.app.core.fcm.PushDeviceRole

/**
 * The watchdog that brings the kitchen back after Android takes it away
 * (PART I-5 · P-019).
 *
 * A kitchen phone has one job: ring when an order arrives. It is also the thing
 * an owner swipes off the screen without thinking, the thing a cheap ROM kills
 * in the background to save 40 MB, and the thing that has been sitting on a
 * counter untouched for two days. `stopWithTask="false"` and `START_STICKY`
 * cover the polite half of Android; this covers the other half.
 *
 * The mechanism is an alarm rather than a service, because an alarm is the only
 * one of these that *survives the process being killed*: it is held by the
 * system, addressed to this package, and delivered by handing the app a fresh
 * process. When it fires, [AlertWatchdogReceiver] starts the heartbeat and the
 * live order watcher again and arms the next check.
 *
 * **Why nine minutes.** The owner's rule is that ten minutes without a heartbeat
 * pauses the store, so a watchdog that fires less often than that would be
 * watching from behind. Nine minutes also happens to be the shortest interval
 * Android will still deliver while the phone is in Doze, where a woken app is
 * allowed one precise alarm per nine minutes — asking for less would buy nothing
 * and cost battery.
 *
 * **What the owner experiences.** They swipe Undal away, put the phone down, and
 * within nine minutes the phone is armed again on its own — and because the
 * Worker undoes a pause shorter than thirty minutes, a store that blinked
 * reopens itself with a push saying so, instead of staying dark until somebody
 * opens the app. If the watchdog is switched off (a ROM, a manufacturer's
 * battery mode, a revoked exact-alarm permission) the diagnostics screen says so
 * in as many words, because a promise nobody can see is a promise nobody can
 * trust.
 */
object AlertWatchdog {

    private const val TAG = "UndalWatchdog"

    private const val PREFS = "undal_alert_watchdog"
    private const val KEY_NEXT_AT = "nextAt"
    private const val KEY_ARMED = "armed"

    /** The alarm request code; one watchdog per device, replaced not stacked. */
    private const val REQUEST_CODE = 8611

    /**
     * The heartbeat cadence of the watchdog: shorter than the ten-minute rule it
     * exists to defend, and no shorter than Doze will honour.
     */
    const val WATCHDOG_MILLIS = 9 * 60 * 1000L

    /**
     * The hurried first check. When somebody swipes Undal off the recents list
     * the process is on borrowed time, so the next look happens in two minutes
     * rather than waiting out the full cadence — and it is in the foreground
     * window, where an alarm is not being throttled at all.
     */
    const val SWIPE_GRACE_MILLIS = 2 * 60 * 1000L

    /** Arms (or re-arms) the watchdog. Idempotent: only one alarm is ever live. */
    fun arm(context: Context, delayMillis: Long = WATCHDOG_MILLIS) {
        val alarm = alarmManager(context) ?: return
        val at = System.currentTimeMillis() + delayMillis.coerceAtLeast(60_000L)
        val intent = receiverIntent(context)
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val scheduled = runCatching {
            // `setExactAndAllowWhileIdle` is the one call that both wakes a Dozing
            // phone and survives having been killed; the inexact fallback still
            // fires, just not to the minute, which is the honest second-best.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && canScheduleExact(alarm)) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                alarm.set(AlarmManager.RTC_WAKEUP, at, pending)
            }
            true
        }.getOrElse { error ->
            Log.w(TAG, "could not arm the alert watchdog", error)
            false
        }

        put(context, scheduled, at)
    }

    /** Cancels it — used when the phone is not a kitchen phone any more. */
    fun cancel(context: Context) {
        val alarm = alarmManager(context) ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            receiverIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching { alarm.cancel(pending) }
        prefs(context).edit().putBoolean(KEY_ARMED, false).putLong(KEY_NEXT_AT, 0L).apply()
    }

    /** True when an alarm is believed to be live. Read by the diagnostics screen. */
    fun isArmed(context: Context): Boolean = prefs(context).getBoolean(KEY_ARMED, false)

    /** When the next self-check is due, or 0. */
    fun nextCheckMillis(context: Context): Long = prefs(context).getLong(KEY_NEXT_AT, 0L)

    /**
     * "in 6 min" — how long the owner has before the phone looks after itself
     * again, in the same plain language the dashboard uses elsewhere.
     */
    fun nextCheckLabel(context: Context, nowMillis: Long = System.currentTimeMillis()): String {
        val next = nextCheckMillis(context)
        if (!isArmed(context) || next <= 0L) return "not armed"
        val minutes = ((next - nowMillis) / 60_000L).coerceAtLeast(0L)
        return if (minutes < 1L) "in under a minute" else "in $minutes min"
    }

    /**
     * The sentence the diagnostics screen shows. It names the reason rather than
     * showing a red dot, because the owner's next move differs: exact alarms are a
     * setting, a killed process is Android being Android, and a customer phone
     * should never be armed at all.
     */
    fun describe(context: Context): String = when {
        isArmed(context) ->
            "Armed. If Android ever kills Undal — a swipe, a battery cleaner, a low-memory " +
                "reboot — this phone rebuilds the order watcher by itself, ${nextCheckLabel(context)}."
        exactAlarmBlocked(context) ->
            "Android has not allowed exact alarms for Undal, so the self-check runs late. " +
                "Open the exact-alarms setting below and allow it."
        else ->
            "Not armed. Start the live watcher (or reopen the kitchen console) and Undal will " +
                "keep itself alive from then on."
    }

    /** Whether this device's Android is willing to deliver the alarm precisely. */
    fun exactAlarmBlocked(context: Context): Boolean {
        val alarm = alarmManager(context) ?: return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !canScheduleExact(alarm)
    }

    private fun canScheduleExact(alarm: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarm.canScheduleExactAlarms() else true

    private fun receiverIntent(context: Context): Intent =
        Intent(context, AlertWatchdogReceiver::class.java)
            .setAction(AlertWatchdogReceiver.ACTION_WATCHDOG)

    private fun alarmManager(context: Context): AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * `apply`, not `commit`: this runs on the main thread at every cold start,
     * and the values it writes are read by a later process, which `apply`
     * reaches just as surely without an fsync on the opening frame.
     */
    private fun put(context: Context, armed: Boolean, nextAt: Long) {
        prefs(context).edit().putBoolean(KEY_ARMED, armed).putLong(KEY_NEXT_AT, nextAt).apply()
    }

    /**
     * The one place that decides what "still the kitchen" means, so the receiver
     * and the boot path can never disagree.
     *
     * It reads the role the router last established for the account that is
     * signed in now — never a `Task`, because a watchdog fires in a process that
     * was started to run it and has no time to wait for a token round trip. The
     * previous version read `getIdToken(false).result`, which throws unless the
     * task has already finished; the throw was swallowed, so the answer was
     * always "not the kitchen" and the watchdog disarmed itself the first time it
     * fired. A kitchen phone that had been swiped away never came back.
     */
    fun isKitchenDevice(context: Context): Boolean = runCatching {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return false
        PushDeviceRole.isKitchen(context, uid)
    }.getOrDefault(false)

}
