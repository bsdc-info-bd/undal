package com.undal.syl.app.features.vendor.chats

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalPrimaryButton
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.design.UndalTextField
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
    val state by viewModel.state.collectAsState()
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
            order == null -> "Loading the order…"
            state.customerTyping -> "The customer is typing…"
            state.readAtMillis > 0L -> "They read your last message at " +
                TimeDhaka.formatInstant(Instant.ofEpochMilli(state.readAtMillis))

            else -> "${order.customerName.ifBlank { "Customer" }} · ${order.statusLabel}"
        },
        onBack = onBack,
        actions = {
            UndalTextButton(
                text = "Order",
                onClick = { onOpenOrder(orderId) },
                icon = Icons.Filled.ShoppingCart
            )
        }
    ) { _ ->
        Column(modifier = Modifier.fillMaxSize()) {
            if (order != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = UndalDimens.screenPadding, vertical = UndalDimens.xs)
                ) {
                    VendorStatusPill(status = order.status)
                    Spacer(Modifier.width(UndalDimens.xs))
                    Text(
                        text = order.linesSummary.ifBlank { "${order.itemCount} item(s)" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (state.messages.isEmpty()) {
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
                            text = "No message yet. Say when the food leaves, and tell them if it will be late.",
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

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(UndalDimens.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)
            ) {
                UndalTextField(
                    value = state.draft,
                    onValueChange = viewModel::onDraftChange,
                    label = "Message",
                    hint = "Text only — the rider is five minutes away.",
                    maxLines = 3,
                    modifier = Modifier.weight(1f)
                )
                UndalPrimaryButton(
                    text = "Send",
                    onClick = viewModel::send,
                    enabled = state.draft.isNotBlank() && !state.sending,
                    loading = state.sending,
                    icon = Icons.Filled.Email,
                    fillWidth = false
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(
                    start = UndalDimens.screenPadding,
                    end = UndalDimens.screenPadding,
                    bottom = UndalDimens.sm
                )
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(UndalDimens.iconSm)
                )
                Spacer(Modifier.width(UndalDimens.xs))
                Text(
                    text = "Messages are text only in this version, and both sides see the same thread.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        state.message?.let { message ->
            UndalAlertBanner(
                text = message,
                icon = Icons.Filled.Info,
                modifier = Modifier.fillMaxWidth().padding(UndalDimens.screenPadding)
            )
        }
    }
}

/** Message bubbles, with the day and the read mark written underneath. */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsOf(state: VendorChatViewModel.State) {
    state.messages.forEach { message ->
        item(key = message.id) {
            val mine = message.fromKitchen
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
                    if (message.tsMillis > 0L) {
                        Text(
                            text = TimeDhaka.formatInstantDate(Instant.ofEpochMilli(message.tsMillis)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
