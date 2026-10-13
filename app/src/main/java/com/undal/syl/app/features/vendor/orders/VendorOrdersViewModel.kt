package com.undal.syl.app.features.vendor.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.undal.syl.app.core.model.VendorOrder
import com.undal.syl.app.core.model.VendorQueueFilter
import com.undal.syl.app.data.repo.VendorOrderRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The queue (V-015, V-022, V-041, V-042, V-047).
 *
 * One realtime window over a week of orders, filtered on the phone: the tabs are
 * instant, the cancelled orders stay visible in history with their note, and the
 * oldest waiting order is always first because that is the one going cold.
 */
@HiltViewModel
class VendorOrdersViewModel @Inject constructor(
    private val vendorOrderRepo: VendorOrderRepo
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val filter: VendorQueueFilter = VendorQueueFilter.NEW,
        val visible: List<VendorOrder> = emptyList(),
        val counts: Map<VendorQueueFilter, Int> = emptyMap(),
        val fromCache: Boolean = false,
        val message: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val refreshKey = MutableStateFlow(0)
    private var week: List<VendorOrder> = emptyList()

    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                _now.value = System.currentTimeMillis()
                delay(1_000L)
            }
        }
        viewModelScope.launch {
            refreshKey.flatMapLatest { vendorOrderRepo.observeWeek() }.collect { feed ->
                week = feed.orders
                _state.value = _state.value.copy(
                    loading = false,
                    fromCache = feed.fromCache,
                    counts = VendorQueueFilter.entries.associateWith { filter ->
                        feed.orders.count { filter.matches(it.status) }
                    }
                )
                apply()
            }
        }
    }

    fun onFilterChange(filter: VendorQueueFilter) {
        _state.value = _state.value.copy(filter = filter)
        apply()
    }

    /** Restarts the listener, which forces a server read (V-022). */
    fun refresh() {
        refreshKey.value += 1
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun apply() {
        val filter = _state.value.filter
        val matching = week.filter { filter.matches(it.status) }
        val ordered = if (filter == VendorQueueFilter.NEW) {
            matching.sortedBy { it.createdAtMillis }
        } else {
            matching.sortedByDescending { it.createdAtMillis }
        }
        _state.value = _state.value.copy(visible = ordered)
    }
}
