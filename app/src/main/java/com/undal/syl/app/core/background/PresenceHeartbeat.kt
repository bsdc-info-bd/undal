package com.undal.syl.app.core.background

import android.content.Context
import android.content.Intent
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The kitchen's heartbeat (PART I-4 · V-006 · V-078).
 *
 * `presence/vendor.lastSeen` is the single number the Worker's five-minute cron
 * trusts. If it goes stale for ten minutes while the store is open, the Worker
 * pauses the store and pushes the owner a notice — and brings it back, with a
 * second notice, the moment the phone beats again. Ten minutes is the rule the
 * owner asked for and the same number [STALE_AGE_MILLIS] reports on the
 * diagnostics screen: one outage, described the same way on both sides.
 *
 * Four writers keep that number fresh, in order of reliability:
 *   1. the foreground service while the kitchen console is open — a beat every
 *      five minutes, which is what keeps the store open on a normal day,
 *   2. the alert watchdog, which restarts that service when Android has killed
 *      the app (a swipe, a battery cleaner) — see [AlertWatchdog],
 *   3. a fifteen-minute WorkManager job, which survives a reboot: it is slower
 *      than the rule by design (WorkManager's floor is fifteen minutes) and it is
 *      the writer of last resort, there to make the auto-offline record honest
 *      about a phone that is simply off,
 *   4. an explicit write whenever the kitchen app comes to the foreground.
 */
@Singleton
class PresenceHeartbeat @Inject constructor(
    private val database: FirebaseDatabase
) {

    data class Presence(
        val lastSeen: Long,
        val online: Boolean,
        val device: String
    )

    private val reference get() = database.getReference("presence").child("vendor")

    /** Writes a server-timestamped heartbeat; the clock used is Google's, not the phone's. */
    suspend fun beat(): Result<Unit> = runCatching {
        reference.setValue(presencePayload(online = true)).await()
        Unit
    }

    /**
     * Says the kitchen has stopped beating.
     *
     * All three children go every time, not just the one that changed — see
     * [presencePayload], because the database rules require all three and a
     * partial write to a node that does not exist yet is refused outright.
     */
    suspend fun markOffline(): Result<Unit> = runCatching {
        reference.updateChildren(presencePayload(online = false)).await()
        Unit
    }

    fun observe(): Flow<Presence?> = callbackFlow {
        val listener = object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val lastSeen = snapshot.child("lastSeen").getValue(Long::class.java) ?: 0L
                val online = snapshot.child("online").getValue(Boolean::class.java) ?: false
                val device = snapshot.child("device").getValue(String::class.java).orEmpty()
                trySend(Presence(lastSeen = lastSeen, online = online, device = device))
            }

            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                trySend(null)
            }
        }
        reference.addValueEventListener(listener)
        awaitClose { reference.removeEventListener(listener) }
    }

    companion object {
        /** Minimum age the diagnostics screen reports as healthy. */
        const val HEALTHY_AGE_MILLIS = 5 * 60 * 1000L

        /** What the Realtime Database calls a beat written by this app. */
        const val DEVICE_LABEL = "vendor-app"

        /**
         * The one shape `presence/vendor` is ever written in.
         *
         * `firebase/database.rules.json` validates that node with
         * `newData.hasChildren(['lastSeen', 'online', 'device'])`. An
         * `updateChildren` that carries only `lastSeen` and `online` is therefore
         * refused whenever the node does not already hold a `device` — which is
         * every first write after a reinstall, and every write the Worker's own
         * pause made to a node it later cleaned. A refused heartbeat is invisible:
         * the phone believes it is beating, the store goes offline ten minutes
         * later, and nobody can say why. One payload, three children, always.
         */
        fun presencePayload(online: Boolean): Map<String, Any> = mapOf(
            "lastSeen" to ServerValue.TIMESTAMP,
            "online" to online,
            "device" to DEVICE_LABEL
        )

        /**
         * The age at which the kitchen counts as gone.
         *
         * Must match `STALE_AFTER_MS` in `worker/src/crons/presence.ts`: the
         * Worker is what actually pauses the store, and a phone that believed a
         * different number would either worry early or reassure late.
         */
        const val STALE_AGE_MILLIS = 10 * 60 * 1000L

        fun serviceIntent(context: Context): Intent =
            Intent(context, PresenceHeartbeatService::class.java)

        /** Whether Android allowed the heartbeat's foreground service to start. */
        fun startService(context: Context): Boolean {
            val started = UndalForeground.start(context, serviceIntent(context))
            // A kitchen that is beating is also a kitchen that must come back by
            // itself: the two promises are armed and cancelled together.
            AlertWatchdog.arm(context)
            return started
        }

        fun stopService(context: Context) {
            UndalForeground.stop(context, serviceIntent(context))
        }

        fun describeAge(ageMillis: Long): String = when {
            ageMillis <= 0L -> "never"
            ageMillis < 60_000L -> "${ageMillis / 1000} s ago"
            ageMillis < 3_600_000L -> "${ageMillis / 60_000} min ago"
            else -> "${ageMillis / 3_600_000L} h ago"
        }
    }
}
