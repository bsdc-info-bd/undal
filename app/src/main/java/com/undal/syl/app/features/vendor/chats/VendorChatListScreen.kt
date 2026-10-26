@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.undal.syl.app.features.vendor.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.undal.syl.app.core.design.ShimmerListRow
import com.undal.syl.app.core.design.UndalSectionHeader
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.design.UndalStatusPill
import com.undal.syl.app.core.design.UndalTextButton
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.core.theme.undalSemantics
import com.undal.syl.app.features.vendor.VendorEmptyState
import com.undal.syl.app.features.vendor.VendorStatusPill
import com.undal.syl.app.features.vendor.tick

/**
 * Every conversation the kitchen has going (V-097, V-102, V-104).
 *
 * The list is ordered by what needs a reply: anything with an unread customer
 * message first, then the freshest thread. A row taps into the chat; a second
 * button jumps straight to the order behind it.
 */
@Composable
fun VendorChatListScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenOrder: (String) -> Unit,
    viewModel: VendorChatListViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    UndalScaffold(
        title = "Chats",
        subtitle = "Text only, one thread per order · ${state.rows.size} thread(s) in the last week",
        onBack = onBack, contentMaxWidth = 760.dp
    ) { _ ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(UndalDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(UndalDimens.md)
        ) {
            item(key = "chat-intro") {
                UndalSectionHeader("Conversations", subtitle = "${state.rows.count { it.unread > 0 }} thread(s) with unread messages · unread first")
            }
            if (state.fromCache) {
                item(key = "cache") { UndalAlertBanner("Showing saved threads. Reconnect to check for new messages.", icon = Icons.Filled.Info) }
            }
            if (state.notice != null) {
                item(key = "notice") { UndalAlertBanner(state.notice.orEmpty(), icon = Icons.Filled.Info) }
            }
            if (state.loading && state.rows.isEmpty()) {
                items(3, key = { "loading-$it" }) { ShimmerListRow() }
            } else if (state.rows.isEmpty()) {
                item(key = "empty") {
                    VendorEmptyState(icon = Icons.Filled.Email,
                        title = if (state.notice != null) "Conversations unavailable" else "No conversations yet",
                        message = if (state.notice != null) "Check your connection and reopen chats to try again." else
                            "Order conversations appear here with their unread count when available.",
                        actionLabel = null, onAction = null)
                }
            } else {

                    items(state.rows, key = { "thread-${it.order.id}" }) { row ->
                        UndalCard(modifier = Modifier.fillMaxWidth(), onClick = {
                            haptics.tick()
                            onOpenChat(row.order.id)
                        }) {
                            Column(modifier = Modifier.padding(UndalDimens.sm)) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(UndalDimens.sm),
                                    verticalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                                    Text(row.order.shortId.ifBlank { row.order.id.takeLast(4) }, style = MaterialTheme.typography.titleMedium)
                                    VendorStatusPill(status = row.order.status)
                                }
                                Text(row.order.customerName.ifBlank { "Customer" }, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(UndalDimens.xs))
                                Text(
                                    text = when {
                                        row.lastText.isBlank() && row.order.isOpen ->
                                            "No message yet — the order is still open."

                                        row.lastText.isBlank() -> "The thread ended quietly."
                                        row.lastFromKitchen -> "You: ${row.lastText}"
                                        else -> row.lastText
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(UndalDimens.xs))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(UndalDimens.sm),
                                    verticalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                                    if (row.unread > 0) {
                                        UndalStatusPill(
                                            text = "${row.unread} unread",
                                            containerColour = MaterialTheme.undalSemantics.closedContainer,
                                            contentColour = MaterialTheme.undalSemantics.onClosedContainer
                                        )
                                    } else {
                                        Text(
                                            text = "No unread message",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    UndalTextButton(
                                        text = "Open the order",
                                        onClick = { onOpenOrder(row.order.id) },
                                        icon = Icons.Filled.ShoppingCart
                                    )
                                }
                            }
                        }
                    }

            }
        }
    }
}
