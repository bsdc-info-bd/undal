package com.undal.syl.app.core.background

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.undal.syl.app.MainActivity
import com.undal.syl.app.R
import com.undal.syl.app.core.fcm.AlarmChannels
import com.undal.syl.app.core.util.OrderStatus
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The kitchen's eyes — the layer that does not need push to work.
 *
 * Push is a courier: FCM can be delayed by a phone in a lift, an aggressive
 * battery saver, an OEM task killer, a token that rotated a moment before the
 * send, or a server whose `vendorUid()` lookup came back empty. Any of those and
 * the kitchen simply does not ring — silently, which is the worst property a
 * missed order can have. A courier cannot be the only route.
 *
 * This service is the second route, and it is strictly better whenever it can
 * run: it holds a **live listener on Firestore for orders in `placed`**, so the
 * moment an order exists, this phone rings. No push, no TTL, no vendor lookup,
 * no topic subscription — one socket that Firebase already keeps open.
 *
 * It runs in the foreground with an ongoing (and deliberately silent)
 * notification, which is what stops Android from reclaiming the process. It is
 * started when a vendor signs in, after a reboot, and when the kitchen opens the
 * app; it stops on sign-out.
 *
 * Two listeners, because they answer different questions:
 *   · `orders where status == placed` — "is there an order nobody answered?"
 *     (the query is equality-only on purpose: no composite index to deploy)
 *   · `orderLive` in the Realtime Database — "did anybody answer it?", which
 *     covers the second kitchen phone accepting an order this phone is ringing
 *     for, and keeps the two devices silent together.
 *
 * What it cannot do: run when the process is dead. That is what push and the
 * Worker's re-alert sweep are for, and the readiness screen says which of the
 * three routes are currently live.
 */
@AndroidEntryPoint
class KitchenWatchService : Service() {

    @Inject lateinit var auth: FirebaseAuth
    @Inject lateinit var firestore: FirebaseFirestore
    @Inject lateinit var database: FirebaseDatabase

    private var ordersRegistration: ListenerRegistration? = null
    private var liveReference: com.google.firebase.database.DatabaseReference? = null

    /** Orders this service has already handed to the alert, so it rings once. */
    private val handedOver = mutableSetOf<String>()

    private val liveListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            // Every order the kitchen is ringing for, plus any order another
            // device answered. `orderLive/{id}/status` is written by the app the
            // moment a transition happens, on whichever device made it.
            snapshot.children.forEach { child ->
                val orderId = child.key ?: return@forEach
                val status = child.child("status").getValue(String::class.java)
                if (status != null && status != OrderStatus.PLACED.wire) {
                    OrderAlertService.silence(applicationContext, orderId)
                    handedOver.remove(orderId)
                }
            }
        }

        override fun onCancelled(error: DatabaseError) {
            Log.w(TAG, "orderLive watcher cancelled: ${error.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AlarmChannels.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopWatching()
            return START_NOT_STICKY
        }

        // Foreground before anything else: a listener held by a service Android
        // is about to reclaim is not a guarantee.
        startForegroundNotification()
        startWatching()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val open = PendingIntent.getActivity(
            this,
            0,
            MainActivity.intentFor(this, "vendor/dashboard"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, AlarmChannels.VENDOR_WATCH)
            .setSmallIcon(R.drawable.ic_undal_mark)
            .setContentTitle(getString(R.string.watch_title))
            .setContentText(getString(R.string.watch_body))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, AlarmChannels.WATCH_NOTIFICATION_ID, notification, type)
    }

    private fun startWatching() {
        if (ordersRegistration != null) return

        ordersRegistration = firestore.collection(ORDERS)
            .whereEqualTo("status", OrderStatus.PLACED.wire)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "order watcher stopped: ${error.message}")
                    // Losing the listener quietly would leave a phone that looks
                    // armed and is not. Stop the service so the notification goes
                    // away and the readiness screen shows the truth.
                    KitchenWatchState.stopped(error.message ?: "the order listener was refused")
                    stopWatching()
                    return@addSnapshotListener
                }
                snapshot?.documents.orEmpty().forEach { document ->
                    if (!handedOver.add(document.id)) return@forEach
                    val shortId = document.getString("shortId").orEmpty()
                    val itemCount = (document.get("items") as? List<*>)?.size ?: 0
                    val name = document.getString("customerName").orEmpty()
                    OrderAlertService.ring(
                        context = applicationContext,
                        orderId = document.id,
                        shortId = shortId,
                        title = if (shortId.isBlank()) "New order" else "New order $shortId",
                        body = listOf(name.ifBlank { "A customer" }, "$itemCount item(s)")
                            .joinToString(" · "),
                        source = OrderAlertService.SOURCE_WATCH
                    )
                }
            }

        liveReference = database.getReference(LIVE).also { reference ->
            reference.addValueEventListener(liveListener)
        }
        KitchenWatchState.started()
        Log.i(TAG, "kitchen watch started")
    }

    private fun stopWatching() {
        ordersRegistration?.remove()
        ordersRegistration = null
        liveReference?.removeEventListener(liveListener)
        liveReference = null
        handedOver.clear()
        KitchenWatchState.stopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        ordersRegistration?.remove()
        ordersRegistration = null
        liveReference?.removeEventListener(liveListener)
        liveReference = null
        KitchenWatchState.stopped()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "UndalWatch"
        const val ACTION_START = "com.undal.syl.app.action.WATCH_START"
        const val ACTION_STOP = "com.undal.syl.app.action.WATCH_STOP"
        private const val ORDERS = "orders"
        private const val LIVE = "orderLive"

        /** Starts the watch. Idempotent: starting twice keeps one watcher. */
        fun start(context: Context) {
            val intent = Intent(context, KitchenWatchService::class.java).apply { action = ACTION_START }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "could not start the kitchen watch", it) }
        }

        /** Stops it — used on sign-out, so a customer phone never watches. */
        fun stop(context: Context) {
            val intent = Intent(context, KitchenWatchService::class.java).apply { action = ACTION_STOP }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /**
         * Starts or stops based on who is signed in. Callers do not have to know
         * the rule; this service does.
         */
        fun sync(context: Context, isVendor: Boolean) {
            if (isVendor) start(context) else stop(context)
        }
    }
}
