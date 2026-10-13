package com.undal.syl.app.features.portal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.undal.syl.app.core.background.OrderAlertController
import com.undal.syl.app.core.util.OrderStateMachine
import com.undal.syl.app.core.util.UndalResult
import com.undal.syl.app.data.repo.OrderStatusRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The ringing order screen (V-024 … V-034).
 *
 * This screen is what the kitchen sees when the phone wakes up: the order, the
 * customer, the exact cash amount, and the one decision that matters — accept
 * with a prep promise, or reject with a reason the customer will read.
 */
@HiltViewModel
class VendorOrderViewModel @Inject constructor(
    private val repo: OrderStatusRepo
) : ViewModel() {

    data class State(
        val order: OrderStatusRepo.OrderSnapshot? = null,
        val loading: Boolean = true,
        val busy: Boolean = false,
        val message: String? = null,
        val messageIsError: Boolean = false,
        val showAcceptSheet: Boolean = false,
        val showRejectSheet: Boolean = false,
        val showReadyConfirm: Boolean = false,
        val showDeliveredConfirm: Boolean = false,
        val selectedPrepMinutes: Int = 15,
        val customPrepMinutes: String = "",
        val rejectReason: String = "",
        val rejectTemplate: String? = null,
        val rejectError: String? = null,
        val collectedInput: String = ""
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var orderId: String = ""

    fun bind(orderId: String) {
        if (this.orderId == orderId) return
        this.orderId = orderId
        viewModelScope.launch {
            repo.observeOrder(orderId).collectLatest { snapshot ->
                _state.value = _state.value.copy(
                    order = snapshot,
                    loading = false,
                    collectedInput = snapshot?.let { it.totalAmountPaisa.toString() } ?: ""
                )
            }
        }
    }

    /** Opens the accept sheet when the alert asked for it (deep-link sheet param). */
    fun openSheetFor(sheet: String?) {
        when (sheet) {
            "accept" -> _state.value = _state.value.copy(showAcceptSheet = true)
            "reject" -> _state.value = _state.value.copy(showRejectSheet = true)
            "ready" -> _state.value = _state.value.copy(showReadyConfirm = true)
            else -> Unit
        }
    }

    fun dismissSheets() {
        _state.value = _state.value.copy(
            showAcceptSheet = false,
            showRejectSheet = false,
            showReadyConfirm = false,
            showDeliveredConfirm = false
        )
    }

    fun openAcceptSheet() {
        _state.value = _state.value.copy(showAcceptSheet = true)
    }

    fun openRejectSheet() {
        _state.value = _state.value.copy(showRejectSheet = true)
    }

    fun openReadyConfirm() {
        _state.value = _state.value.copy(showReadyConfirm = true)
    }

    fun selectPrepMinutes(minutes: Int) {
        _state.value = _state.value.copy(selectedPrepMinutes = minutes, customPrepMinutes = "")
    }

    fun onCustomPrepChange(value: String) {
        val digits = value.filter { it.isDigit() }.take(3)
        _state.value = _state.value.copy(customPrepMinutes = digits)
    }

    fun effectivePrepMinutes(): Int {
        val custom = _state.value.customPrepMinutes.toIntOrNull()
        return if (custom != null && custom > 0) custom else _state.value.selectedPrepMinutes
    }

    fun confirmAccept() {
        val minutes = effectivePrepMinutes()
        if (minutes !in 1..180) {
            _state.value = _state.value.copy(message = "Choose a prep time between 1 and 180 minutes.", messageIsError = true)
            return
        }
        run {
            _state.value = _state.value.copy(busy = true, message = null)
            viewModelScope.launch {
                when (val result = repo.accept(orderId, minutes)) {
                    is UndalResult.Success -> {
                        OrderAlertController.stop(orderId)
                        _state.value = _state.value.copy(
                            busy = false,
                            showAcceptSheet = false,
                            message = "Accepted. The customer has been told to expect it in $minutes minutes.",
                            messageIsError = false
                        )
                    }

                    is UndalResult.Failure -> _state.value = _state.value.copy(
                        busy = false,
                        message = result.message,
                        messageIsError = true
                    )
                }
            }
        }
    }

    fun onRejectTemplateChange(template: String) {
        _state.value = _state.value.copy(
            rejectTemplate = template,
            rejectReason = if (_state.value.rejectReason.isBlank()) template else _state.value.rejectReason,
            rejectError = null
        )
    }

    fun onRejectReasonChange(value: String) {
        _state.value = _state.value.copy(rejectReason = value, rejectError = null)
    }

    fun confirmReject() {
        val reason = _state.value.rejectReason.trim()
        if (reason.length < 3) {
            _state.value = _state.value.copy(rejectError = "The customer reads this note, so please explain in a few words.")
            return
        }
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            when (val result = repo.reject(orderId, reason)) {
                is UndalResult.Success -> {
                    OrderAlertController.stop(orderId)
                    _state.value = _state.value.copy(
                        busy = false,
                        showRejectSheet = false,
                        message = "Order cancelled. The customer has been sent your note.",
                        messageIsError = false
                    )
                }

                is UndalResult.Failure -> _state.value = _state.value.copy(
                    busy = false,
                    message = result.message,
                    messageIsError = true
                )
            }
        }
    }

    fun confirmReady() {
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            when (val result = repo.markReady(orderId)) {
                is UndalResult.Success -> _state.value = _state.value.copy(
                    busy = false,
                    showReadyConfirm = false,
                    message = "Marked ready. Tell the rider, and the customer has been notified.",
                    messageIsError = false
                )

                is UndalResult.Failure -> _state.value = _state.value.copy(
                    busy = false,
                    message = result.message,
                    messageIsError = true
                )
            }
        }
    }

    fun markInDelivery() {
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            when (val result = repo.markInDelivery(orderId)) {
                is UndalResult.Success -> _state.value = _state.value.copy(
                    busy = false,
                    message = "On the way. The customer can follow the status live.",
                    messageIsError = false
                )

                is UndalResult.Failure -> _state.value = _state.value.copy(
                    busy = false,
                    message = result.message,
                    messageIsError = true
                )
            }
        }
    }

    fun onCollectedChange(value: String) {
        val cleaned = value.filter { it.isDigit() || it == '.' }.take(9)
        _state.value = _state.value.copy(collectedInput = cleaned)
    }

    /** Pre-fills the exact cash amount, so the cook confirms rather than types. */
    fun prefillCollected() {
        val total = _state.value.order?.totalAmountPaisa ?: return
        _state.value = _state.value.copy(
            collectedInput = com.undal.syl.app.core.util.Money.formatPlain(total)
        )
    }

    /** The amount typed is in taka ("585" or "585.50"); the cook never counts paisa. */
    fun confirmDelivered() {
        val order = _state.value.order ?: return
        val collectedPaisa = com.undal.syl.app.core.util.Money.parseTakaToPaisa(_state.value.collectedInput)
        if (!OrderStateMachine.isValidDelivered(collectedPaisa, order.totalAmountPaisa)) {
            _state.value = _state.value.copy(
                message = "The collected amount must match ${com.undal.syl.app.core.util.Money.format(order.totalAmountPaisa)} exactly.",
                messageIsError = true
            )
            return
        }
        val collected = collectedPaisa ?: return
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            when (val result = repo.markDelivered(orderId, collected)) {
                is UndalResult.Success -> _state.value = _state.value.copy(
                    busy = false,
                    showDeliveredConfirm = false,
                    message = "Delivered and the cash is recorded. Well done.",
                    messageIsError = false
                )

                is UndalResult.Failure -> _state.value = _state.value.copy(
                    busy = false,
                    message = result.message,
                    messageIsError = true
                )
            }
        }
    }

    fun openDeliveredSheet() {
        _state.value = _state.value.copy(showDeliveredConfirm = true)
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun availableActions(): List<String> {
        val status = _state.value.order?.status ?: return emptyList()
        return OrderStateMachine.allowedNext(status).map { it.wire }
    }
}
