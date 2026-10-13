package com.undal.syl.app.features.order

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.undal.syl.app.core.cart.Cart
import com.undal.syl.app.core.util.UndalResult
import com.undal.syl.app.data.repo.CartRepo
import com.undal.syl.app.data.repo.MenuRepo
import com.undal.syl.app.data.repo.OrderRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Order history (C-075 · C-076).
 *
 * The security rules cap a customer's order list at 50 documents, so the query
 * asks for exactly that and the screen reveals them in windows of twenty. That
 * is real paging over real data — and it is honest about its ceiling instead of
 * pretending the list is infinite.
 */
@HiltViewModel
class OrderHistoryViewModel @Inject constructor(
    private val orderRepo: OrderRepo,
    private val menuRepo: MenuRepo,
    private val cartRepo: CartRepo
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val orders: List<OrderRepo.OrderSummary> = emptyList(),
        val visible: Int = PAGE_SIZE,
        val reorderingId: String? = null,
        val notice: String? = null,
        val reorderedLines: Int = 0
    ) {

        val shown: List<OrderRepo.OrderSummary> get() = orders.take(visible)

        val active: List<OrderRepo.OrderSummary> get() = shown.filter { it.isActive }

        val past: List<OrderRepo.OrderSummary> get() = shown.filterNot { it.isActive }

        val canLoadMore: Boolean get() = orders.size > visible

        val hasAnything: Boolean get() = orders.isNotEmpty()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val visibleFlow = MutableStateFlow(PAGE_SIZE)
    private val reorderingFlow = MutableStateFlow<String?>(null)
    private val noticeFlow = MutableStateFlow<String?>(null)
    private val reorderedFlow = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            combine(
                orderRepo.observeMyOrders(MAX_WINDOW),
                visibleFlow,
                combine(reorderingFlow, noticeFlow, reorderedFlow) { id, notice, lines ->
                    Work(id = id, notice = notice, reorderedLines = lines)
                }
            ) { orders, visible, work ->
                State(
                    loading = false,
                    orders = orders.filter { it.id.isNotBlank() },
                    visible = visible,
                    reorderingId = work.id,
                    notice = work.notice,
                    reorderedLines = work.reorderedLines
                )
            }.collect { next -> _state.value = next }
        }
    }

    private data class Work(val id: String?, val notice: String?, val reorderedLines: Int)

    fun loadMore() {
        visibleFlow.value = (visibleFlow.value + PAGE_SIZE).coerceAtMost(MAX_WINDOW.toInt())
    }

    /** Rebuilds the cart from a past order, skipping anything unavailable today (C-076). */
    fun reorder(orderId: String) {
        if (reorderingFlow.value != null) return
        reorderingFlow.value = orderId
        viewModelScope.launch {
            val snapshot = menuRepo.observeMenu().first()
            if (!snapshot.loaded) {
                noticeFlow.value = "The menu has not loaded yet. Check your connection and try again."
                reorderingFlow.value = null
                return@launch
            }
            when (val result = orderRepo.reorder(orderId, snapshot)) {
                is UndalResult.Success -> {
                    cartRepo.replaceLines(Cart(lines = result.value))
                    reorderedFlow.value = result.value.size
                    noticeFlow.value = orderRepo.lastReorderNote
                }

                is UndalResult.Failure -> noticeFlow.value = result.message
            }
            reorderingFlow.value = null
        }
    }

    fun dismissNotice() {
        noticeFlow.value = null
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val MAX_WINDOW = 50L
    }
}
