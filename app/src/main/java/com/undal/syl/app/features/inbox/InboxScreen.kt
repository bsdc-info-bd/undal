package com.undal.syl.app.features.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalEmptyState
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.design.UndalSecondaryButton
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.core.util.TimeDhaka
import com.undal.syl.app.data.repo.NotifInboxRepo
import java.time.Instant

/**
 * Notification inbox (C-078).
 *
 * A push is gone the moment it is swiped; this page is where it lives on. Each
 * row keeps its picture, its timestamp and its read state, and an order row is a
 * live link into that order — which is why tapping one opens the tracking screen
 * rather than a text page.
 */
@Composable
fun InboxScreen(
    onBack: () -> Unit,
    onOpenOrder: (String) -> Unit,
    viewModel: InboxViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    UndalScaffold(
        title = "Notifications",
        subtitle = when {
            state.loading -> "Loading…"
            state.unread > 0 -> "${state.unread} unread"
            state.hasAnything -> "All caught up"
            else -> null
        },
        onBack = onBack,
        actions = {
            if (state.unread > 0) {
                TextButton(onClick = viewModel::markAllRead, enabled = !state.busy) {
                    Text("Mark all read")
                }
            }
        }
    ) { _ ->
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.notice != null) {
                Spacer(Modifier.height(UndalDimens.sm))
                UndalAlertBanner(
                    text = state.notice.orEmpty(),
                    icon = Icons.Filled.Warning,
                    modifier = Modifier.padding(horizontal = UndalDimens.screenPadding),
                    action = { TextButton(onClick = viewModel::clearNotice) { Text("Close") } }
                )
            }

            if (!state.loading && !state.hasAnything) {
                Spacer(Modifier.height(UndalDimens.lg))
                UndalEmptyState(
                    icon = Icons.Filled.Notifications,
                    title = "No notifications yet",
                    message = "Order updates, kitchen messages and offers land here — and stay here, even " +
                        "after you swipe the push away.",
                    modifier = Modifier.padding(horizontal = UndalDimens.screenPadding)
                )
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = UndalDimens.screenPadding,
                    end = UndalDimens.screenPadding,
                    top = UndalDimens.sm,
                    bottom = UndalDimens.xxl
                ),
                verticalArrangement = Arrangement.spacedBy(UndalDimens.sm)
            ) {
                items(state.items, key = { it.id }) { item ->
                    InboxRow(
                        item = item,
                        onOpen = { viewModel.open(item, onOpenOrder) }
                    )
                }
            }
        }
    }
}

@Composable
private fun InboxRow(
    item: NotifInboxRepo.InboxItem,
    onOpen: () -> Unit
) {
    UndalCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.Top) {
            if (!item.imageUrl.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(UndalDimens.radiusSm)
                        )
                ) {
                    AsyncImage(
                        model = item.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(Modifier.width(UndalDimens.sm))
            } else {
                Icon(
                    imageVector = if (item.type.startsWith("promo")) Icons.Filled.Info else Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = if (item.read) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(UndalDimens.iconMd)
                )
                Spacer(Modifier.width(UndalDimens.sm))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (item.read) FontWeight.Normal else FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.body.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = stampLabel(item),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!item.orderId.isNullOrBlank() && item.type.startsWith("order")) {
                    Spacer(Modifier.height(UndalDimens.xs))
                    UndalSecondaryButton(
                        text = "Open the order",
                        onClick = onOpen,
                        icon = Icons.Filled.Check
                    )
                }
            }

            if (!item.read) {
                Spacer(Modifier.width(UndalDimens.sm))
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                )
            }
        }
    }
}

private fun stampLabel(item: NotifInboxRepo.InboxItem): String =
    if (item.createdAtMillis <= 0L) {
        "Just now"
    } else {
        TimeDhaka.relativeFromNow(Instant.ofEpochMilli(item.createdAtMillis))
    }
