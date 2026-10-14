package com.undal.syl.app.core.background

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.undal.syl.app.R
import com.undal.syl.app.core.fcm.AlarmChannels
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
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
        if (intent?.action == ACTION_STOP) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this,
            AlarmChannels.HEARTBEAT_NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )

        if (loop == null) {
            loop = scope.launch {
                val reference = FirebaseDatabase.getInstance().getReference("presence").child("vendor")
                while (isActive) {
                    runCatching {
                        reference.setValue(
                            mapOf(
                                "lastSeen" to ServerValue.TIMESTAMP,
                                "online" to true,
                                "device" to "vendor-app"
                            )
                        ).await()
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

    private fun stopForegroundAndSelf() {
        scope.launch {
            runCatching {
                FirebaseDatabase.getInstance().getReference("presence").child("vendor")
                    .updateChildren(
                        mapOf(
                            "lastSeen" to ServerValue.TIMESTAMP,
                            "online" to false
                        )
                    )
                    .await()
            }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(AlarmChannels.HEARTBEAT_NOTIFICATION_ID)
        stopSelf()
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
                .setValue(
                    mapOf(
                        "lastSeen" to ServerValue.TIMESTAMP,
                        "online" to true,
                        "device" to "vendor-app"
                    )
                )
                .await()
        }
    }

    override fun onDestroy() {
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
