package com.undal.syl.app.features.vendor.inbox

import androidx.compose.runtime.getValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.undal.syl.app.core.design.ShimmerListRow
import com.undal.syl.app.core.design.UndalSectionHeader
import com.undal.syl.app.core.design.UndalSecondaryButton
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
    val state by viewModel.state.collectAsStateWithLifecycle()

    UndalScaffold(
        title = "Kitchen history",
        subtitle = if (state.loading) "Reading saved alerts…" else "${state.unread} unread · ${state.items.size} saved",
        onBack = onBack, contentMaxWidth = 760.dp
    ) { _ ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(UndalDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(UndalDimens.md)
        ) {
            item(key = "history-intro") {
                UndalSectionHeader("Saved kitchen updates", subtitle = "Notification history is not proof an alarm sounded")
                if (state.unread > 0) {
                    UndalSecondaryButton("Mark all read", onClick = viewModel::markAllRead,
                        enabled = !state.busy, loading = state.busy, icon = Icons.Filled.Check)
                }
            }
            if (state.message != null) {
                item(key = "history-message") {
                    UndalAlertBanner(text = state.message.orEmpty(), icon = Icons.Filled.Info,
                        action = { UndalTextButton("Close", onClick = viewModel::dismissMessage) })
                }
            }
            if (state.loading) {
                items(3, key = { "loading-$it" }) { ShimmerListRow() }
            } else if (state.items.isEmpty()) {
                item(key = "history-empty") {
                    VendorEmptyState(icon = Icons.Filled.Email,
                        title = if (state.loadFailed) "History couldn't load" else "No saved alerts yet",
                        message = if (state.loadFailed) "Check your connection and reopen history to try again." else
                            "Saved kitchen notifications appear here when available. Use diagnostics to test this phone's alarm.",
                        actionLabel = null, onAction = null)
                }
            } else {
                    items(state.items, key = { "alert-${it.id}" }) { item ->
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
                                    text = if (item.createdAtMillis > 0L) TimeDhaka.formatInstantDate(Instant.ofEpochMilli(item.createdAtMillis)) else "Time unavailable",
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
}
