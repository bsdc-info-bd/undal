package com.undal.syl.app.features.vendor.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.undal.syl.app.data.repo.NotifInboxRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The kitchen's own alert history (V-109).
 *
 * Every new order that reached this phone is recorded here by the order
 * repository exactly once. It is not a duplicate of the customer's inbox: it is
 * the kitchen's record of what rang, when, and for how much — which is what the
 * owner reaches for when a customer claims an order was never seen.
 */
@HiltViewModel
class VendorInboxViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val notifInboxRepo: NotifInboxRepo
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val items: List<NotifInboxRepo.InboxItem> = emptyList(),
        val unread: Int = 0,
        val message: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            _state.value = State(loading = false, message = "Sign in again — this session has no account.")
        } else {
            viewModelScope.launch {
                notifInboxRepo.observe(uid).collect { items ->
                    _state.value = _state.value.copy(
                        loading = false,
                        items = items,
                        unread = items.count { !it.read }
                    )
                }
            }
        }
    }

    fun markRead(itemId: String) {
        val uid = auth.currentUser?.uid ?: return
        viewModelScope.launch { notifInboxRepo.markRead(uid, itemId) }
    }

    fun markAllRead() {
        val uid = auth.currentUser?.uid ?: return
        viewModelScope.launch {
            val result = notifInboxRepo.markAllRead(uid)
            _state.value = _state.value.copy(
                message = if (result.isSuccess) "Everything in the history is marked as read." else null
            )
        }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
