package com.undal.syl.app.core.fcm

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.undal.syl.app.MainActivity
import com.undal.syl.app.R
import com.undal.syl.app.core.background.NotifyRetryWorker
import com.undal.syl.app.core.background.OrderAlertService
import com.undal.syl.app.core.background.PrepTimerService
import com.undal.syl.app.core.background.TokenRefreshWorker
import com.undal.syl.app.data.repo.NotifInboxRepo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The single entry point for every push Undal sends (PART I-1 · PART J).
 *
 * Messages are **data-only**, which is deliberate: a `notification` payload would
 * let the system tray handle the message while the app is in the background, and
 * the vendor order alert would never start. Because everything arrives here,
 * `onMessageReceived` runs in the foreground, in the background and on a locked
 * screen — the three states that matter for a kitchen.
 *
 * Message types and what they do:
 *   order_created → start the ringing alert service (vendor) + inbox entry
 *   order_status  → notification for the customer, mirrors accepted to the prep timer
 *   chat          → notification with the order deep link
 *   promo         → quiet offer notification
 *   vendor_self   → kitchen notice (auto-offline, prep due, reopening)
 */
@AndroidEntryPoint
class UndalFcmService : FirebaseMessagingService() {

    @Inject lateinit var inboxRepo: NotifInboxRepo
    @Inject lateinit var progress: com.undal.syl.app.core.session.OnboardingProgress

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        // The token changed under us (restore, reinstall, security event): the
        // fastest safe reaction is to let the worker re-register it with uid.
        TokenRefreshWorker.enqueue(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        AlarmChannels.ensureChannels(applicationContext)
        val data = message.data
        val uid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
        val isVendor = runCatching {
            FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.result?.claims?.get("vendor") == true
        }.getOrDefault(false)

        when (data["type"]) {
            TYPE_ORDER_CREATED -> handleOrderCreated(data, uid)
            TYPE_ORDER_STATUS -> handleOrderStatus(data, uid, isVendor)
            TYPE_CHAT -> handleChat(data, uid, isVendor)
            TYPE_PROMO -> handlePromo(data, uid)
            TYPE_VENDOR_SELF -> handleVendorSelf(data, uid, isVendor)
            else -> Log.i(TAG, "ignoring push of unknown type ${data["type"]}")
        }
    }

    // ------------------------------------------------------------------ vendor
    private fun handleOrderCreated(data: Map<String, String>, uid: String?) {
        val orderId = data["orderId"] ?: return
        val shortId = data["shortId"].orEmpty()
        val title = data["title"].orEmpty().ifBlank { "New order $shortId" }
        val body = data["body"].orEmpty().ifBlank { "Tap to review and accept" }

        // The sound loop is the product promise: it starts even if the process
        // was dead a second ago, and it keeps ringing until somebody answers.
        // A `test` marker keeps the diagnostics alert honest: it rings exactly
        // like a real order, labels itself and stops itself.
        val isTest = data["test"] == "true"
        OrderAlertService.ring(
            context = applicationContext,
            orderId = orderId,
            shortId = shortId,
            title = title,
            body = body,
            isTest = isTest,
            source = OrderAlertService.SOURCE_PUSH
        )

        // A repeated push (the Worker's re-alert sweep) has already done its job
        // by ringing; recording it again in the inbox would just be noise.
        if (isTest || data["repeated"] == "true") return

        if (uid != null) {
            scope.launch {
                inboxRepo.record(
                    uid = uid,
                    title = title,
                    body = body,
                    type = TYPE_ORDER_CREATED,
                    orderId = orderId
                )
            }
        }
    }

    private fun handleVendorSelf(data: Map<String, String>, uid: String?, isVendor: Boolean) {
        val title = data["title"].orEmpty().ifBlank { "Undal kitchen" }
        if (!isVendor) {
            // The claim can be an hour stale, and this message is a kitchen
            // notice (auto-offline, prep due, an order nobody answered) that
            // must not be dropped on a technicality: ask the device what it
            // resolved last, and only then decide.
            scope.launch {
                val remembered = runCatching { progress.kitchenDevice.first() }.getOrDefault(false)
                if (remembered) handleVendorSelf(data, uid, isVendor = true)
            }
            return
        }
        val body = data["body"].orEmpty()
        val kind = data["kind"].orEmpty()

        postNotification(
            channelId = if (kind == "prep_due" || kind == "missed") AlarmChannels.VENDOR_GENERAL
            else AlarmChannels.VENDOR_NEW_ORDER,
            notificationId = (kind + title).hashCode() and 0x7FFFFFFF,
            title = title,
            body = body,
            deepLink = if (kind == "auto_offline") "vendor/store" else "vendor/orders",
            highPriority = true,
            ongoing = false
        )

        if (uid != null) {
            scope.launch {
                inboxRepo.record(
                    uid = uid,
                    title = title,
                    body = body,
                    type = "vendor_self:$kind",
                    orderId = data["orderId"]
                )
            }
        }
    }

    // ---------------------------------------------------------------- customer
    private fun handleOrderStatus(data: Map<String, String>, uid: String?, isVendor: Boolean) {
        val orderId = data["orderId"] ?: return
        val status = data["status"].orEmpty()
        val title = data["title"].orEmpty().ifBlank { "Order update" }
        val body = data["body"].orEmpty()

        // A kitchen device that receives its own status push (a second kitchen
        // phone, for example) must not start a countdown for the customer.
        if (isVendor && status == "accepted") {
            PrepTimerService.startFromIso(
                context = applicationContext,
                orderId = orderId,
                shortId = data["shortId"].orEmpty(),
                prepEndsAtIso = data["prepEndsAt"]
            )
        }

        postNotification(
            channelId = AlarmChannels.CUSTOMER_ORDERS,
            notificationId = orderId.hashCode() and 0x7FFFFFFF,
            title = title,
            body = body,
            deepLink = "customer/order/$orderId",
            highPriority = true,
            ongoing = false
        )

        if (uid != null) {
            scope.launch {
                inboxRepo.record(
                    uid = uid,
                    title = title,
                    body = body,
                    type = "order_status:$status",
                    orderId = orderId
                )
            }
        }
    }

    private fun handleChat(data: Map<String, String>, uid: String?, isVendor: Boolean) {
        val orderId = data["orderId"] ?: return
        val title = data["title"].orEmpty().ifBlank { "New message" }
        val body = data["body"].orEmpty()

        postNotification(
            channelId = if (isVendor) AlarmChannels.VENDOR_GENERAL else AlarmChannels.CUSTOMER_ORDERS,
            notificationId = ("chat" + orderId).hashCode() and 0x7FFFFFFF,
            title = title,
            body = body,
            deepLink = if (isVendor) "vendor/chat/$orderId" else "customer/chat/$orderId",
            highPriority = false,
            ongoing = false
        )

        if (uid != null) {
            scope.launch {
                inboxRepo.record(
                    uid = uid,
                    title = title,
                    body = body,
                    type = "chat",
                    orderId = orderId
                )
            }
        }
    }

    private fun handlePromo(data: Map<String, String>, uid: String?) {
        val title = data["title"].orEmpty().ifBlank { "Undal HomeKitchen" }
        val body = data["body"].orEmpty()
        val imageUrl = data["imageUrl"]?.takeIf { it.isNotBlank() }

        postNotification(
            channelId = AlarmChannels.CUSTOMER_PROMOS,
            notificationId = ("promo" + (data["sentForDate"] ?: title)).hashCode() and 0x7FFFFFFF,
            title = title,
            body = body,
            deepLink = "customer/home",
            highPriority = false,
            ongoing = false,
            imageUrl = imageUrl
        )

        if (uid != null) {
            scope.launch {
                inboxRepo.record(
                    uid = uid,
                    title = title,
                    body = body,
                    type = TYPE_PROMO,
                    imageUrl = imageUrl
                )
            }
        }
    }

    /**
     * Posting a notification. Notification permission can be revoked at any
     * time, so this is always guarded — a missing permission must never crash
     * the service that is also responsible for ringing orders.
     */
    private fun postNotification(
        channelId: String,
        notificationId: Int,
        title: String,
        body: String,
        deepLink: String,
        highPriority: Boolean,
        ongoing: Boolean,
        imageUrl: String? = null
    ) {
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) {
            Log.i(TAG, "notifications are disabled; skipping $channelId")
            return
        }

        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            MainActivity.intentFor(applicationContext, deepLink),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_undal_mark)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (highPriority) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(
                if (channelId == AlarmChannels.CUSTOMER_ORDERS) NotificationCompat.CATEGORY_STATUS
                else NotificationCompat.CATEGORY_MESSAGE
            )
            .setAutoCancel(true)
            .setOngoing(ongoing)
            .setContentIntent(contentIntent)

        // Offer images are shown inside the app (Coil + the inbox), not in the
        // tray: loading a remote bitmap here would run on the push thread and
        // could slow down the order-alert path that shares this service.
        runCatching { manager.notify(notificationId, builder.build()) }
    }

    override fun onDeletedMessages() {
        // FCM dropped messages while the device was offline. The in-app listeners
        // rebuild everything from Firestore, so the only work here is to make
        // sure the device can be reached again.
        TokenRefreshWorker.enqueue(applicationContext)
        super.onDeletedMessages()
    }

    companion object {
        private const val TAG = "UndalFcm"

        // One source of truth, shared with the Worker and checked by
        // tools/check-alert-ladder.mjs: these strings are a wire format.
        const val TYPE_ORDER_CREATED = AlertContract.TYPE_ORDER_CREATED
        const val TYPE_ORDER_STATUS = AlertContract.TYPE_ORDER_STATUS
        const val TYPE_CHAT = AlertContract.TYPE_CHAT
        const val TYPE_PROMO = AlertContract.TYPE_PROMO
        const val TYPE_VENDOR_SELF = AlertContract.TYPE_VENDOR_SELF

        /** Used by the diagnostics screen to queue a test that exercises the real path. */
        fun scheduleRetry(context: Context, orderId: String, kind: String) {
            NotifyRetryWorker.enqueue(context, orderId, kind)
        }
    }
}
