package com.undal.syl.app.core.background

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import com.undal.syl.app.core.fcm.PushDeviceRole
import com.undal.syl.app.core.net.WorkerApi
import java.util.concurrent.TimeUnit

/**
 * Background jobs that keep the kitchen trustworthy when the app is not on
 * screen (PART I-4 · I-5 · P-019 · P-020).
 *
 * All three are Hilt workers, so they inject the same repositories the UI uses,
 * and all three are idempotent: running them twice changes nothing.
 */
object UndalWorkNames {
    const val PRESENCE_HEARTBEAT_PERIODIC = "undal-presence-heartbeat"
    const val TOKEN_REFRESH_ONE_TIME = "undal-token-refresh"
    const val NOTIFY_RETRY_PREFIX = "undal-notify-retry"
}

/** Fifteen-minute heartbeat fallback, rescheduled on boot and after every login. */
@HiltWorker
class PresenceHeartbeatWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val heartbeat: PresenceHeartbeat,
    private val auth: FirebaseAuth
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Only a signed-in vendor device should ever claim the kitchen is alive.
        val uid = auth.currentUser?.uid ?: return Result.success()
        // Being signed in is not enough, and this check used to be the whole of
        // it: `presence/vendor` is the one number the Worker's ten-minute rule
        // trusts, so a customer's phone beating it would hold a closed store open
        // — and every customer phone ran this job, because the app scheduled it at
        // every start for whoever was on it. The remembered role is the same
        // answer the watchdog and the boot receiver use, and it costs no network.
        if (!PushDeviceRole.isKitchen(applicationContext, uid)) return Result.success()
        heartbeat.beat()
        return Result.success()
    }

    companion object {
        /**
         * Arms the fifteen-minute heartbeat fallback.
         *
         * WorkManager's first touch of this process opens its database on the
         * calling thread, so callers decide where that happens — `UndalApp` does
         * it off the main thread, and a kitchen that has just signed in does it
         * from a coroutine.
         */
        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<PresenceHeartbeatWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UndalWorkNames.PRESENCE_HEARTBEAT_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UndalWorkNames.PRESENCE_HEARTBEAT_PERIODIC)
        }
    }
}

/**
 * Re-registers the FCM token and the vendor heartbeat after a reboot or an app
 * update, because a device that came back from the dead must be reachable again
 * without anyone opening the app (V-002 · V-013).
 */
@HiltWorker
class TokenRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val auth: FirebaseAuth,
    private val registrar: com.undal.syl.app.core.fcm.TokenRegistrar,
    private val progress: com.undal.syl.app.core.session.OnboardingProgress
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val user = auth.currentUser ?: return Result.success()
        // Forced, once, per boot/update: this worker exists precisely for the
        // moments when the cached token cannot be trusted, and the claim decides
        // whether this device may read the kitchen's orders at all.
        val isVendor = runCatching {
            user.getIdToken(true).await().claims?.get("vendor") == true
        }.getOrDefault(false)

        progress.setKitchenDevice(isVendor)
        com.undal.syl.app.core.fcm.PushDeviceRole.remember(applicationContext, user.uid, isVendor)

        return registrar.register(uid = user.uid, isVendor = isVendor, subscribeToPromos = !isVendor)
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UndalWorkNames.TOKEN_REFRESH_ONE_TIME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<TokenRefreshWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
            )
        }
    }
}

/**
 * Retries a Worker notification relay that failed while offline (P-020).
 *
 * Order notifications are queued with a bounded number of attempts so a phone
 * that was in a lift for a minute still rings the kitchen, but a phone that has
 * been offline for a day does not hammer the network.
 */
@HiltWorker
class NotifyRetryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val api: WorkerApi
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val orderId = inputData.getString(KEY_ORDER_ID) ?: return Result.failure()
        val kind = inputData.getString(KEY_KIND) ?: return Result.failure()
        val status = inputData.getString(KEY_STATUS).orEmpty()
        val text = inputData.getString(KEY_TEXT).orEmpty()
        val attempts = inputData.getInt(KEY_ATTEMPTS, 0)

        if (attempts >= MAX_ATTEMPTS) return Result.failure()

        val outcome = when (kind) {
            KIND_ORDER_CREATED -> api.notifyOrderCreated(orderId)
            KIND_ORDER_STATUS -> api.notifyOrderStatus(orderId, status)
            KIND_CHAT -> api.notifyChat(orderId, text)
            else -> return Result.failure()
        }

        return when (outcome) {
            is WorkerApi.ApiResult.Success -> Result.success()
            is WorkerApi.ApiResult.Permanent -> Result.failure()
            is WorkerApi.ApiResult.Retryable -> {
                val next = OneTimeWorkRequestBuilder<NotifyRetryWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_ORDER_ID to orderId,
                            KEY_KIND to kind,
                            KEY_STATUS to status,
                            KEY_TEXT to text,
                            KEY_ATTEMPTS to (attempts + 1)
                        )
                    )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
                WorkManager.getInstance(applicationContext)
                    .enqueueUniqueWork(UndalWorkNames.NOTIFY_RETRY_PREFIX + orderId + kind, ExistingWorkPolicy.REPLACE, next)
                Result.success()
            }
        }
    }

    companion object {
        const val KEY_ORDER_ID = "orderId"
        const val KEY_KIND = "kind"
        const val KEY_STATUS = "status"
        const val KEY_TEXT = "text"
        const val KEY_ATTEMPTS = "attempts"
        const val MAX_ATTEMPTS = 8

        const val KIND_ORDER_CREATED = "order_created"
        const val KIND_ORDER_STATUS = "order_status"
        const val KIND_CHAT = "chat"

        /** Queues a notification relay that must survive a dead spot in the network. */
        fun enqueue(
            context: Context,
            orderId: String,
            kind: String,
            status: String = "",
            text: String = ""
        ) {
            val request: OneTimeWorkRequest = OneTimeWorkRequestBuilder<NotifyRetryWorker>()
                .setInputData(
                    workDataOf(
                        KEY_ORDER_ID to orderId,
                        KEY_KIND to kind,
                        KEY_STATUS to status,
                        KEY_TEXT to text,
                        KEY_ATTEMPTS to 0
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UndalWorkNames.NOTIFY_RETRY_PREFIX + orderId + kind, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
