package com.undal.syl.app.features.vendor.orders

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.design.ShimmerListRow
import com.undal.syl.app.core.design.UndalSectionHeader
import com.undal.syl.app.core.design.UndalLayoutPolicy
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.model.VendorQueueFilter
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.features.vendor.VendorChip
import com.undal.syl.app.features.vendor.VendorEmptyState
import com.undal.syl.app.features.vendor.VendorOrderCard
import com.undal.syl.app.features.vendor.tick
import java.util.Locale

/**
 * The order queue with its six tabs (V-041 … V-047).
 *
 * Read-only on purpose: every state change happens inside the order, where the
 * confirmation sheets and the customer's phone both live. Nothing here can move
 * an order by accident.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VendorOrdersScreen(
    onBack: () -> Unit,
    onOpenOrder: (String) -> Unit,
    viewModel: VendorOrdersViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(refreshing) {
        if (!refreshing) return@LaunchedEffect
        // The indicator stops when the work stops, cancelled or not: a queued
        // order must never be hidden behind a spinner that cannot turn off.
        try {
            viewModel.refresh()
            kotlinx.coroutines.delay(350L)
        } finally {
            refreshing = false
        }
    }

    UndalScaffold(
        title = "Kitchen orders",
        contentMaxWidth = 1100.dp,
        subtitle = "Review an order to update its status",
        onBack = onBack
    ) { _ ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true },
            modifier = Modifier.fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                UndalSectionHeader(
                    title = if (state.loading) "Reading order queue…" else "${state.counts[VendorQueueFilter.NEW] ?: 0} waiting for an answer",
                    subtitle = "Select a stage below. Open an order for details and actions.",
                    modifier = Modifier.padding(horizontal = UndalDimens.screenPadding)
                )
                if (state.message != null) {
                    UndalAlertBanner(
                        text = state.message.orEmpty(), icon = Icons.Filled.Info,
                        containerColour = MaterialTheme.colorScheme.errorContainer,
                        contentColour = MaterialTheme.colorScheme.onErrorContainer,
                        action = { TextButton(onClick = { refreshing = true }) { Text("Retry") } }
                    )
                }
                if (state.fromCache) {
                    UndalAlertBanner(
                        text = "Showing the last synced queue. Reconnecting to check for newer orders.",
                        icon = Icons.Filled.Info,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = UndalDimens.screenPadding, vertical = UndalDimens.xs),
                    horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)
                ) {
                    VendorQueueFilter.entries.forEach { filter ->
                        val count = state.counts[filter] ?: 0
                        VendorChip(
                            label = if (count > 0) "${filter.label} · $count" else filter.label,
                            selected = state.filter == filter,
                            onClick = {
                                haptics.tick()
                                viewModel.onFilterChange(filter)
                            }
                        )
                    }
                }

                if (state.visible.isEmpty() && state.loading) {
                    Column(modifier = Modifier.padding(UndalDimens.screenPadding)) {
                        repeat(3) {
                            ShimmerListRow()
                            Spacer(Modifier.height(UndalDimens.sm))
                        }
                    }
                } else if (state.visible.isEmpty()) {
                    VendorEmptyState(
                        icon = Icons.Filled.ShoppingCart,
                        title = "Nothing in ${state.filter.label.lowercase(Locale.ENGLISH)}",
                        message = when (state.filter) {
                            VendorQueueFilter.NEW -> "No order is waiting for an answer. Check device readiness if alerts have been quiet."
                            VendorQueueFilter.PREPARING -> "Nothing is cooking right now."
                            VendorQueueFilter.READY -> "Nothing is packed and waiting for the rider."
                            VendorQueueFilter.OUT -> "No order is out for delivery."
                            VendorQueueFilter.DONE -> "No order has been delivered in the last seven days."
                            VendorQueueFilter.CANCELLED -> "No order was cancelled in the last seven days."
                        },
                        actionLabel = null,
                        onAction = null
                    )
                } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = (
                                if (UndalLayoutPolicy.useSplit(LocalConfiguration.current.screenWidthDp.toFloat(), LocalDensity.current.fontScale)) 420.dp else 1100.dp
                            )),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = UndalDimens.screenPadding,
                                end = UndalDimens.screenPadding,
                                top = UndalDimens.xs,
                                bottom = UndalDimens.xl
                            ),
                            verticalArrangement = Arrangement.spacedBy(UndalDimens.md),
                            horizontalArrangement = Arrangement.spacedBy(UndalDimens.md)
                        ) {
                            // Keyed by the order, not by the row: a new order
                            // arriving at the top must not hand this row's
                            // composition (and its age badge) to a different order.
                            items(state.visible, key = { it.id }) { order ->
                                VendorOrderCard(
                                    order = order,
                                    onClick = {
                                        haptics.tick()
                                        onOpenOrder(order.id)
                                    }
                                )
                            }
                        }
                }
            }
        }
    }
}
