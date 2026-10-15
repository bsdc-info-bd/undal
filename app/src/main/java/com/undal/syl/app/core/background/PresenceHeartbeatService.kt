package com.undal.syl.app.core.background

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.undal.syl.app.R
import com.undal.syl.app.core.fcm.AlarmChannels
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Foreground heartbeat for the kitchen phone (PART I-4).
 *
 * Runs only while a vendor session is active and shows a quiet, low-importance
 * ongoing notification so the owner always knows the kitchen is "connected".
 *
 * Stopping this service does not stop the heartbeat entirely: the alert watchdog
 * brings it back within nine minutes if Android killed the app, and the
 * fifteen-minute WorkManager job keeps beating across a reboot. That chain is
 * what makes the auto-offline rule trustworthy — the Worker pauses a kitchen
 * that really has gone away, and a paused kitchen comes back by itself.
 */
class PresenceHeartbeatService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AlarmChannels.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground first, whatever this intent turns out to ask for: a service
        // that was started with `startForegroundService` and stops without ever
        // taking the foreground is killed by the platform, not by us.
        UndalForeground.enter(this, AlarmChannels.HEARTBEAT_NOTIFICATION_ID, buildNotification())

        if (intent?.action == ACTION_STOP) {
            // The offline write and the notice both happen in `onDestroy`, which
            // `stopSelf()` always reaches — so this route and a plain
            // `Context.stopService` from outside behave identically, and neither
            // can leave the Worker believing a kitchen that has gone away is still
            // beating.
            stopSelf()
            return START_NOT_STICKY
        }

        if (loop == null) {
            loop = scope.launch {
                val reference = FirebaseDatabase.getInstance().getReference("presence").child("vendor")
                while (isActive) {
                    runCatching {
                        reference.setValue(PresenceHeartbeat.presencePayload(online = true)).await()
                    }
                    delay(BEAT_INTERVAL_MILLIS)
                }
            }
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, AlarmChannels.VENDOR_GENERAL)
            .setSmallIcon(R.drawable.ic_undal_mark)
            .setContentTitle("Undal kitchen is connected")
            .setContentText("Keeping this phone reachable for new orders.")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()

    /**
     * Tells the Worker this kitchen has stopped beating, so the ten-minute rule
     * starts from now rather than from the last five-minute beat.
     *
     * It runs from [onDestroy] as well, because the honest way to stop a service
     * is `Context.stopService`, which never reaches `onStartCommand` at all — and
     * an offline write that only happened on one of the two stop routes is an
     * offline write that usually does not happen.
     */
    private fun markOffline() {
        scope.launch {
            runCatching {
                FirebaseDatabase.getInstance().getReference("presence").child("vendor")
                    .updateChildren(PresenceHeartbeat.presencePayload(online = false))
                    .await()
            }
        }
    }

    /**
     * Undal was swiped off the recents list. The heartbeat loop dies with the
     * process, and the Worker would pause the store ten minutes later — so the
     * watchdog is armed first, and a beat is written now, which keeps the store
     * open if the process survives the swipe.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        AlertWatchdog.arm(this, AlertWatchdog.SWIPE_GRACE_MILLIS)
        scope.launch { beatNow() }
        super.onTaskRemoved(rootIntent)
    }

    private suspend fun beatNow() {
        runCatching {
            FirebaseDatabase.getInstance().getReference("presence").child("vendor")
                .setValue(PresenceHeartbeat.presencePayload(online = true))
                .await()
        }
    }

    override fun onDestroy() {
        markOffline()
        UndalForeground.leave(this, AlarmChannels.HEARTBEAT_NOTIFICATION_ID)
        loop?.cancel()
        loop = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.undal.syl.app.action.STOP_HEARTBEAT"

        /** Five minutes: two whole beats inside the Worker's ten-minute window. */
        const val BEAT_INTERVAL_MILLIS = 5 * 60 * 1000L
    }
}
