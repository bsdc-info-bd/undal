package com.undal.syl.app.features.vendor.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.undal.syl.app.core.model.VendorOrder
import com.undal.syl.app.data.repo.ChatRepo
import com.undal.syl.app.data.repo.VendorOrderRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The chat list (V-097, V-102, V-104).
 *
 * One row per order that has a conversation worth reading: everything still
 * moving, plus the last few delivered orders in case the customer writes about
 * what arrived. A preview listener runs only for the orders on screen, so a phone
 * on a slow connection is not holding twenty sockets open.
 */
@HiltViewModel
class VendorChatListViewModel @Inject constructor(
    private val vendorOrderRepo: VendorOrderRepo,
    private val chatRepo: ChatRepo
) : ViewModel() {

    data class Row(
        val order: VendorOrder,
        val lastText: String,
        val lastAtMillis: Long,
        val unread: Int,
        val lastFromKitchen: Boolean
    )

    data class Preview(val lastText: String = "", val lastAtMillis: Long = 0L, val unread: Int = 0, val fromKitchen: Boolean = true)

    data class State(
        val loading: Boolean = true,
        val rows: List<Row> = emptyList(),
        val fromCache: Boolean = false,
        val tracked: Int = 0
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val previews = MutableStateFlow<Map<String, Preview>>(emptyMap())
    private val orders = MutableStateFlow<List<VendorOrder>>(emptyList())
    private val trackers = mutableMapOf<String, Job>()

    init {
        viewModelScope.launch {
            vendorOrderRepo.observeWeek().collect { feed ->
                orders.value = feed.orders
                _state.value = _state.value.copy(loading = false, fromCache = feed.fromCache)
                retarget(feed.orders)
                rebuild()
            }
        }
        viewModelScope.launch {
            previews.collect { rebuild() }
        }
    }

    /** Everything open, plus the five most recent finished orders. */
    private fun retarget(all: List<VendorOrder>) {
        val window = (all.filter { it.isOpen } + all.filterNot { it.isOpen }.take(5))
            .distinctBy { it.id }
            .take(12)
        window.forEach { order -> track(order.id) }
        val stale = trackers.keys - window.map { it.id }.toSet()
        stale.forEach { id ->
            trackers.remove(id)?.cancel()
            previews.value = previews.value - id
        }
        _state.value = _state.value.copy(tracked = trackers.size)
    }

    private fun track(orderId: String) {
        if (trackers.containsKey(orderId)) return
        trackers[orderId] = viewModelScope.launch {
            combine(
                chatRepo.observeMessages(orderId),
                chatRepo.vendorLastReadFlow(orderId)
            ) { messages, lastRead ->
                val last = messages.lastOrNull()
                Preview(
                    lastText = last?.text.orEmpty().take(90),
                    lastAtMillis = last?.tsMillis ?: 0L,
                    unread = chatRepo.unreadFromCustomer(messages, lastRead),
                    fromKitchen = last?.fromKitchen ?: true
                )
            }.collect { preview ->
                previews.value = previews.value + (orderId to preview)
            }
        }
    }

    private fun rebuild() {
        val rows = orders.value.mapNotNull { order ->
            val preview = previews.value[order.id] ?: Preview()
            if (preview.lastAtMillis == 0L && !order.isOpen) return@mapNotNull null
            if (preview.lastAtMillis == 0L && order.status == com.undal.syl.app.core.util.OrderStatus.DELIVERED) {
                return@mapNotNull null
            }
            Row(
                order = order,
                lastText = preview.lastText,
                lastAtMillis = preview.lastAtMillis,
                unread = preview.unread,
                lastFromKitchen = preview.fromKitchen
            )
        }.sortedWith(
            compareByDescending<Row> { it.unread > 0 }
                .thenByDescending { it.lastAtMillis }
                .thenByDescending { it.order.createdAtMillis }
        )
        _state.value = _state.value.copy(rows = rows)
    }

    fun openOrderId(row: Row): String = row.order.id
}
