package com.undal.syl.app.features.vendor.orders

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.undal.syl.app.core.background.NotifyRetryWorker
import com.undal.syl.app.core.model.VendorOrder
import com.undal.syl.app.core.net.WorkerApi
import com.undal.syl.app.core.util.Money
import com.undal.syl.app.core.util.OrderStateMachine
import com.undal.syl.app.core.util.UndalResult
import com.undal.syl.app.data.repo.ChatRepo
import com.undal.syl.app.data.repo.OrderStatusRepo
import com.undal.syl.app.data.repo.VendorOrderRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One order, all the way through the kitchen (V-025 … V-040, V-043 … V-048).
 *
 * The state machine decides which buttons exist: an order that is already out
 * for delivery shows no "Ready?" button at all, because an impossible tap should
 * not be possible in the first place. Every transition goes through
 * [OrderStatusRepo], which writes one Firestore transaction and queues the
 * customer's push behind WorkManager.
 */
@HiltViewModel
class VendorOrderDetailViewModel @Inject constructor(
    private val vendorOrderRepo: VendorOrderRepo,
    private val orderStatusRepo: OrderStatusRepo,
    private val chatRepo: ChatRepo,
    private val workerApi: WorkerApi,
    @ApplicationContext private val context: Context
) : ViewModel() {

    sealed interface Sheet {
        data object None : Sheet
        data object Accept : Sheet
        data object Reject : Sheet
        data object Ready : Sheet
        data object Delivered : Sheet
    }

    data class State(
        val loading: Boolean = true,
        val order: VendorOrder? = null,
        val messages: List<ChatRepo.Message> = emptyList(),
        val messageText: String = "",
        val unread: Int = 0,
        val customerTyping: Boolean = false,
        val customerSeenAtMillis: Long = 0L,
        val sheet: Sheet = Sheet.None,
        val prepMinutes: Int = DEFAULT_PREP_MINUTES,
        val customPrepText: String = "",
        val rejectTemplate: String = "",
        val rejectNote: String = "",
        val collectedText: String = "",
        val busy: Boolean = false,
        val infoMessage: String? = null,
        val celebrate: Boolean = false,
        val chatOpen: Boolean = false,
        val overlayMap: Boolean = false,
        val shareText: String = "",
        val overdueNoticeSent: Boolean = false,
        val fromCache: Boolean = false,
        val customerPhotoUrl: String = ""
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now.asStateFlow()

    private var orderId: String = ""
    private var typingPublished = false

    init {
        viewModelScope.launch {
            while (true) {
                _now.value = System.currentTimeMillis()
                delay(1_000L)
            }
        }
    }

    fun bind(orderId: String, openChatInitially: Boolean = false) {
        if (this.orderId == orderId) return
        this.orderId = orderId
        _state.value = _state.value.copy(chatOpen = openChatInitially)

        viewModelScope.launch {
            vendorOrderRepo.observeOrder(orderId).collect { order ->
                _state.value = _state.value.copy(
                    loading = false,
                    order = order,
                    rejectTemplate = _state.value.rejectTemplate.ifBlank { REJECT_TEMPLATES.first() }
                )
                order?.let { current ->
                    escalateIfOverdue(current)
                    if (_state.value.customerPhotoUrl.isBlank() && current.customerId.isNotBlank()) {
                        val photo = vendorOrderRepo.customerPhoto(current.customerId).orEmpty()
                        _state.value = _state.value.copy(customerPhotoUrl = photo)
                    }
                }
            }
        }

        viewModelScope.launch {
            chatRepo.observeMessages(orderId).collect { messages ->
                val lastRead = chatRepo.vendorLastRead(orderId)
                val unread = if (_state.value.chatOpen) 0 else chatRepo.unreadFromCustomer(messages, lastRead)
                _state.value = _state.value.copy(messages = messages, unread = unread)
                if (_state.value.chatOpen) {
                    val newest = messages.maxOfOrNull { it.tsMillis } ?: 0L
                    if (newest > 0L) {
                        chatRepo.markVendorRead(orderId, newest)
                        chatRepo.publishRead(orderId, ChatRepo.SENDER_VENDOR, newest)
                    }
                }
            }
        }

        viewModelScope.launch {
            chatRepo.observeCustomerTyping(orderId).collect { typing ->
                _state.value = _state.value.copy(customerTyping = typing)
            }
        }

        viewModelScope.launch {
            chatRepo.observeReadMark(orderId, ChatRepo.SENDER_CUSTOMER).collect { seenAt ->
                _state.value = _state.value.copy(customerSeenAtMillis = seenAt)
            }
        }
    }

    // ------------------------------------------------------------------ sheets

    fun openSheet(sheet: Sheet) {
        if (sheet != Sheet.Accept && _state.value.sheet == Sheet.Accept) return
        _state.value = _state.value.copy(
            sheet = sheet,
            collectedText = if (sheet == Sheet.Delivered) {
                Money.formatPlain(_state.value.order?.totalAmountPaisa ?: 0L)
            } else {
                _state.value.collectedText
            }
        )
    }

    fun dismissSheet() {
        _state.value = _state.value.copy(sheet = Sheet.None, customPrepText = "", rejectNote = "")
    }

    fun onPrepSelected(minutes: Int) {
        _state.value = _state.value.copy(prepMinutes = minutes, customPrepText = "")
    }

    fun onCustomPrepChange(value: String) {
        val digits = value.filter { it.isDigit() }.take(3)
        _state.value = _state.value.copy(customPrepText = digits)
    }

    fun effectivePrepMinutes(): Int =
        _state.value.customPrepText.toIntOrNull() ?: _state.value.prepMinutes

    fun onRejectTemplate(template: String) {
        _state.value = _state.value.copy(rejectTemplate = template, rejectNote = template)
    }

    fun onRejectNote(value: String) {
        _state.value = _state.value.copy(rejectNote = value.take(280))
    }

    fun onCollectedChange(value: String) {
        _state.value = _state.value.copy(collectedText = value)
    }

    // -------------------------------------------------------------- transitions

    fun confirmAccept() {
        val minutes = effectivePrepMinutes()
        if (!OrderStateMachine.isValidAccept(
                prepMinutes = minutes,
                prepEndsAt = java.time.Instant.now().plusSeconds(minutes * 60L)
            )
        ) {
            _state.value = _state.value.copy(infoMessage = "Pick a prep time between 1 and 180 minutes.")
            return
        }
        transition("Accepted — the customer has been told.") {
            orderStatusRepo.accept(orderId, minutes)
        }
    }

    fun confirmReject() {
        val note = _state.value.rejectNote.trim()
        if (!OrderStateMachine.isValidCancel(note)) {
            _state.value = _state.value.copy(infoMessage = "Write at least a few words — the customer reads this.")
            return
        }
        transition("Cancelled. The customer has the reason and the cash was never taken.") {
            orderStatusRepo.reject(orderId, note)
        }
    }

    fun confirmReady() {
        transition("Marked ready. The customer's app now says the food is packed.") {
            orderStatusRepo.markReady(orderId)
        }
    }

    fun markInDelivery() {
        transition("On the way. The customer was pushed the moment it left.") {
            orderStatusRepo.markInDelivery(orderId)
        }
    }

    fun confirmDelivered() {
        val order = _state.value.order ?: return
        val collected = Money.parseTakaToPaisa(_state.value.collectedText)
        if (collected == null) {
            _state.value = _state.value.copy(
                infoMessage = "Enter the cash you counted, like ${Money.formatPlain(order.totalAmountPaisa)}."
            )
            return
        }
        if (!OrderStateMachine.isValidDelivered(collected, order.totalAmountPaisa)) {
            _state.value = _state.value.copy(
                infoMessage = "Cash on delivery must match ${Money.format(order.totalAmountPaisa)} exactly."
            )
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            when (val result = orderStatusRepo.markDelivered(orderId, collected)) {
                is UndalResult.Success -> _state.value = _state.value.copy(
                    busy = false,
                    sheet = Sheet.None,
                    celebrate = true,
                    infoMessage = "Delivered. ${Money.format(collected)} collected in cash."
                )

                is UndalResult.Failure -> _state.value = _state.value.copy(
                    busy = false,
                    infoMessage = result.message
                )
            }
        }
    }

    fun dismissCelebration() {
        _state.value = _state.value.copy(celebrate = false)
    }

    private fun transition(successMessage: String, block: suspend () -> UndalResult<Unit>) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val result = block()
            _state.value = _state.value.copy(
                busy = false,
                sheet = Sheet.None,
                infoMessage = when (result) {
                    is UndalResult.Success -> successMessage
                    is UndalResult.Failure -> result.message
                }
            )
        }
    }

    // --------------------------------------------------------------------- chat

    fun toggleChat() {
        val open = !_state.value.chatOpen
        _state.value = _state.value.copy(chatOpen = open, unread = if (open) 0 else _state.value.unread)
        if (open) {
            val newest = _state.value.messages.maxOfOrNull { it.tsMillis } ?: 0L
            viewModelScope.launch {
                chatRepo.markVendorRead(orderId, newest)
                chatRepo.publishRead(orderId, ChatRepo.SENDER_VENDOR, newest)
            }
        }
    }

    fun onMessageChange(value: String) {
        _state.value = _state.value.copy(messageText = value.take(ChatRepo.MAX_MESSAGE_LENGTH))
        val typing = value.isNotBlank()
        if (typing != typingPublished) {
            typingPublished = typing
            viewModelScope.launch { chatRepo.setVendorTyping(orderId, typing) }
        }
    }

    fun sendMessage() {
        val body = _state.value.messageText.trim()
        if (body.isEmpty()) return
        viewModelScope.launch {
            when (chatRepo.sendAsVendor(orderId, body)) {
                is UndalResult.Success -> {
                    _state.value = _state.value.copy(messageText = "")
                    typingPublished = false
                    // The customer's push goes through the Worker, queued with retry.
                    NotifyRetryWorker.enqueue(
                        context = context,
                        orderId = orderId,
                        kind = NotifyRetryWorker.KIND_CHAT,
                        text = body.take(120)
                    )
                }

                is UndalResult.Failure -> _state.value = _state.value.copy(infoMessage = "The message did not send.")
            }
        }
    }

    // -------------------------------------------------------------------- tools

    fun toggleMapOverlay() {
        _state.value = _state.value.copy(overlayMap = !_state.value.overlayMap)
    }

    fun readyShareText() {
        val order = _state.value.order ?: return
        _state.value = _state.value.copy(shareText = order.shareText())
    }

    fun copyCustomerPhone(): String = _state.value.order?.phone.orEmpty()

    fun clearShareText() {
        _state.value = _state.value.copy(shareText = "")
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(infoMessage = null)
    }

    /**
     * Overdue escalation (V-035): one extra push, once, so a phone that is in the
     * owner's pocket still tells them the customer is waiting.
     */
    private fun escalateIfOverdue(order: VendorOrder) {
        if (!order.isOverdue() || _state.value.overdueNoticeSent) return
        _state.value = _state.value.copy(overdueNoticeSent = true)
        viewModelScope.launch {
            runCatching {
                workerApi.notifyVendorSelf(
                    kind = "overdue",
                    title = "Order ${order.shortId.ifBlank { order.id.takeLast(4) }} is late",
                    body = "The promised prep time has passed. Mark it ready or tell the customer.",
                    orderId = order.id
                )
            }
        }
    }

    companion object {
        val PREP_CHOICES = listOf(10, 15, 20, 25, 30, 45)
        const val DEFAULT_PREP_MINUTES = 20

        val REJECT_TEMPLATES = listOf(
            "We ran out of an ingredient for this order.",
            "The kitchen is closed for a short break.",
            "We cannot reach your address tonight.",
            "Too many orders at once — we would serve you badly."
        )
    }
}
