package com.undal.syl.app.features.vendor.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.undal.syl.app.core.model.VendorOrder
import com.undal.syl.app.core.model.VendorQueueFilter
import com.undal.syl.app.data.repo.VendorOrderRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
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
    /**
     * Nothing this ViewModel does may end the process.
     *
     * An exception that escapes a `viewModelScope` job is uncaught, and Android's
     * answer to an uncaught exception is to kill the app — which the person
     * experiences as the app vanishing a few seconds after it opened, then the
     * crash guard reopening it, then the home screen. Every coroutine here carries
     * this handler, so a failed read becomes one sentence where the screen already
     * shows messages.
     */
    private val guard = com.undal.syl.app.core.util.UndalGuards.viewModel("reading the kitchen's orders") { message ->
        _state.value = _state.value.copy(message = message)
    }

    private val refreshKey = MutableStateFlow(0)
    private var week: List<VendorOrder> = emptyList()

    /**
     * The age badges, ticking once a second — but only while a screen is looking.
     *
     * This was a `while (true)` loop inside `viewModelScope`, and a view model
     * lives for as long as its screen is on the back stack: on a kitchen phone
     * left in a pocket with the queue open, that was one wake-up per second, for
     * hours, for a number nobody was reading. `WhileSubscribed` ties the clock to
     * the screen instead, with five seconds of grace so a rotation does not
     * restart it.
     */
    val now: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000L)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), System.currentTimeMillis())

    init {
        viewModelScope.launch(guard) {
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
