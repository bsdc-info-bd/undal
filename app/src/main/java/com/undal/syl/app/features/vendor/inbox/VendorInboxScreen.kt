package com.undal.syl.app.features.vendor.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.design.UndalTextButton
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.core.util.TimeDhaka
import com.undal.syl.app.features.vendor.VendorEmptyState
import java.time.Instant

/**
 * The kitchen's alert history (V-109).
 *
 * Newest first, unread first within reach of a thumb, and every row knows which
 * order it came from — so "when did that order come in?" is answered by opening
 * the app rather than scrolling a notification shade.
 */
@Composable
fun VendorInboxScreen(
    onBack: () -> Unit,
    onOpenOrder: (String) -> Unit,
    viewModel: VendorInboxViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    UndalScaffold(
        title = "Kitchen history",
        subtitle = if (state.unread == 0) {
            "Every order this phone rang for, newest first"
        } else {
            "${state.unread} unread alert(s) · ${state.items.size} in total"
        },
        onBack = onBack,
        actions = {
            if (state.items.any { !it.read }) {
                UndalTextButton(
                    text = "Mark all read",
                    onClick = viewModel::markAllRead,
                    icon = Icons.Filled.Check
                )
            }
        }
    ) { _ ->
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.items.isEmpty() && !state.loading) {
                VendorEmptyState(
                    icon = Icons.Filled.Email,
                    title = "No alert recorded yet",
                    message = "The moment a new order rings this phone it is written here, with the order " +
                        "number and the amount. Losing the notification shade never loses this.",
                    actionLabel = null,
                    onAction = null
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(UndalDimens.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(UndalDimens.xs)
                ) {
                    items(state.items, key = { it.id }) { item ->
                        UndalCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                viewModel.markRead(item.id)
                                item.orderId?.let(onOpenOrder)
                            }
                        ) {
                            Column(modifier = Modifier.padding(UndalDimens.sm)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (!item.read) {
                                        Text(
                                            text = "New",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(Modifier.height(UndalDimens.xs))
                                Text(text = item.body, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(UndalDimens.xs))
                                Text(
                                    text = TimeDhaka.formatInstantDate(Instant.ofEpochMilli(item.createdAtMillis)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (item.orderId != null) {
                                    Spacer(Modifier.height(UndalDimens.xs))
                                    UndalTextButton(
                                        text = "Open order",
                                        onClick = { onOpenOrder(item.orderId) }
                                    )
                                }
                            }
                        }
                    }
                }
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
