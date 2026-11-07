package com.undal.syl.app.features.vendor.chats

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.undal.syl.app.core.background.NotifyRetryWorker
import com.undal.syl.app.core.model.VendorOrder
import com.undal.syl.app.core.util.UndalResult
import com.undal.syl.app.data.repo.ChatRepo
import com.undal.syl.app.data.repo.VendorOrderRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One conversation, with the order behind it (V-098 … V-106).
 *
 * The draft lives in [SavedStateHandle], so a message half-typed survives a
 * rotation and even the process being killed in the background — the one place a
 * kitchen loses work for no reason. Nothing but text ever travels: the first
 * version of Undal chat has no attachments, which keeps the free tier free and
 * the thread readable.
 */
@HiltViewModel
class VendorChatViewModel @Inject constructor(
    private val chatRepo: ChatRepo,
    private val vendorOrderRepo: VendorOrderRepo,
    private val savedState: SavedStateHandle,
    @ApplicationContext private val context: Context
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val order: VendorOrder? = null,
        val messages: List<ChatRepo.Message> = emptyList(),
        val draft: String = "",
        val customerTyping: Boolean = false,
        val readAtMillis: Long = 0L,
        val firstUnreadIndex: Int = -1,
        val scrolledToUnread: Boolean = false,
        val sending: Boolean = false,
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
    private val guard = com.undal.syl.app.core.util.UndalGuards.viewModel("reading the kitchen's chats") { message ->
        _state.value = _state.value.copy(message = message, loading = false)
    }

    private var orderId: String = ""
    private var typingPublished = false

    fun bind(orderId: String) {
        if (this.orderId == orderId) return
        this.orderId = orderId
        _state.value = _state.value.copy(
            loading = true,
            draft = savedState.get<String>(draftKey(orderId)).orEmpty()
        )

        viewModelScope.launch(guard) {
            vendorOrderRepo.observeOrder(orderId).collect { order ->
                _state.value = _state.value.copy(loading = false, order = order)
            }
        }

        viewModelScope.launch(guard) {
            chatRepo.observeMessages(orderId).collect { messages ->
                val lastRead = chatRepo.vendorLastRead(orderId)
                val firstUnread = messages.indexOfFirst { !it.fromKitchen && it.tsMillis > lastRead }
                val state = _state.value
                _state.value = state.copy(
                    messages = messages,
                    firstUnreadIndex = if (state.firstUnreadIndex == -1) firstUnread else state.firstUnreadIndex
                )
                // Looking at the thread means reading it (V-101).
                val newest = messages.maxOfOrNull { it.tsMillis } ?: 0L
                if (newest > 0L) {
                    chatRepo.markVendorRead(orderId, newest)
                    chatRepo.publishRead(orderId, ChatRepo.SENDER_VENDOR, newest)
                }
            }
        }

        viewModelScope.launch(guard) {
            chatRepo.observeCustomerTyping(orderId).collect { typing ->
                _state.value = _state.value.copy(customerTyping = typing)
            }
        }

        viewModelScope.launch(guard) {
            chatRepo.observeReadMark(orderId, ChatRepo.SENDER_CUSTOMER).collect { seenAt ->
                _state.value = _state.value.copy(readAtMillis = seenAt)
            }
        }
    }

    fun onDraftChange(value: String) {
        if (_state.value.sending) return
        val body = value.take(ChatRepo.MAX_MESSAGE_LENGTH)
        _state.value = _state.value.copy(draft = body)
        savedState[draftKey(orderId)] = body
        val typing = body.isNotBlank()
        if (typing != typingPublished) {
            typingPublished = typing
            viewModelScope.launch(guard) { chatRepo.setVendorTyping(orderId, typing) }
        }
    }

    fun useQuickReply(reply: String) {
        onDraftChange(reply)
    }

    fun send() {
        val body = _state.value.draft.trim()
        if (body.isEmpty() || _state.value.sending || _state.value.order == null) return
        _state.value = _state.value.copy(sending = true)
        viewModelScope.launch(guard) {
            try {
                when (chatRepo.sendAsVendor(orderId, body)) {
                    is UndalResult.Success -> {
                        savedState[draftKey(orderId)] = ""
                        typingPublished = false
                        _state.value = _state.value.copy(sending = false, draft = "")
                        // The customer's push is queued with retry, never dropped silently (V-102).
                        NotifyRetryWorker.enqueue(
                            context = context,
                            orderId = orderId,
                            kind = NotifyRetryWorker.KIND_CHAT,
                            text = body.take(120)
                        )
                    }

                    is UndalResult.Failure -> _state.value = _state.value.copy(
                        sending = false,
                        message = "The message did not send. It is still in the box — try again."
                    )
                }
            } finally {
                _state.value = _state.value.copy(sending = false)
            }
        }
    }

    fun onScrolledToUnread() {
        _state.value = _state.value.copy(scrolledToUnread = true)
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun draftKey(orderId: String): String = "vendor_chat_draft_$orderId"

    companion object {
        val QUICK_REPLIES = listOf(
            "On the way",
            "Need ten more minutes",
            "Packed and waiting for the rider",
            "Your order is at the door"
        )
    }
}
