package com.undal.syl.app.core.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.auth.FirebaseAuth
import com.undal.syl.app.core.fcm.AlarmChannels
import com.undal.syl.app.core.fcm.PushDeviceRole

/**
 * Android threw the process away — the kitchen must come back on its own
 * (PART I-5 · P-019).
 *
 * On boot, a package update or a timezone change this receiver:
 *   · recreates the notification channels (some ROMs drop them on update),
 *   · reschedules the fifteen-minute heartbeat fallback,
 *   · re-registers the FCM token so the phone can be reached before anyone
 *     opens the app,
 *   · brings the heartbeat foreground service back for a signed-in kitchen,
 *   · brings the live order watcher back, so a rebooted phone rings for an order
 *     that arrives before anybody touches it,
 *   · arms the alert watchdog, which is what keeps all of the above true after
 *     Android kills the app for its own reasons (see [AlertWatchdog]).
 *
 * It never touches the customer experience: if nobody is signed in, it simply
 * recreates the channels and returns.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in HANDLED_ACTIONS) return

        AlarmChannels.ensureChannels(context)

        val user = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
        // The role the router established before this phone went down, not a
        // token read: `Task.getResult()` on a fetch that has not finished throws,
        // and the throw used to be swallowed into "this is not a kitchen" — which
        // is how a rebooted vendor phone came back silent until somebody opened
        // the app and signed in again.
        val isVendor = PushDeviceRole.isKitchen(context, user?.uid)

        // The fifteen-minute heartbeat is the kitchen's writer of last resort and
        // nobody else's: a customer's phone that beat `presence/vendor` would hold
        // a closed store open, and a periodic job that only ever answers "not me"
        // is a wakeup every fifteen minutes for nothing.
        if (isVendor) {
            PresenceHeartbeatWorker.ensureScheduled(context)
        } else {
            PresenceHeartbeatWorker.cancel(context)
        }

        if (user != null) {
            TokenRefreshWorker.enqueue(context)
        }

        if (isVendor) {
            PresenceHeartbeat.startService(context)
            // Without this, a rebooted kitchen phone would only ring for an order
            // that arrived by push until somebody opened the app — and the push is
            // exactly the route that fails on the phones that need this most.
            KitchenWatchService.start(context)
            // A rebooted kitchen phone must be able to ring again immediately,
            // so the alert channel state is verified as part of the boot path.
            OrderAlertController.stop()
            // The watchdog outlives everything else here: the two services above
            // can be killed later in the day, and this is what brings them back.
            AlertWatchdog.arm(context)
        } else if (user != null) {
            // A customer device must never keep the kitchen's watcher alive.
            KitchenWatchService.stop(context)
            AlertWatchdog.cancel(context)
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED
        )
    }
}
