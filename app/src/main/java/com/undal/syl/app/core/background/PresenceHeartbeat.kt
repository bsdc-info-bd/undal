package com.undal.syl.app.core.background

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
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
 * trusts. If it goes stale for fifteen minutes while the store is open, the
 * Worker closes the store and pushes the owner a Reopen notice, which is what
 * stops a sleeping phone from silently accepting orders it never shows.
 *
 * Three writers keep that number fresh, in order of reliability:
 *   1. the foreground service while the kitchen console is open,
 *   2. a fifteen-minute WorkManager job (survives process death and reboot),
 *   3. an explicit write whenever the kitchen app comes to the foreground.
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
        reference.setValue(
            mapOf(
                "lastSeen" to ServerValue.TIMESTAMP,
                "online" to true,
                "device" to "vendor-app"
            )
        ).await()
        Unit
    }

    suspend fun markOffline(): Result<Unit> = runCatching {
        reference.updateChildren(
            mapOf(
                "lastSeen" to ServerValue.TIMESTAMP,
                "online" to false
            )
        ).await()
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
        const val STALE_AGE_MILLIS = 15 * 60 * 1000L

        fun serviceIntent(context: Context): Intent =
            Intent(context, PresenceHeartbeatService::class.java)

        fun startService(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, serviceIntent(context)) }
        }

        fun stopService(context: Context) {
            runCatching { context.stopService(serviceIntent(context)) }
        }

        fun describeAge(ageMillis: Long): String = when {
            ageMillis <= 0L -> "never"
            ageMillis < 60_000L -> "${ageMillis / 1000} s ago"
            ageMillis < 3_600_000L -> "${ageMillis / 60_000} min ago"
            else -> "${ageMillis / 3_600_000L} h ago"
        }
    }
}
