package com.undal.syl.app.features.vendor.dashboard

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.undal.syl.app.core.config.UndalBuildInfo
import com.undal.syl.app.core.design.UndalActionGroup
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalKeyValueRow
import com.undal.syl.app.core.design.UndalPrimaryButton
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.design.UndalSecondaryButton
import com.undal.syl.app.core.design.UndalStatusPill
import com.undal.syl.app.core.design.UndalTextField
import com.undal.syl.app.core.design.UndalTextButton
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.core.theme.undalSemantics
import com.undal.syl.app.core.background.PresenceHeartbeat
import com.undal.syl.app.core.model.PresenceSummary
import com.undal.syl.app.core.util.Money
import com.undal.syl.app.core.util.TimeDhaka
import com.undal.syl.app.features.vendor.VendorAgePill
import com.undal.syl.app.features.vendor.VendorEmptyState
import com.undal.syl.app.features.vendor.VendorOrderCard
import com.undal.syl.app.features.vendor.VendorSection
import com.undal.syl.app.features.vendor.VendorStatCard
import com.undal.syl.app.features.vendor.VendorStatusPill
import com.undal.syl.app.features.vendor.VendorWeekChart
import com.undal.syl.app.features.vendor.tick

/**
 * The kitchen's dashboard (V-011 … V-023, V-044, V-076 … V-078, V-094 … V-096,
 * V-108, V-110).
 *
 * It opens on the one thing that matters: is the store open, and is anything
 * waiting. Everything else — the week, the event stream, the device health — is
 * below that, so a glance at the top of the screen is enough.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VendorDashboardScreen(
    onOpenOrders: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenPromos: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChats: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenInbox: () -> Unit,
    onOpenOrder: (String) -> Unit,
    onPreviewAsCustomer: () -> Unit,
    viewModel: VendorDashboardViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh(context) }
    LaunchedEffect(refreshing) {
        if (!refreshing) return@LaunchedEffect
        try {
            viewModel.refreshAll(context)
            kotlinx.coroutines.delay(350L)
        } finally {
            refreshing = false
        }
    }

    // The export and the store share both leave through the phone's share sheet.
    LaunchedEffect(state.exportCsv) {
        val csv = state.exportCsv ?: return@LaunchedEffect
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "Undal orders ${TimeDhaka.formatDate(TimeDhaka.today())}")
            putExtra(Intent.EXTRA_TEXT, csv)
        }
        context.startActivity(Intent.createChooser(intent, "Share today's orders"))
        viewModel.clearExport()
    }
    LaunchedEffect(state.shareText) {
        val text = state.shareText
        if (text.isBlank()) return@LaunchedEffect
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share the store link"))
        viewModel.dismissMessage()
    }

    UndalScaffold(
        title = state.store.storeName.ifBlank { "Undal HomeKitchen" },
        subtitle = state.openState.headline,
        actions = {
            UndalTextButton(
                text = "Queue",
                onClick = onOpenOrders,
                icon = Icons.Filled.ShoppingCart
            )
            UndalTextButton(
                text = "Settings",
                onClick = onOpenSettings,
                icon = Icons.Filled.Build
            )
        }
    ) { _ ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(UndalDimens.screenPadding),
                verticalArrangement = Arrangement.spacedBy(UndalDimens.sm)
            ) {
                if (!state.vendorClaim && !state.loading) {
                    item {
                        UndalAlertBanner(
                            text = "This account does not carry the kitchen claim. Sign out and sign in " +
                                "with the kitchen account from your repository secrets.",
                            icon = Icons.Filled.Warning,
                            action = {
                                UndalTextButton(
                                    text = "Sign out",
                                    onClick = { viewModel.signOut(context) },
                                    icon = Icons.Filled.ExitToApp
                                )
                            }
                        )
                    }
                }

                if (state.store.autoClosed) {
                    item {
                        UndalAlertBanner(
                            text = "Undal closed the store automatically: this phone stopped checking in " +
                                "for ten minutes. Reopen when you are back.",
                            icon = Icons.Filled.Notifications,
                            action = {
                                UndalTextButton(
                                    text = "Reopen now",
                                    onClick = { viewModel.reopenNow(context) }
                                )
                            }
                        )
                    }
                }

                // The question the opening reminder asks, in the place the tap
                // lands: one button between "paused" and orders ringing. Only for a
                // store that is paused (by the owner or the watchdog) — a store
                // closed merely by its hours opens by itself, and offering to
                // "take orders now" there would promise something it cannot do.
                if ((!state.openState.switchOn || state.store.autoClosed) && !state.loading) {
                    item {
                        VendorSection(
                            title = "Are you taking orders?",
                            subtitle = state.openState.nextOpeningLine
                        ) {
                            Column {
                                Text(
                                    text = if (state.store.autoClosed) {
                                        "Undal paused the store because this phone stopped checking in. " +
                                            "Confirming starts the heartbeat again and puts the store back on " +
                                            "your hours."
                                    } else {
                                        "Your store is paused, so it will not open by itself. Confirming puts " +
                                            "it back on your schedule: customers can order whenever your hours " +
                                            "say open, and this phone rings for them."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(UndalDimens.sm))
                                UndalPrimaryButton(
                                    text = "Yes — take orders now",
                                    onClick = { viewModel.confirmOpenNow(context) },
                                    icon = Icons.Filled.Notifications,
                                    enabled = !state.busy,
                                    loading = state.busy
                                )
                            }
                        }
                    }
                }

                if (state.fromCache) {
                    item {
                        UndalAlertBanner(
                            text = "Showing the last queue that reached this phone. Reconnect and check the latest " +
                                "order status before acting.",
                            icon = Icons.Filled.Info
                        )
                    }
                }

                item {
                    StoreHero(
                        state = state,
                        onNoteChange = viewModel::onNoteChange,
                        onOpen = { viewModel.setOpen(context, open = true) },
                        onClose = { viewModel.setOpen(context, open = false) }
                    )
                }

                item {
                    VendorSection(
                        title = "New orders",
                        subtitle = if (state.waiting.isEmpty()) {
                            "No waiting orders in the current feed."
                        } else {
                            "${state.waiting.size} waiting for your answer — tap to open."
                        }
                    ) {
                        if (state.waiting.isEmpty()) {
                            VendorEmptyState(
                                icon = Icons.Filled.Star,
                                title = "All quiet in the kitchen",
                                message = "New orders appear here when they reach this phone. Keep notification " +
                                    "and sound permissions enabled; check alert diagnostics if an order is missed.",
                                actionLabel = null,
                                onAction = null
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                                state.waiting.forEach { order ->
                                    VendorOrderCard(
                                        order = order,
                                        onClick = {
                                            haptics.tick()
                                            onOpenOrder(order.id)
                                        },
                                        trailing = {
                                            VendorAgePill(order = order)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    UndalActionGroup(primary = {
                        VendorStatCard(
                            modifier = Modifier.fillMaxWidth(),
                            label = "Today",
                            value = state.stats.orders.toString(),
                            caption = "orders since midnight"
                        )
                    }, secondary = {
                        VendorStatCard(
                            modifier = Modifier.fillMaxWidth(),
                            label = "Collected",
                            value = Money.format(state.stats.revenuePaisa),
                            caption = "cash on delivered"
                        )
                    })
                }

                item {
                    UndalActionGroup(primary = {
                        VendorStatCard(
                            modifier = Modifier.fillMaxWidth(),
                            label = "In hand",
                            value = state.stats.pendingAndActive.toString(),
                            caption = "pending + cooking",
                            highlight = state.stats.pendingAndActive > 0
                        )
                    }, secondary = {
                        VendorStatCard(
                            modifier = Modifier.fillMaxWidth(),
                            label = "Average",
                            value = if (state.stats.averageTicketPaisa > 0L) {
                                Money.format(state.stats.averageTicketPaisa)
                            } else {
                                "—"
                            },
                            caption = if (state.stats.topDish.isBlank()) {
                                "no orders yet"
                            } else {
                                "top: ${state.stats.topDish}"
                            }
                        )
                    })
                }

                item {
                    VendorSection(
                        title = "This week",
                        subtitle = "Bars are real orders, counted in Asia/Dhaka days."
                    ) {
                        VendorWeekChart(points = state.week)
                    }
                }

                item {
                    ConnectionCard(
                        // The age is recomputed from the one-second clock the
                        // queue already ticks on, so "last check-in" counts up
                        // while the owner watches instead of freezing at paint.
                        summary = PresenceSummary.of(
                            days = state.presenceDays,
                            heartbeatAgeMillis = state.presence?.lastSeen
                                ?.takeIf { lastSeen -> lastSeen > 0L }
                                ?.let { lastSeen -> now - lastSeen }
                                ?: -1L
                        ),
                        loaded = state.presenceLoaded
                    )
                }

                if (state.runningTimers.isNotEmpty()) {
                    item {
                        VendorSection(
                            title = "On the stove",
                            subtitle = "The prep countdown runs in the notification too."
                        ) {
                            Column {
                                state.runningTimers.forEach { order ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = order.shortId.ifBlank { order.id.takeLast(4) },
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                            Text(
                                                text = "${order.itemCount} item(s) · ${order.etaLine}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        order.prepEndsAtMillis?.let { endsAt ->
                                            Text(
                                                text = TimeDhaka.countdown(
                                                    java.time.Instant.ofEpochMilli(endsAt)
                                                ),
                                                style = MaterialTheme.typography.titleSmall,
                                                color = if (order.isOverdue()) {
                                                    MaterialTheme.undalSemantics.closed
                                                } else {
                                                    MaterialTheme.colorScheme.primary
                                                }
                                            )
                                        }
                                        Spacer(Modifier.width(UndalDimens.xs))
                                        UndalTextButton(
                                            text = "Ready?",
                                            onClick = { onOpenOrder(order.id) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    VendorSection(
                        title = "Jump to",
                        subtitle = "The five screens the kitchen actually uses."
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                            UndalPrimaryButton(
                                text = "Order queue",
                                onClick = onOpenOrders,
                                icon = Icons.Filled.ShoppingCart
                            )
                            UndalActionGroup(primary = {
                                UndalSecondaryButton(
                                    text = "Menu",
                                    onClick = onOpenMenu,
                                    icon = Icons.Filled.List
                                )
                            }, secondary = {
                                UndalSecondaryButton(
                                    text = "Schedule",
                                    onClick = onOpenSchedule,
                                    icon = Icons.Filled.Home
                                )
                            })
                            UndalActionGroup(primary = {
                                UndalSecondaryButton(
                                    text = "Offers",
                                    onClick = onOpenPromos,
                                    icon = Icons.Filled.Star
                                )
                            }, secondary = {
                                UndalSecondaryButton(
                                    text = "Chats",
                                    onClick = onOpenChats,
                                    icon = Icons.Filled.Email
                                )
                            })
                        }
                    }
                }

                if (state.events.isNotEmpty()) {
                    item {
                        VendorSection(title = "Today, as it happened", subtitle = "Newest first.") {
                            Column {
                                state.events.forEach { event ->
                                    val stamp = TimeDhaka.formatInstant(
                                        java.time.Instant.ofEpochMilli(event.atMillis)
                                    )
                                    Text(
                                        text = "$stamp · ${event.text}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(2.dp))
                                }
                            }
                        }
                    }
                }

                item {
                    VendorSection(
                        title = "This phone",
                        subtitle = "Undal only works if these five facts are true."
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            UndalStatusPill(
                                text = if (state.notificationsEnabled) "Alerts on" else "Alerts off",
                                containerColour = if (state.notificationsEnabled) {
                                    MaterialTheme.undalSemantics.successContainer
                                } else {
                                    MaterialTheme.undalSemantics.closedContainer
                                },
                                contentColour = if (state.notificationsEnabled) {
                                    MaterialTheme.undalSemantics.onSuccessContainer
                                } else {
                                    MaterialTheme.undalSemantics.onClosedContainer
                                },
                                icon = Icons.Filled.Notifications
                            )
                            Spacer(Modifier.width(UndalDimens.xs))
                            UndalStatusPill(
                                text = "Heartbeat ${state.heartbeatLabel}",
                                containerColour = MaterialTheme.colorScheme.surfaceVariant,
                                contentColour = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(UndalDimens.xs))
                        UndalKeyValueRow("Session", state.sessionSummary)
                        UndalKeyValueRow("Device token", state.tokenTail.ifBlank { "registering…" })
                        UndalKeyValueRow("Clock", "Asia/Dhaka · ${TimeDhaka.formatDate(TimeDhaka.today())}")
                        Spacer(Modifier.height(UndalDimens.xs))
                        Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                            UndalTextButton(
                                text = "Send heartbeat",
                                onClick = { viewModel.heartbeatNow(context) },
                                icon = Icons.Filled.Refresh
                            )
                            UndalTextButton(
                                text = "Diagnostics",
                                onClick = onOpenDiagnostics,
                                icon = Icons.Filled.Build
                            )
                        }
                    }
                }

                item {
                    VendorSection(
                        title = "Closing time",
                        subtitle = "The day's numbers, then the switch."
                    ) {
                        Text(
                            text = "Today: ${state.stats.orders} order(s), " +
                                "${Money.format(state.stats.revenuePaisa)} collected, " +
                                "${state.stats.delivered} delivered, ${state.stats.cancelled} cancelled. " +
                                if (state.stats.topDish.isBlank()) {
                                    "No dish has been ordered yet."
                                } else {
                                    "Most cooked: ${state.stats.topDish}."
                                },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(UndalDimens.xs))
                        UndalSecondaryButton(
                            text = "Export today's orders (CSV)",
                            onClick = viewModel::exportToday,
                            icon = Icons.Filled.Share
                        )
                        Spacer(Modifier.height(UndalDimens.xs))
                        UndalSecondaryButton(
                            text = "Share the store link",
                            onClick = viewModel::shareStoreLink,
                            icon = Icons.Filled.Share
                        )
                        Spacer(Modifier.height(UndalDimens.xs))
                        UndalSecondaryButton(
                            text = "Kitchen history — every alert this phone rang for",
                            onClick = onOpenInbox,
                            icon = Icons.Filled.Email
                        )
                        Spacer(Modifier.height(UndalDimens.xs))
                        UndalSecondaryButton(
                            text = "See the app as a customer",
                            onClick = onPreviewAsCustomer,
                            icon = Icons.Filled.Person
                        )
                        Spacer(Modifier.height(UndalDimens.xs))
                        Text(
                            text = "Undal ${UndalBuildInfo.versionName} · built for a kitchen in Sylhet. " +
                                "Every screen shows Dhaka time and taka amounts down to the paisa.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Offline and online time, measured rather than guessed (V-013 era).
 *
 * Every number here comes from the Worker's five-minute presence samples for the
 * last seven Dhaka days, so "we were offline twelve minutes yesterday" is a
 * measurement, not a memory. The heartbeat line is this phone's own last
 * check-in, which is the number the ten-minute auto-offline rule reads.
 */
@Composable
private fun ConnectionCard(summary: PresenceSummary, loaded: Boolean) {
    val today = summary.today
    VendorSection(
        title = "Connection",
        subtitle = if (loaded) {
            "Five-minute samples the Worker kept for this kitchen over seven days."
        } else {
            "Waiting for the first sample — the Worker checks in every five minutes."
        }
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)
            ) {
                VendorStatCard(
                            modifier = Modifier.fillMaxWidth(),                    label = "Uptime",
                    value = if (loaded) summary.uptimeLabel else "—",
                    caption = if (summary.sampledMinutes > 0) {
                        "${summary.sampledMinutes} min sampled"
                    } else {
                        "no samples yet"
                    },
                    highlight = summary.heartbeatHealthy,
                    modifier = Modifier.weight(1f)
                )
                VendorStatCard(
                            modifier = Modifier.fillMaxWidth(),                    label = "Offline",
                    value = PresenceSummary.formatMinutes(summary.offlineMinutes),
                    caption = "in 7 days",
                    highlight = summary.heartbeatAgeMillis > PresenceHeartbeat.STALE_AGE_MILLIS,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(UndalDimens.sm))
            UndalKeyValueRow(
                label = "This phone",
                value = if (summary.heartbeatAgeMillis < 0L) "checking…" else "last check-in ${summary.heartbeatLabel}"
            )
            UndalKeyValueRow(
                label = "Offline time",
                value = summary.offlineLine
            )
            UndalKeyValueRow(
                label = "Automatic pauses",
                value = if (summary.pauses == 0) {
                    "none in 7 days"
                } else {
                    "${summary.pauses} paused · ${summary.restores} reopened"
                }
            )
            UndalKeyValueRow(
                label = "Outages",
                value = summary.outageLine
            )
            if (today != null && today.outages.isNotEmpty()) {
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "Today, in Dhaka time",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                today.outages.takeLast(MAX_OUTAGE_LINES).forEach { outage ->
                    UndalKeyValueRow(
                        label = if (outage.open) "Still offline" else "Outage",
                        value = outage.span,
                        icon = Icons.Filled.Info
                    )
                }
            }
            Text(
                text = "A pause is Undal's own decision: this phone stopped checking in for ten " +
                    "minutes while the store was open, so orders were paused before a customer " +
                    "waited on one nobody could cook. Coming back within half an hour reopens it " +
                    "by itself and tells you why.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The card never grows taller than this, however bad the night was. */
private const val MAX_OUTAGE_LINES = 4

@Composable
private fun StoreHero(
    state: VendorDashboardViewModel.State,
    onNoteChange: (String) -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit
) {
    UndalCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(UndalDimens.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UndalStatusPill(
                    text = if (state.openState.serviceable) "Open" else "Closed",
                    containerColour = if (state.openState.serviceable) {
                        MaterialTheme.undalSemantics.successContainer
                    } else {
                        MaterialTheme.undalSemantics.closedContainer
                    },
                    contentColour = if (state.openState.serviceable) {
                        MaterialTheme.undalSemantics.onSuccessContainer
                    } else {
                        MaterialTheme.undalSemantics.onClosedContainer
                    }
                )
                Spacer(Modifier.width(UndalDimens.xs))
                if (state.store.autoClosed) {
                    UndalStatusPill(
                        text = "Auto-closed",
                        containerColour = MaterialTheme.undalSemantics.warningContainer,
                        contentColour = MaterialTheme.undalSemantics.onWarningContainer
                    )
                } else if (!state.openState.switchOn) {
                    UndalStatusPill(
                        text = "Paused",
                        containerColour = MaterialTheme.undalSemantics.warningContainer,
                        contentColour = MaterialTheme.undalSemantics.onWarningContainer
                    )
                } else {
                    UndalStatusPill(
                        text = "On schedule",
                        containerColour = MaterialTheme.undalSemantics.successContainer,
                        contentColour = MaterialTheme.undalSemantics.onSuccessContainer
                    )
                }
            }
            Spacer(Modifier.height(UndalDimens.xs))
            Text(
                text = state.openState.headline,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = state.openState.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = state.openState.todayLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.openState.switchOn && !state.store.autoClosed) {
                // The switch is on: the hours do the rest, every day.
                Text(
                    text = "Opens and closes by itself on your hours · ${state.openState.nextOpeningLine}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(UndalDimens.sm))
            UndalTextField(
                value = state.note,
                onValueChange = onNoteChange,
                label = "Note customers will read",
                hint = "Back at 4 pm — the tandoor has to cool first.",
                maxLines = 2
            )
            Spacer(Modifier.height(UndalDimens.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                // "Open" switches the store onto its schedule (open now, or by itself
                // at the next opening); "Pause" holds it closed until opened again.
                val paused = !state.openState.switchOn || state.store.autoClosed
                UndalPrimaryButton(
                    text = "Open the store",
                    onClick = onOpen,
                    enabled = !state.busy && paused,
                    loading = state.busy && paused,
                    icon = Icons.Filled.Home,
                    modifier = Modifier.weight(1f)
                )
                UndalSecondaryButton(
                    text = "Pause",
                    onClick = onClose,
                    enabled = !state.busy && state.openState.switchOn,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
