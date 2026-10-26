@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.undal.syl.app.features.vendor.chats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.undal.syl.app.core.design.ShimmerListRow
import com.undal.syl.app.core.design.UndalMessageComposer
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.design.UndalTextButton
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.core.theme.undalSemantics
import com.undal.syl.app.core.util.TimeDhaka
import com.undal.syl.app.features.vendor.VendorChip
import com.undal.syl.app.features.vendor.VendorStatusPill
import com.undal.syl.app.features.vendor.tick
import java.time.Instant

/**
 * The kitchen's side of one conversation (V-098 … V-105).
 *
 * The first unread customer message is scrolled to when the thread opens, typing
 * shows on both sides through Realtime Database flags, and the read mark travels
 * back so the customer knows the kitchen saw the question.
 */
@Composable
fun VendorChatScreen(
    orderId: String,
    onBack: () -> Unit,
    onOpenOrder: (String) -> Unit,
    viewModel: VendorChatViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    var scrolled by remember { mutableStateOf(false) }

    LaunchedEffect(orderId) { viewModel.bind(orderId) }

    val order = state.order

    LaunchedEffect(state.messages.size, state.firstUnreadIndex) {
        if (scrolled || state.messages.isEmpty()) return@LaunchedEffect
        val target = if (state.firstUnreadIndex >= 0) state.firstUnreadIndex else state.messages.size - 1
        runCatching { listState.scrollToItem(target.coerceIn(0, (state.messages.size - 1).coerceAtLeast(0))) }
        scrolled = true
        viewModel.onScrolledToUnread()
    }

    UndalScaffold(
        title = "Chat · ${order?.shortId?.ifBlank { orderId.takeLast(4) } ?: "order"}",
        subtitle = when {
            order == null -> if (state.loading) "Reading the order…" else "Order unavailable"
            state.customerTyping -> "The customer is typing…"
            state.readAtMillis > 0L -> "They read your last message at " +
                TimeDhaka.formatInstant(Instant.ofEpochMilli(state.readAtMillis))

            else -> "${order.customerName.ifBlank { "Customer" }} · ${order.statusLabel}"
        },
        onBack = onBack,
        contentMaxWidth = 760.dp,
        actions = {
            UndalTextButton(
                text = "Order",
                onClick = { onOpenOrder(orderId) },
                icon = Icons.Filled.ShoppingCart
            )
        }
    ) { _ ->
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.message != null) {
                UndalAlertBanner(text = state.message.orEmpty(), icon = Icons.Filled.Info,
                    modifier = Modifier.padding(horizontal = UndalDimens.screenPadding),
                    action = { UndalTextButton("Close", onClick = viewModel::dismissMessage) })
            }
            if (state.loading) {
                ShimmerListRow(Modifier.padding(horizontal = UndalDimens.screenPadding))
            }
            if (order != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(UndalDimens.sm),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = UndalDimens.screenPadding, vertical = UndalDimens.xs)
                ) {
                    VendorStatusPill(status = order.status)
                    Spacer(Modifier.width(UndalDimens.xs))
                    Text(
                        text = order.linesSummary.ifBlank { "${order.itemCount} item(s)" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (state.messages.isEmpty() && !state.loading && state.message == null) {
                UndalCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = UndalDimens.screenPadding)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(UndalDimens.sm)
                    ) {
                        Icon(
                            Icons.Filled.Email,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(UndalDimens.iconMd)
                        )
                        Spacer(Modifier.width(UndalDimens.xs))
                        Text(
                            text = if (order == null) "The order is unavailable. Open it from the queue to check its status." else
                                "No messages yet. Keep the customer informed about their order.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(UndalDimens.screenPadding),
                verticalArrangement = Arrangement.spacedBy(UndalDimens.xs)
            ) {
                itemsOf(state)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = UndalDimens.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)
            ) {
                VendorChatViewModel.QUICK_REPLIES.forEach { reply ->
                    VendorChip(
                        label = reply,
                        selected = false,
                        onClick = {
                            haptics.tick()
                            viewModel.useQuickReply(reply)
                        }
                    )
                }
            }

            UndalMessageComposer(
                draft = state.draft, onDraftChange = viewModel::onDraftChange,
                onSend = viewModel::send, sending = state.sending, enabled = order != null,
                modifier = Modifier.padding(horizontal = UndalDimens.screenPadding, vertical = UndalDimens.sm)
            )
        }
    }
}

/** Message bubbles, with the day and the read mark written underneath. */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsOf(state: VendorChatViewModel.State) {
    state.messages.forEach { message ->
        item(key = message.id) {
            val mine = message.fromKitchen
            val bubbleContent = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            Surface(
                color = if (mine) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(UndalDimens.radiusMd),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(UndalDimens.sm)) {
                    Text(
                        text = if (mine) "Kitchen" else "Customer",
                        style = MaterialTheme.typography.labelSmall,
                        color = bubbleContent
                    )
                    Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
                    if (message.tsMillis > 0L) {
                        Text(
                            text = TimeDhaka.formatInstantDate(Instant.ofEpochMilli(message.tsMillis)),
                            style = MaterialTheme.typography.labelSmall,
                            color = bubbleContent
                        )
                    }
                }
            }
        }
    }
    if (state.customerTyping) {
        item(key = "typing") {
            Text(
                text = "Customer is typing…",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = UndalDimens.xs, bottom = UndalDimens.xs),
                color = MaterialTheme.undalSemantics.success
            )
        }
    }
    if (state.messages.isNotEmpty()) {
        item(key = "footnote") {
            Spacer(Modifier.height(UndalDimens.xs))
            Text(
                text = if (state.readAtMillis > 0L) {
                    "They last read this thread at " +
                        TimeDhaka.formatInstant(Instant.ofEpochMilli(state.readAtMillis)) + "."
                } else {
                    "They have not opened this thread since your last message."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
