package com.undal.syl.app.core.background

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single source of truth for "is an order ringing right now".
 *
 * The alert service writes here, the UI reads here, and because it lives in the
 * process-wide object graph it survives activity recreation, rotation and a
 * locked screen. If this is non-null while the vendor opens the app, the app
 * jumps straight to the ringing order instead of the dashboard.
 */
object OrderAlertController {

    data class RingingOrder(
        val orderId: String,
        val shortId: String,
        val title: String,
        val body: String,
        val startedAtMillis: Long
    )

    private val _ringing = MutableStateFlow<RingingOrder?>(null)
    val ringing: StateFlow<RingingOrder?> = _ringing.asStateFlow()

    /** Deep link the alert wants the app to open once it comes to the foreground. */
    private val _pendingRoute = MutableStateFlow<String?>(null)
    val pendingRoute: StateFlow<String?> = _pendingRoute.asStateFlow()

    fun start(order: RingingOrder) {
        _ringing.value = order
    }

    fun stop(orderId: String? = null) {
        val current = _ringing.value ?: return
        if (orderId == null || current.orderId == orderId) {
            _ringing.value = null
        }
    }

    fun requestRoute(route: String) {
        _pendingRoute.value = route
    }

    fun consumeRoute(): String? {
        val route = _pendingRoute.value
        _pendingRoute.value = null
        return route
    }

    fun isRinging(orderId: String): Boolean = _ringing.value?.orderId == orderId
}
