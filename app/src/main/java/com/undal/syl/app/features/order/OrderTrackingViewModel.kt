package com.undal.syl.app.features.order

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.undal.syl.app.core.cart.Cart
import com.undal.syl.app.core.config.UndalBuildInfo
import com.undal.syl.app.core.util.OrderStateMachine
import com.undal.syl.app.core.util.OrderStatus
import com.undal.syl.app.core.util.TimeDhaka
import com.undal.syl.app.core.util.UndalPhone
import com.undal.syl.app.core.util.UndalResult
import com.undal.syl.app.data.repo.CartRepo
import com.undal.syl.app.data.repo.ChatRepo
import com.undal.syl.app.data.repo.MenuRepo
import com.undal.syl.app.data.repo.OrderRepo
import com.undal.syl.app.core.background.NotifyRetryWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * One order, live (C-063 … C-076).
 *
 * Two pipes feed this screen and they have different jobs:
 *   · Firestore carries the order document — items, money, notes, timeline — and
 *     is written by the kitchen as it works,
 *   · the Realtime Database carries the `orderLive` status flag and the chat, so
 *     the stepper moves in under a second and messages land instantly.
 *
 * The screen shows the newest of the two, which means a status change is visible
 * even in the moment between the kitchen's RTDB write and its Firestore commit.
 */
private const val IDLE_TICK_MILLIS = 2_000L

@HiltViewModel
class OrderTrackingViewModel @Inject constructor(
    private val orderRepo: OrderRepo,
    private val chatRepo: ChatRepo,
    private val menuRepo: MenuRepo,
    private val cartRepo: CartRepo,
    @ApplicationContext private val context: Context
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val orderId: String = "",
        val detail: OrderRepo.OrderDetail? = null,
        val liveStatus: OrderStatus? = null,
        val displayStatus: OrderStatus = OrderStatus.PLACED,
        /**
         * The kitchen's words for a cancellation, from the order document or —
         * while that write is still in flight — from the live mirror that moved
         * the status first. Blank for every order that was not cancelled.
         */
        val cancelReason: String = "",
        val steps: List<OrderStatus> = OrderStateMachine.timeline,
        val stepIndex: Int = -1,
        val timelineRows: List<Pair<OrderStatus, String>> = emptyList(),
        val countdownLabel: String = "",
        val prepProgress: Float = 0f,
        val prepOverdue: Boolean = false,
        val placedAgoLabel: String = "",
        val messages: List<ChatRepo.Message> = emptyList(),
        val unread: Int = 0,
        val chatOpen: Boolean = false,
        val draft: String = "",
        val kitchenTyping: Boolean = false,
        val sending: Boolean = false,
        val reordering: Boolean = false,
        val reorderedLines: Int = 0,
        val notice: String? = null
    ) {

        /** The number the customer dials to reach the kitchen (C-072). */
        val kitchenPhone: String get() = UndalBuildInfo.supportPhone

        val canCallKitchen: Boolean get() = UndalPhone.isDialable(kitchenPhone)

        val isCancelled: Boolean get() = displayStatus == OrderStatus.CANCELLED

        val isDelivered: Boolean get() = displayStatus == OrderStatus.DELIVERED

        val isCooking: Boolean get() = displayStatus == OrderStatus.ACCEPTED

        val isActive: Boolean get() = OrderStateMachine.isActive(displayStatus)

        val itemCount: Int get() = detail?.items?.sumOf { it.quantity } ?: 0

        val sendEnabled: Boolean get() = draft.isNotBlank() && !sending

        val hasHistoryRows: Boolean get() = timelineRows.any { it.second.isNotBlank() }
    }

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
    private val guard = com.undal.syl.app.core.util.UndalGuards.viewModel("following your order") { message ->
        noticeFlow.value = message
        _state.value = _state.value.copy(notice = message)
    }

    /**
     * The screen telling the ViewModel what it can know: whether it is on top.
     *
     * Called from the screen's own lifecycle, because a screen that is behind
     * another one is a screen nobody is reading.
     */
    fun onScreenVisible() {
        onScreenFlow.value = true
        // The customer has just arrived, so the labels they are about to read are
        // built from the time on the clock in this instant, not from the last
        // moment the ticker ran.
        tickFlow.value = System.currentTimeMillis()
    }

    fun onScreenHidden() {
        onScreenFlow.value = false
    }

    private var boundOrderId: String = ""
    private val detailLoadedFlow = MutableStateFlow(false)
    private val detailFlow = MutableStateFlow<OrderRepo.OrderDetail?>(null)
    private val messagesFlow = MutableStateFlow<List<ChatRepo.Message>>(emptyList())
    private val lastReadFlow = MutableStateFlow(0L)
    private val typingFlow = MutableStateFlow(false)
    private val chatOpenFlow = MutableStateFlow(false)
    private val draftFlow = MutableStateFlow("")
    private val tickFlow = MutableStateFlow(System.currentTimeMillis())
    /**
     * Whether this screen is in front of the customer.
     *
     * The countdown labels it wakes for are only read on screen, so a phone with
     * this screen on the back stack — or in the customer's pocket — has no reason
     * to run a one-second clock. The screen reports its own lifecycle; the ticker
     * below is the only thing that listens.
     */
    private val onScreenFlow = MutableStateFlow(false)
    private val sendingFlow = MutableStateFlow(false)
    private val reorderingFlow = MutableStateFlow(false)
    private val noticeFlow = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch(guard) {
            while (isActive) {
                // Three speeds, on purpose — and the slowest of them is "nobody is
                // looking".
                //
                // While the customer has this screen open and the kitchen or the
                // rider still has the order, the prep countdown and the "waiting
                // 4 min" badges are the reason the screen exists, and a one-second
                // tick is what makes them true. Once the order is delivered or
                // cancelled the only thing left moving is a chat that is usually
                // closed, so the clock slows to a quarter of a minute. And while
                // the screen is not on top — the customer is on the menu, in the
                // cart, or the phone is in a pocket — nothing on it is being read,
                // so the ticker stops touching the clock altogether and waits for
                // the screen to come back: a phone that polled once a second
                // behind a closed screen woke the CPU 86,400 times a day to
                // repaint a countdown no one could see.
                //
                // The order itself is not missed while it waits: the status
                // arrives on its own listeners (Firestore and `orderLive`), and
                // `onScreenVisible()` reads the clock the moment the customer
                // comes back, so the first frame they see is current.
                if (!onScreenFlow.value) {
                    delay(IDLE_TICK_MILLIS)
                    continue
                }
                val finished = _state.value.isDelivered || _state.value.isCancelled
                delay(if (finished) 15_000L else 1_000L)
                tickFlow.value = System.currentTimeMillis()
            }
        }
        viewModelScope.launch(guard) {
            combine(
                detailFlow,
                messagesFlow,
                lastReadFlow,
                combine(chatOpenFlow, draftFlow, typingFlow, tickFlow) { open, draft, typing, tick ->
                    ChatView(open = open, draft = draft, typing = typing, tick = tick)
                },
                combine(sendingFlow, reorderingFlow, noticeFlow, detailLoadedFlow) { sending, reordering, notice, loaded ->
                    WorkView(sending = sending, reordering = reordering, notice = notice, detailLoaded = loaded)
                }
            ) { detail, messages, lastRead, chat, work ->
                buildState(detail, messages, lastRead, chat, work)
            }.collect { next -> _state.value = next }
        }
    }

    private data class ChatView(val open: Boolean, val draft: String, val typing: Boolean, val tick: Long)

    private data class WorkView(val sending: Boolean, val reordering: Boolean, val notice: String?, val detailLoaded: Boolean)

    fun bind(orderId: String) {
        if (boundOrderId == orderId) return
        boundOrderId = orderId
        _state.value = _state.value.copy(orderId = orderId, loading = true)

        viewModelScope.launch(guard) {
            orderRepo.observeOrder(orderId).collect { detail ->
                detailFlow.value = detail
                detailLoadedFlow.value = true
                if (detail != null) {
                    // The moment the screen opens, this phone has seen the chat.
                    markReadFor(messagesFlow.value)
                }
            }
        }
        viewModelScope.launch(guard) {
            chatRepo.observeMessages(orderId).collect { messages ->
                messagesFlow.value = messages
                if (chatOpenFlow.value) markReadFor(messages)
            }
        }
        viewModelScope.launch(guard) {
            chatRepo.lastReadFlow(orderId).collect { lastReadFlow.value = it }
        }
        viewModelScope.launch(guard) {
            chatRepo.observeKitchenTyping(orderId).collect { typingFlow.value = it }
        }
    }

    private fun buildState(
        detail: OrderRepo.OrderDetail?,
        messages: List<ChatRepo.Message>,
        lastRead: Long,
        chat: ChatView,
        work: WorkView
    ): State {
        val liveStatus = detail?.liveStatus?.let { OrderStatus.fromWire(it) }
        // The further of the two, never the older of the two: a cancellation that
        // the mirror carried first is still a cancellation, and a stale mirror
        // saying "accepted" must never drag a cancelled order back to life.
        val display = OrderStateMachine.newest(
            firestoreStatus = detail?.status ?: OrderStatus.PLACED,
            liveStatus = liveStatus
        )
        val cancelled = display == OrderStatus.CANCELLED

        // Where the order stopped: for a cancelled order the last step that was
        // actually reached, so the note sits next to the right dot.
        val stepIndex = when {
            cancelled -> detail?.timeline
                ?.map { it.first }
                ?.lastOrNull { it != OrderStatus.CANCELLED }
                ?.let { OrderStateMachine.stepIndex(it) } ?: 0
            else -> OrderStateMachine.stepIndex(display)
        }.coerceAtLeast(0)

        val rows = OrderStateMachine.timeline.map { step ->
            step to (detail?.timeline?.lastOrNull { it.first == step }?.second.orEmpty())
        }

        val prepEndsAt = detail?.prepEndsAt
        val prepMinutes = detail?.prepMinutes
        val now = Instant.ofEpochMilli(chat.tick)
        val startMillis = if (prepEndsAt != null && prepMinutes != null) {
            prepEndsAt.toEpochMilli() - prepMinutes * 60_000L
        } else {
            0L
        }
        val span = if (prepEndsAt != null) (prepEndsAt.toEpochMilli() - startMillis).coerceAtLeast(1L) else 1L

        return State(
            loading = !work.detailLoaded,
            orderId = boundOrderId,
            detail = detail,
            liveStatus = liveStatus,
            displayStatus = display,
            cancelReason = detail?.cancelNote.orEmpty().ifBlank { detail?.liveNote.orEmpty() },
            stepIndex = stepIndex,
            timelineRows = rows,
            countdownLabel = if (prepEndsAt != null) TimeDhaka.countdown(prepEndsAt, now) else "",
            prepProgress = if (prepEndsAt != null && startMillis > 0L) {
                ((chat.tick - startMillis).toFloat() / span.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            },
            prepOverdue = TimeDhaka.isPrepOverdue(prepEndsAt, now),
            placedAgoLabel = TimeDhaka.relativeFromNow(detail?.createdAt),
            messages = messages,
            unread = if (chat.open) 0 else chatRepo.unreadCount(messages, lastRead),
            chatOpen = chat.open,
            draft = chat.draft,
            kitchenTyping = chat.typing,
            sending = work.sending,
            reordering = work.reordering,
            reorderedLines = _state.value.reorderedLines,
            notice = work.notice
        )
    }

    // ------------------------------------------------------------------- chat
    fun openChat() {
        chatOpenFlow.value = true
        markReadFor(messagesFlow.value)
    }

    fun closeChat() {
        chatOpenFlow.value = false
        viewModelScope.launch(guard) { chatRepo.setCustomerTyping(boundOrderId, false) }
    }

    fun onDraftChange(value: String) {
        val next = value.take(ChatRepo.MAX_MESSAGE_LENGTH)
        draftFlow.value = next
        // One write on the first keystroke, cleared on send or when the sheet
        // closes: enough for "the customer is typing", without a write per key.
        if (next.length == 1) {
            viewModelScope.launch(guard) { chatRepo.setCustomerTyping(boundOrderId, true) }
        }
    }

    fun send() {
        val body = draftFlow.value.trim()
        if (body.isEmpty() || sendingFlow.value) return
        sendingFlow.value = true
        viewModelScope.launch(guard) {
            when (val result = chatRepo.send(boundOrderId, body)) {
                is UndalResult.Success -> {
                    draftFlow.value = ""
                    noticeFlow.value = null
                    // The kitchen must be told even if their console is closed.
                    NotifyRetryWorker.enqueue(
                        context = context,
                        orderId = boundOrderId,
                        kind = NotifyRetryWorker.KIND_CHAT,
                        text = body.take(120)
                    )
                }

                is UndalResult.Failure -> noticeFlow.value = result.message
            }
            sendingFlow.value = false
        }
    }

    private fun markReadFor(messages: List<ChatRepo.Message>) {
        if (boundOrderId.isBlank()) return
        val newest = messages.maxOfOrNull { it.tsMillis } ?: 0L
        val stamp = maxOf(System.currentTimeMillis(), newest)
        viewModelScope.launch(guard) { chatRepo.markRead(boundOrderId, stamp) }
    }

    // ---------------------------------------------------------------- reorder
    /** Rebuilds the cart from this order, skipping dishes that are gone (C-076). */
    fun reorder() {
        if (reorderingFlow.value) return
        reorderingFlow.value = true
        viewModelScope.launch(guard) {
            val snapshot = menuRepo.observeMenu().first()
            if (!snapshot.loaded) {
                noticeFlow.value = "The menu has not loaded yet. Check your connection and try again."
                reorderingFlow.value = false
                return@launch
            }
            when (val result = orderRepo.reorder(boundOrderId, snapshot)) {
                is UndalResult.Success -> {
                    cartRepo.replaceLines(Cart(lines = result.value))
                    _state.value = _state.value.copy(reorderedLines = result.value.size)
                    noticeFlow.value = orderRepo.lastReorderNote
                }

                is UndalResult.Failure -> noticeFlow.value = result.message
            }
            reorderingFlow.value = false
        }
    }

    fun dismissNotice() {
        noticeFlow.value = null
    }
}
