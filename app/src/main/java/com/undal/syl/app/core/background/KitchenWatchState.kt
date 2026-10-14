package com.undal.syl.app.core.background

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the live order watcher is actually running, right now.
 *
 * A kitchen that believes it is armed and is not is the worst state this app can
 * be in, so "is the watcher running" is a fact the UI is allowed to read rather
 * than assume. The service writes it on start and on stop (including the stop it
 * performs on itself when its listener fails, which is the case that matters:
 * a listener that died at 3 a.m. must not leave a green tick behind).
 *
 * It lives in the process-wide object graph next to [OrderAlertController], so it
 * survives activity recreation and rotation.
 */
object KitchenWatchState {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun started() {
        _lastError.value = null
        _running.value = true
    }

    fun stopped(reason: String? = null) {
        _running.value = false
        if (!reason.isNullOrBlank()) _lastError.value = reason
    }
}
