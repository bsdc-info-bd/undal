package com.undal.syl.app.core.background

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.undal.syl.app.MainActivity
import com.undal.syl.app.R
import com.undal.syl.app.core.fcm.AlarmChannels

/**
 * The watchdog firing: put the kitchen back together (PART I-5 · P-019).
 *
 * This receiver is the only part of the alerting chain that runs after the app
 * has been killed, because the system starts the process to deliver it. What it
 * does is deliberately small and idempotent:
 *
 *   · if this is not a kitchen phone any more (signed out, or a customer
 *     account) it cancels itself and stops — a watchdog on a customer's phone
 *     would be a battery complaint with no upside;
 *   · it recreates the notification channels, in case the ROM dropped them;
 *   · it restarts the presence heartbeat and the live order watcher, both of
 *     which are foreground services that re-establish themselves from scratch;
 *   · it arms the next check, so the chain continues even if this one is the
 *     only delivery that ever gets through.
 *
 * If Android refuses the foreground-service start — the documented behaviour on
 * some manufacturers' ROMs with a locked screen and battery saving on — it posts
 * a notification instead. The one outcome it never produces is silence: an owner
 * whose phone cannot ring should find out from their own phone, not from a
 * customer calling to ask where their order is.
 */
class AlertWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_WATCHDOG) return
        val app = context.applicationContext

        if (!AlertWatchdog.isKitchenDevice(app)) {
            AlertWatchdog.cancel(app)
            return
        }

        AlarmChannels.ensureChannels(app)
        // Both calls report whether Android allowed them; wrapping them in one
        // `runCatching` hid that, because neither throws any more. A watchdog that
        // cannot tell the difference between "revived" and "refused" is a
        // watchdog that reports a green tick over a silent kitchen.
        val heartbeat = runCatching { PresenceHeartbeat.startService(app) }.getOrDefault(false)
        val watcher = runCatching { KitchenWatchService.start(app) }.getOrDefault(false)

        if (!heartbeat || !watcher) {
            Log.w(TAG, "watchdog was refused: heartbeat=$heartbeat watcher=$watcher")
            warnOwner(app)
        } else {
            Log.i(TAG, "watchdog revived the kitchen: heartbeat and order watcher restarted")
        }

        // The chain has to continue whether or not this pass worked: a phone that
        // could not start a service now has another chance at the next alarm.
        AlertWatchdog.arm(app)
    }

    /** A visible, actionable notice when the phone could not revive itself. */
    private fun warnOwner(context: Context) {
        val open = PendingIntent.getActivity(
            context,
            0,
            MainActivity.intentFor(context, "vendor/dashboard"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, AlarmChannels.VENDOR_GENERAL)
            .setSmallIcon(R.drawable.ic_undal_mark)
            .setContentTitle("Undal could not restart its alerts")
            .setContentText("Android blocked the restart. Tap to reopen the kitchen console.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(WATCHDOG_WARNING_ID, notification)
        }
    }

    companion object {
        private const val TAG = "UndalWatchdog"

        const val ACTION_WATCHDOG = "com.undal.syl.app.action.ALERT_WATCHDOG"

        /** A separate id, so it can never replace a ringing order. */
        private const val WATCHDOG_WARNING_ID = 9110
    }
}
