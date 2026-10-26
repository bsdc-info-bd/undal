package com.undal.syl.app.features.order

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import com.undal.syl.app.core.design.ShimmerListRow
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalEmptyState
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.design.UndalSectionHeader
import com.undal.syl.app.core.design.UndalTextButton
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.core.theme.undalSemantics
import com.undal.syl.app.core.util.Money
import com.undal.syl.app.data.repo.OrderRepo

/**
 * Order history (C-075 · C-076).
 *
 * Active orders first, because that is what someone opening this screen wants to
 * see — then everything finished, newest first. Every row carries its own
 * reorder button, and a reorder that skipped a sold-out dish says so in words
 * rather than quietly handing over a smaller cart.
 */
@Composable
fun OrderHistoryScreen(
    onBack: () -> Unit,
    onOpenOrder: (String) -> Unit,
    onOpenCart: () -> Unit,
    onBrowseMenu: () -> Unit,
    viewModel: OrderHistoryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.reorderedLines) { if (state.reorderedLines > 0) onOpenCart() }

    UndalScaffold(
        title = "Your orders",
        subtitle = if (state.hasAnything) "${state.orders.size} order(s) on this account" else null,
        onBack = onBack
    ) { _ ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(UndalDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(UndalDimens.md)
        ) {
            if (state.notice != null) {
                item(key = "notice") {
                    UndalAlertBanner(text = state.notice.orEmpty(), icon = Icons.Filled.Info,
                        action = { TextButton(onClick = viewModel::dismissNotice) { Text("Close") } })
                }
            }
            if (state.loading) {
                item(key = "loading-label") { Text("Reading your orders…", style = MaterialTheme.typography.bodyLarge) }
                items(3, key = { "loading-$it" }) { ShimmerListRow() }
            } else if (!state.hasAnything) {
                item(key = "empty") {
                    UndalEmptyState(
                        icon = Icons.Filled.ShoppingCart,
                        title = if (state.loadFailed) "Couldn't read your orders" else "Your first favourite is waiting",
                        message = if (state.loadFailed) "Your history could not be loaded. Go back and reopen this page to try again." else
                            "Orders appear here with their status, receipt and a way to order again at today's prices.",
                        actionLabel = if (state.loadFailed) "Go back" else "Browse the menu",
                        onAction = if (state.loadFailed) onBack else onBrowseMenu
                    )
                }
            } else {
                if (state.active.isNotEmpty()) {
                    item(key = "active-heading") { UndalSectionHeader("Happening now", subtitle = "Open an order to follow its progress") }
                    items(state.active, key = { "active-${it.id}" }) { order ->
                        OrderHistoryRow(order, state.reorderingId == order.id, reorderEnabled = state.reorderingId == null,
                            onOpen = { onOpenOrder(order.id) }, onReorder = { viewModel.reorder(order.id) })
                    }
                }
                if (state.past.isNotEmpty()) {
                    item(key = "past-heading") { UndalSectionHeader("Your past favourites", subtitle = "Delivered and cancelled orders · newest first") }
                    items(state.past, key = { "past-${it.id}" }) { order ->
                        OrderHistoryRow(order, state.reorderingId == order.id, reorderEnabled = state.reorderingId == null,
                            onOpen = { onOpenOrder(order.id) }, onReorder = { viewModel.reorder(order.id) })
                    }
                }
                if (state.canLoadMore) {
                    item(key = "more") { UndalTextButton("Show more orders", onClick = viewModel::loadMore) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrderHistoryRow(
    order: OrderRepo.OrderSummary,
    reordering: Boolean,
    reorderEnabled: Boolean,
    onOpen: () -> Unit,
    onReorder: () -> Unit
) {
    UndalCard(onClick = onOpen) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(UndalDimens.sm),
            verticalArrangement = Arrangement.spacedBy(UndalDimens.sm)) {
            Text(order.shortId.ifBlank { "Order" }, style = MaterialTheme.typography.titleMedium)
            OrderStatusPill(order.status)
        }
        Spacer(Modifier.height(UndalDimens.sm))
        Text(summaryLine(order), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(historyStamp(order), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(UndalDimens.sm))
        Text(Money.format(order.totalAmountPaisa), style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary)
        if (order.isActive) {
            UndalTextButton("Track this order", onClick = onOpen)
        } else {
            UndalTextButton(if (reordering) "Rebuilding your basket…" else "Order again",
                onClick = onReorder, icon = Icons.Filled.Refresh, enabled = reorderEnabled && !reordering)
        }
        if (order.cancelNote.isNotBlank()) {
            Spacer(Modifier.height(UndalDimens.xs))
            Text(
                text = "Kitchen note: ${order.cancelNote}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.undalSemantics.closed
            )
        }
    }
}
