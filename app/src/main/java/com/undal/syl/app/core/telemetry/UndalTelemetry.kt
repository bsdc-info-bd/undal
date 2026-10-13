package com.undal.syl.app.core.telemetry

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.perf.metrics.Trace
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Analytics, Crashlytics and Performance in one place (P-023 · P-024 · P-025).
 *
 * Three rules govern everything here:
 *
 *  1. **Telemetry never breaks an order.** Every SDK call is wrapped, every
 *     handle is nullable, and a phone that has no Play Services, no network or
 *     no `google-services.json` keeps working exactly as before.
 *  2. **Only events that answer a question the kitchen actually asks.** How many
 *     orders are placed, how long the kitchen takes to accept one, which dishes
 *     people open — not "user tapped a button".
 *  3. **Nothing personal leaves the phone.** No phone numbers, no addresses, no
 *     customer names: an order id, a count, a duration and a dish id at most.
 */
@Singleton
class UndalTelemetry @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val analytics: FirebaseAnalytics? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching { FirebaseAnalytics.getInstance(context) }.getOrNull()
    }

    private val crashlytics: FirebaseCrashlytics? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching { FirebaseCrashlytics.getInstance() }.getOrNull()
    }

    private val performance: FirebasePerformance? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching { FirebasePerformance.getInstance() }.getOrNull()
    }

    @Volatile
    private var appStartTrace: Trace? = null

    /**
     * Debug builds report nothing: a crash that only happens on the owner's own
     * phone while they are poking at a screen should not be counted against the
     * crash-free rate the release build is judged by.
     */
    fun configureForBuild(isDebugBuild: Boolean) {
        runCatching { crashlytics?.setCrashlyticsCollectionEnabled(!isDebugBuild) }
    }

    /** Called as early as possible in `Application.onCreate`. */
    fun beginAppStart() {
        runCatching {
            val trace = performance?.newTrace(TRACE_APP_START) ?: return
            trace.start()
            appStartTrace = trace
        }
    }

    /**
     * Called after the first frame of the first screen, which is what a person
     * actually experiences as "the app opened".
     */
    fun endAppStart() {
        runCatching {
            val trace = appStartTrace ?: return
            trace.stop()
            appStartTrace = null
        }
    }

    /** The signed-in account, so a crash report belongs to somebody. */
    fun identify(uid: String) {
        runCatching {
            analytics?.setUserId(uid)
            crashlytics?.setUserId(uid)
        }
    }

    /** An order reached the kitchen (P-024). */
    fun placeOrder(orderId: String, itemCount: Int, totalPaisa: Long) {
        logEvent(
            EVENT_ORDER_PLACED,
            Bundle().apply {
                putString(PARAM_ORDER_ID, orderId.take(120))
                putLong(PARAM_ITEM_COUNT, itemCount.toLong())
                putLong(PARAM_TOTAL_PAISA, totalPaisa)
            }
        )
        runCatching { crashlytics?.log("order placed · $itemCount item(s) · $totalPaisa paisa") }
    }

    /** How long the kitchen took to accept, from the customer's own tap (P-024). */
    fun orderAccepted(latencyMillis: Long) {
        val safe = latencyMillis.coerceAtLeast(0L)
        logEvent(
            EVENT_ACCEPT_LATENCY,
            Bundle().apply { putLong(PARAM_LATENCY_MS, safe) }
        )
        runCatching { crashlytics?.setCustomKey(KEY_LAST_ACCEPT_MS, safe) }
    }

    /** A dish or combo page was opened, and where the person came from (P-024). */
    fun offerOpened(cardId: String, source: String) {
        logEvent(
            EVENT_OFFER_OPENED,
            Bundle().apply {
                putString(PARAM_CARD_ID, cardId.take(120))
                putString(PARAM_SOURCE, source.take(40))
            }
        )
    }

    /** The state the kitchen phone was in if it ever crashes (P-023). */
    fun vendorState(storeOpen: Boolean, pendingOrders: Int) {
        runCatching {
            crashlytics?.setCustomKey(KEY_STORE_OPEN, storeOpen)
            crashlytics?.setCustomKey(KEY_PENDING_ORDERS, pendingOrders.coerceAtLeast(0).toLong())
        }
    }

    /**
     * Something failed that the customer or the kitchen recovered from. These are
     * the reports that actually matter in this product: a relay that timed out, a
     * coupon that vanished between the tap and the write.
     */
    fun recordNonFatal(where: String, error: Throwable?) {
        runCatching {
            val rich = crashlytics ?: return
            rich.log("non-fatal in $where")
            rich.setCustomKey(KEY_LAST_NON_FATAL, where.take(60))
            if (error != null) rich.recordException(error)
        }
    }

    /** The order-submission trace (P-025): from the customer's tap to the write. */
    fun beginOrderSubmit(): Trace? = startTrace(TRACE_ORDER_SUBMIT)

    /** A manual trace: order submission, a photo upload, anything slow enough to notice. */
    fun startTrace(name: String): Trace? =
        runCatching { performance?.newTrace(name)?.also { it.start() } }.getOrNull()

    fun stopTrace(trace: Trace?, succeeded: Boolean, attributes: Map<String, String> = emptyMap()) {
        runCatching {
            val running = trace ?: return
            running.putAttribute(ATTR_OUTCOME, if (succeeded) "ok" else "failed")
            attributes.forEach { (key, value) -> running.putAttribute(key.take(40), value.take(90)) }
            running.stop()
        }
    }

    private fun logEvent(name: String, params: Bundle) {
        runCatching { analytics?.logEvent(name, params) }
    }

    private companion object {
        const val TRACE_APP_START = "undal_app_start"
        const val TRACE_ORDER_SUBMIT = "undal_order_submit"

        const val EVENT_ORDER_PLACED = "undal_order_placed"
        const val EVENT_ACCEPT_LATENCY = "undal_accept_latency"
        const val EVENT_OFFER_OPENED = "undal_offer_opened"

        const val PARAM_ORDER_ID = "order_id"
        const val PARAM_ITEM_COUNT = "item_count"
        const val PARAM_TOTAL_PAISA = "total_paisa"
        const val PARAM_LATENCY_MS = "latency_ms"
        const val PARAM_CARD_ID = "card_id"
        const val PARAM_SOURCE = "source"

        const val ATTR_OUTCOME = "outcome"
        const val KEY_STORE_OPEN = "store_open"
        const val KEY_PENDING_ORDERS = "pending_orders"
        const val KEY_LAST_ACCEPT_MS = "last_accept_latency_ms"
        const val KEY_LAST_NON_FATAL = "last_non_fatal"
    }
}
