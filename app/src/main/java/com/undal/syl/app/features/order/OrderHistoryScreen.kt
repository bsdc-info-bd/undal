package com.undal.syl.app.features.order

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
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
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.reorderedLines) { if (state.reorderedLines > 0) onOpenCart() }

    UndalScaffold(
        title = "Your orders",
        subtitle = if (state.hasAnything) "${state.orders.size} order(s) on this account" else null,
        onBack = onBack
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = UndalDimens.screenPadding)
        ) {
            if (state.notice != null) {
                Spacer(Modifier.height(UndalDimens.sm))
                UndalAlertBanner(
                    text = state.notice.orEmpty(),
                    icon = Icons.Filled.Info,
                    action = { TextButton(onClick = viewModel::dismissNotice) { Text("Close") } }
                )
            }

            if (state.loading) {
                Spacer(Modifier.height(UndalDimens.lg))
                Text("Reading your orders…", style = MaterialTheme.typography.bodyLarge)
                return@Column
            }

            if (!state.hasAnything) {
                Spacer(Modifier.height(UndalDimens.xxl))
                UndalEmptyState(
                    icon = Icons.Filled.ShoppingCart,
                    title = "No orders yet",
                    message = "When you place your first order it appears here, with its receipt and a " +
                        "one-tap reorder for next time.",
                    actionLabel = "Browse the menu",
                    onAction = onBrowseMenu
                )
                return@Column
            }

            if (state.active.isNotEmpty()) {
                Spacer(Modifier.height(UndalDimens.lg))
                UndalSectionHeader(title = "In progress", subtitle = "Orders the kitchen is working on now")
                Spacer(Modifier.height(UndalDimens.sm))
                state.active.forEach { order ->
                    OrderHistoryRow(
                        order = order,
                        reordering = state.reorderingId == order.id,
                        onOpen = { onOpenOrder(order.id) },
                        onReorder = { viewModel.reorder(order.id) }
                    )
                    Spacer(Modifier.height(UndalDimens.sm))
                }
            }

            if (state.past.isNotEmpty()) {
                Spacer(Modifier.height(UndalDimens.lg))
                UndalSectionHeader(
                    title = "Past orders",
                    subtitle = "Delivered or cancelled, newest first"
                )
                Spacer(Modifier.height(UndalDimens.sm))
                state.past.forEach { order ->
                    OrderHistoryRow(
                        order = order,
                        reordering = state.reorderingId == order.id,
                        onOpen = { onOpenOrder(order.id) },
                        onReorder = { viewModel.reorder(order.id) }
                    )
                    Spacer(Modifier.height(UndalDimens.sm))
                }
            }

            if (state.canLoadMore) {
                Spacer(Modifier.height(UndalDimens.sm))
                UndalTextButton(
                    text = "Show 20 more",
                    onClick = viewModel::loadMore,
                    modifier = Modifier.padding(vertical = UndalDimens.xs)
                )
            }

            Spacer(Modifier.height(UndalDimens.xxl))
        }
    }
}

@Composable
private fun OrderHistoryRow(
    order: OrderRepo.OrderSummary,
    reordering: Boolean,
    onOpen: () -> Unit,
    onReorder: () -> Unit
) {
    UndalCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = order.shortId.ifBlank { "Order" },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = summaryLine(order),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OrderStatusPill(order.status)
        }
        Spacer(Modifier.height(UndalDimens.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = historyStamp(order),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(UndalDimens.sm))
            Text(
                text = Money.format(order.totalAmountPaisa),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.weight(1f))
            if (!order.isActive) {
                UndalTextButton(
                    text = if (reordering) "Rebuilding…" else "Reorder",
                    onClick = onReorder,
                    icon = Icons.Filled.Refresh,
                    enabled = !reordering
                )
            } else {
                UndalTextButton(text = "Track", onClick = onOpen)
            }
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
