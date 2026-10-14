package com.undal.syl.app.features.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.undal.syl.app.core.session.AuthSession
import com.undal.syl.app.data.repo.NotifInboxRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The in-app notification inbox (C-078).
 *
 * Every push that reaches this phone is also stored under
 * `notifInbox/{uid}/items`, so a notification swiped away at the bus stop is
 * still waiting here — with its picture, its order link and its read state.
 */
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val repo: NotifInboxRepo,
    private val session: AuthSession
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val items: List<NotifInboxRepo.InboxItem> = emptyList(),
        val notice: String? = null,
        val busy: Boolean = false
    ) {

        val unread: Int get() = items.count { !it.read }

        val hasAnything: Boolean get() = items.isNotEmpty()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        val uid = session.uid
        if (uid == null) {
            _state.value = State(loading = false, notice = "Sign in to see your notifications.")
        } else {
            viewModelScope.launch {
                repo.observe(uid).collect { items ->
                    _state.value = _state.value.copy(loading = false, items = items)
                }
            }
        }
    }

    /** Opens an item: marks it read, and reports the order it points at, if any. */
    fun open(item: NotifInboxRepo.InboxItem, onOpenOrder: (String) -> Unit) {
        val uid = session.uid ?: return
        viewModelScope.launch {
            repo.markRead(uid, item.id)
            val orderId = item.orderId
            if (!orderId.isNullOrBlank() && item.type.startsWith("order")) {
                onOpenOrder(orderId)
            }
        }
    }

    fun markAllRead() {
        val uid = session.uid ?: return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val result = repo.markAllRead(uid)
            _state.value = _state.value.copy(
                busy = false,
                notice = result.exceptionOrNull()?.let { "Some notifications could not be updated. Try again." }
            )
        }
    }

    fun clearNotice() {
        _state.value = _state.value.copy(notice = null)
    }
}
