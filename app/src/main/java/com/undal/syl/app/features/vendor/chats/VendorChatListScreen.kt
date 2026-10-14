package com.undal.syl.app.features.vendor.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
    val state by viewModel.state.collectAsState()
    val haptics = LocalHapticFeedback.current

    UndalScaffold(
        title = "Chats",
        subtitle = "Text only, one thread per order · ${state.rows.size} thread(s) in the last week",
        onBack = onBack
    ) { _ ->
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.fromCache) {
                UndalAlertBanner(
                    text = "Showing the threads this phone already knows about. New messages arrive as soon " +
                        "as the signal does.",
                    icon = Icons.Filled.Info,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (state.rows.isEmpty() && !state.loading) {
                VendorEmptyState(
                    icon = Icons.Filled.Email,
                    title = "No conversation yet",
                    message = "When a customer writes about an order, the thread appears here with an unread " +
                        "count — and the push lands on this phone.",
                    actionLabel = null,
                    onAction = null
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(UndalDimens.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(UndalDimens.xs)
                ) {
                    items(state.rows, key = { it.order.id }) { row ->
                        UndalCard(modifier = Modifier.fillMaxWidth(), onClick = {
                            haptics.tick()
                            onOpenChat(row.order.id)
                        }) {
                            Column(modifier = Modifier.padding(UndalDimens.sm)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = row.order.shortId.ifBlank { row.order.id.takeLast(4) },
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Spacer(Modifier.width(UndalDimens.xs))
                                    Text(
                                        text = row.order.customerName.ifBlank { "Customer" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    VendorStatusPill(status = row.order.status)
                                }
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
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
                                    Spacer(Modifier.weight(1f))
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
}
