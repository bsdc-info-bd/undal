package com.undal.syl.app.features.vendor.orders

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.hilt.navigation.compose.hiltViewModel
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.model.VendorQueueFilter
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.features.vendor.VendorChip
import com.undal.syl.app.features.vendor.VendorEmptyState
import com.undal.syl.app.features.vendor.VendorOrderCard
import com.undal.syl.app.features.vendor.tick

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
    val state by viewModel.state.collectAsState()
    val haptics = LocalHapticFeedback.current
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(refreshing) {
        if (refreshing) {
            viewModel.refresh()
            kotlinx.coroutines.delay(600L)
            refreshing = false
        }
    }

    UndalScaffold(
        title = "Order queue",
        subtitle = "Newest waiting order first · taps open the order",
        onBack = onBack
    ) { _ ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true },
            modifier = Modifier.fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (state.fromCache) {
                    UndalAlertBanner(
                        text = "Reading the last synced queue — Firestore will send the writes when the " +
                            "signal returns.",
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

                if (state.visible.isEmpty() && !state.loading) {
                    VendorEmptyState(
                        icon = Icons.Filled.ShoppingCart,
                        title = "Nothing in ${state.filter.label.lowercase()}",
                        message = when (state.filter) {
                            VendorQueueFilter.NEW -> "No order is waiting for an answer. The alert loop is armed."
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
                        androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = UndalDimens.screenPadding,
                                end = UndalDimens.screenPadding,
                                top = UndalDimens.xs,
                                bottom = UndalDimens.xl
                            ),
                            verticalArrangement = Arrangement.spacedBy(UndalDimens.xs)
                        ) {
                            items(state.visible.size) { index ->
                                val order = state.visible[index]
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
