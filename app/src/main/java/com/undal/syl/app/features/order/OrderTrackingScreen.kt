package com.undal.syl.app.features.order

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.undal.syl.app.core.design.UndalAdaptivePair
import com.undal.syl.app.core.design.UndalActionGroup
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalEmptyState
import com.undal.syl.app.core.design.UndalFullScreenLoader
import com.undal.syl.app.core.design.UndalKeyValueRow
import com.undal.syl.app.core.design.UndalPrimaryButton
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.design.UndalSecondaryButton
import com.undal.syl.app.core.design.UndalTextField
import com.undal.syl.app.core.maps.UndalLocationMap
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.core.theme.undalSemantics
import com.undal.syl.app.core.util.Distance
import com.undal.syl.app.core.util.Money
import com.undal.syl.app.core.util.OrderStatus
import com.undal.syl.app.core.util.TimeDhaka
import com.undal.syl.app.core.util.UndalPhone
import com.undal.syl.app.data.repo.ChatRepo
import com.undal.syl.app.data.repo.OrderRepo

/**
 * The order, live (C-063 … C-076).
 *
 * One screen that answers, in this order: what is happening now, what happens
 * next, what I ordered, what I will pay, and how to reach the kitchen. The chat
 * is a mode of this screen rather than a separate page, because a conversation
 * about an order only makes sense next to the order.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OrderTrackingScreen(
    orderId: String,
    onBack: () -> Unit,
    onOpenCart: () -> Unit,
    openChatInitially: Boolean = false,
    viewModel: OrderTrackingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The ticker behind this screen is told when there is somebody to tick for.
    // `ON_START`/`ON_STOP` are the pair Navigation dispatches when this screen
    // comes forward and when another one covers it — including the moment the
    // customer presses home, which is the one that matters most: a countdown
    // nobody can see does not need a clock, and a phone in a pocket should not
    // be woken once a second for one.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onScreenVisible()
                Lifecycle.Event.ON_STOP -> viewModel.onScreenHidden()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // The observer is gone before the last ON_STOP can arrive, so the
            // screen says so itself rather than leaving the ticker running for a
            // screen that no longer exists.
            viewModel.onScreenHidden()
        }
    }

    LaunchedEffect(orderId) { viewModel.bind(orderId) }
    LaunchedEffect(openChatInitially) { if (openChatInitially) viewModel.openChat() }
    LaunchedEffect(state.reorderedLines) { if (state.reorderedLines > 0) onOpenCart() }

    UndalScaffold(
        title = state.detail?.shortId?.ifBlank { "Your order" } ?: "Your order",
        subtitle = state.detail?.let { orderStatusCaption(state.displayStatus) },
        onBack = onBack,
        contentMaxWidth = 1100.dp,
        bottomBar = {
            if (state.detail != null && !state.chatOpen) {
                UndalActionGroup(
                    modifier = Modifier.padding(horizontal = UndalDimens.screenPadding, vertical = UndalDimens.sm),
                    primary = {
                        UndalSecondaryButton(
                            text = if (state.unread > 0) "Chat · ${state.unread} new" else "Chat with kitchen",
                            onClick = viewModel::openChat, icon = Icons.Filled.Send
                        )
                    },
                    secondary = if (state.canCallKitchen) {
                        {
                            UndalSecondaryButton("Call kitchen", onClick = { UndalPhone.dial(context, state.kitchenPhone) },
                                icon = Icons.Filled.Call)
                        }
                    } else null
                )
            }
        }
    ) { _ ->
        if (state.loading && state.detail == null && state.notice == null) {
            UndalFullScreenLoader(message = "Reading your order…")
            return@UndalScaffold
        }
        val detail = state.detail
        if (detail == null) {
            UndalEmptyState(
                icon = Icons.Filled.Warning,
                title = if (state.notice != null) "Your order could not be read" else "That order is not on this account",
                message = state.notice ?: "It may belong to a different account, or the link is old.",
                actionLabel = "Back to the store",
                onAction = onBack
            )
            return@UndalScaffold
        }

        if (state.chatOpen) {
            ChatMode(state = state, viewModel = viewModel, onClose = viewModel::closeChat)
            return@UndalScaffold
        }

        // Status and timeline stay together; the receipt and delivery details
        // sit beside them on wide screens, and follow them on compact screens.
        val showPlacementCard = state.displayStatus == OrderStatus.PLACED && !state.isCancelled

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
                    action = {
                        TextButton(onClick = viewModel::dismissNotice) { Text("Close") }
                    }
                )
            }

            Spacer(Modifier.height(UndalDimens.sm))

            UndalAdaptivePair(primary = {
                Column {
                    // ---- what is happening now -------------------------------------
                    UndalCard {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(UndalDimens.sm),
                            verticalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                            OrderStatusPill(state.displayStatus)
                            if (state.placedAgoLabel.isNotBlank()) {
                                Text(
                                    text = state.placedAgoLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(UndalDimens.sm))

                        when {
                            state.isCancelled -> CancelledBlock(state, viewModel, context)
                            state.isDelivered -> DeliveredBlock(state, viewModel, onOpenCart)
                            state.isCooking -> CookingBlock(state)
                            state.displayStatus == OrderStatus.READY -> ReadyBlock(state)
                            state.displayStatus == OrderStatus.IN_DELIVERY -> OnTheWayBlock(state)
                            else -> WaitingBlock(state)
                        }
                    }

                    Spacer(Modifier.height(UndalDimens.sm))

                    // ---- receipts, timeline, address -------------------------------
                    UndalCard {
                        Text("Order status", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(UndalDimens.sm))
                        OrderTimelineStepper(
                            steps = state.steps,
                            currentIndex = state.stepIndex,
                            cancelled = state.isCancelled
                        )
                        if (state.hasHistoryRows) {
                            Spacer(Modifier.height(UndalDimens.xs))
                            Text(
                                text = "Every step above is recorded on the order itself, in Dhaka time.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                }
            }, secondary = {
                Column {
                    if (showPlacementCard) {
                        UndalOrderConfirmationCard(detail = detail)
                    }
                    // The placement card above prints the same bill row by row, so it is
                    // not printed twice on one screen — the page repeats the *status*,
                    // never the receipt.
                    if (!showPlacementCard) {
                        Spacer(Modifier.height(UndalDimens.sm))

                        ReceiptCard(detail)
                    }

                    Spacer(Modifier.height(UndalDimens.sm))

                    DeliveryCard(detail)

                    if (detail.kitchenNotes.isNotEmpty()) {
                        Spacer(Modifier.height(UndalDimens.sm))
                        UndalCard {
                            Text("From the kitchen", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(UndalDimens.xs))
                            detail.kitchenNotes.forEach { note ->
                                Text(
                                    text = "“$note”",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(UndalDimens.sm))

                }
            })
            Spacer(Modifier.height(UndalDimens.sm))

            UndalCard {
                Text("Order again", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "Rebuilds this order in your cart at today's prices. Dishes that are finished " +
                        "for the day are left out with a note.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(UndalDimens.sm))
                UndalSecondaryButton(
                    text = if (state.reordering) "Rebuilding your cart…" else "Reorder",
                    onClick = viewModel::reorder,
                    icon = Icons.Filled.Refresh,
                    loading = state.reordering
                )
            }

            Spacer(Modifier.height(UndalDimens.xxl))
        }
    }
}

// ---------------------------------------------------------------- state blocks

@Composable
private fun WaitingBlock(state: OrderTrackingViewModel.State) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Send,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(UndalDimens.iconLg)
        )
        Spacer(Modifier.width(UndalDimens.sm))
        Column {
            Text("Waiting for the kitchen", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Your order has been placed. This page updates when the kitchen responds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CookingBlock(state: OrderTrackingViewModel.State) {
    PrepCountdownRing(
        progress = state.prepProgress,
        timeLabel = state.countdownLabel.ifBlank { "—:—" },
        headline = if (state.prepOverdue) {
            "Cooking is taking a little longer than promised"
        } else {
            "Your food is being cooked"
        },
        subline = "The kitchen set aside its prep time when it accepted. This ring follows it live.",
        overdue = state.prepOverdue,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ReadyBlock(state: OrderTrackingViewModel.State) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.undalSemantics.success,
            modifier = Modifier.size(UndalDimens.iconLg)
        )
        Spacer(Modifier.width(UndalDimens.sm))
        Column {
            Text("Packed and ready", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Everything is boxed. The rider takes it from here — you will be told the moment " +
                    "it is on the way.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun OnTheWayBlock(state: OrderTrackingViewModel.State) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(UndalDimens.iconLg)
        )
        Spacer(Modifier.width(UndalDimens.sm))
        Column {
            val collectCash = Money.format(state.detail?.totalAmountPaisa ?: 0L)
            Text("On the way to you", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Your food has left the kitchen. Please keep $collectCash ready in cash.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DeliveredBlock(
    state: OrderTrackingViewModel.State,
    viewModel: OrderTrackingViewModel,
    onOpenCart: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 450),
        label = "deliveredScale"
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.undalSemantics.success,
            modifier = Modifier
                .size(64.dp)
                .scale(scale)
        )
        Spacer(Modifier.height(UndalDimens.sm))
        Text("Delivered — enjoy your food", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Paid in cash: ${Money.format(state.detail?.collectedAmountPaisa ?: state.detail?.totalAmountPaisa ?: 0L)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(UndalDimens.sm))
        UndalSecondaryButton(
            text = if (state.reordering) "Rebuilding your cart…" else "Order this again",
            onClick = viewModel::reorder,
            icon = Icons.Filled.Refresh,
            loading = state.reordering
        )
    }
}

@Composable
private fun CancelledBlock(
    state: OrderTrackingViewModel.State,
    viewModel: OrderTrackingViewModel,
    context: android.content.Context
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.undalSemantics.closed,
                modifier = Modifier.size(UndalDimens.iconLg)
            )
            Spacer(Modifier.width(UndalDimens.sm))
            Text("Cancelled by the kitchen", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(UndalDimens.sm))
        Surface(
            shape = RoundedCornerShape(UndalDimens.radiusSm),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                text = state.detail?.cancelNote?.ifBlank { "No reason was written on the order." }
                    ?: "No reason was written on the order.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(UndalDimens.sm)
            )
        }
        Spacer(Modifier.height(UndalDimens.sm))
        Text(
            text = "An order cannot be cancelled from the app once it is placed — only the kitchen can. " +
                "If this was a mistake, call them and they will help.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(UndalDimens.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.sm)) {
            if (state.canCallKitchen) {
                UndalPrimaryButton(
                    text = "Call the kitchen",
                    onClick = { UndalPhone.dial(context, state.kitchenPhone) },
                    icon = Icons.Filled.Call,
                    modifier = Modifier.weight(1f)
                )
            }
            UndalSecondaryButton(
                text = "Chat instead",
                onClick = viewModel::openChat,
                icon = Icons.Filled.Send,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// -------------------------------------------------------------------- receipt

@Composable
private fun ReceiptCard(detail: OrderRepo.OrderDetail) {
    UndalCard {
        Text("Your receipt", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(UndalDimens.xs))
        detail.items.forEach { line ->
            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                Text(
                    text = "${line.quantity} ×",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(UndalDimens.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(line.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "${Money.format(line.unitAmountPaisa)} each",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (line.note.isNotBlank()) {
                        Text(
                            text = "Note: ${line.note}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                Text(Money.format(line.lineTotalPaisa), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(UndalDimens.xs))
        UndalKeyValueRow("Food subtotal", Money.format(detail.subtotalAmountPaisa))
        UndalKeyValueRow(
            "Delivery · ${Distance.formatKm(detail.distanceKm)}",
            Money.format(detail.deliveryAmountPaisa)
        )
        if (detail.discountAmountPaisa > 0L) {
            UndalKeyValueRow(
                "Coupon ${detail.couponCode}".trim(),
                "- ${Money.format(detail.discountAmountPaisa)}"
            )
        }
        Spacer(Modifier.height(UndalDimens.xs))
        UndalKeyValueRow("Total paid in cash", Money.format(detail.totalAmountPaisa), emphasise = true)
        if (detail.note.isNotBlank()) {
            Spacer(Modifier.height(UndalDimens.xs))
            Text(
                text = "Your note to the kitchen: ${detail.note}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DeliveryCard(detail: OrderRepo.OrderDetail) {
    UndalCard {
        Text("Delivery", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(UndalDimens.xs))
        OrderQuickFactRow(
            icon = Icons.Filled.Home,
            label = "Address",
            value = detail.addressTitle.ifBlank { "Pinned point" }
        )
        OrderQuickFactRow(
            icon = Icons.Filled.LocationOn,
            label = "Distance",
            value = Distance.formatKm(detail.distanceKm)
        )
        OrderQuickFactRow(
            icon = Icons.Filled.DateRange,
            label = "Placed",
            value = detail.createdAt?.let { TimeDhaka.formatInstantDate(it) } ?: "—"
        )
        Spacer(Modifier.height(UndalDimens.xs))
        Text(detail.addressDesc, style = MaterialTheme.typography.bodyMedium)
        if (detail.addressNote.isNotBlank()) {
            Text(
                text = "Rider note: ${detail.addressNote}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = "No rider note — add one if your gate is hard to find.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(UndalDimens.sm))
        UndalLocationMap(
            latitude = detail.addressLat,
            longitude = detail.addressLng,
            title = detail.addressTitle.ifBlank { "Delivery point" },
            caption = detail.addressTitle.ifBlank { "Delivery point" },
            height = UndalDimens.mapHeight
        )
        Spacer(Modifier.height(UndalDimens.sm))
        Surface(
            shape = RoundedCornerShape(UndalDimens.radiusSm),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Text(
                text = "Cash on delivery only. The rider collects the exact total; no card and no online payment.",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(UndalDimens.sm)
            )
        }
    }
}

// ----------------------------------------------------------------------- chat

@Composable
private fun ChatMode(
    state: OrderTrackingViewModel.State,
    viewModel: OrderTrackingViewModel,
    onClose: () -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = UndalDimens.sm, vertical = UndalDimens.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onClose) { Text("Back to the order") }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${state.detail?.shortId.orEmpty()} · kitchen chat",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                UndalEmptyState(
                    icon = Icons.Filled.Send,
                    title = "No messages yet",
                    message = "Say something like “please make it less spicy” — the kitchen sees it on the " +
                        "same screen as your order."
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = UndalDimens.screenPadding),
                verticalArrangement = Arrangement.spacedBy(UndalDimens.sm)
            ) {
                items(state.messages, key = { it.id.ifBlank { it.tsMillis.toString() + it.text.take(8) } }) { message ->
                    ChatBubble(message = message)
                }
            }
        }

        if (state.kitchenTyping) {
            Text(
                text = "The kitchen is typing…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = UndalDimens.screenPadding)
            )
        }

        Surface(tonalElevation = UndalDimens.elevationBar) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(UndalDimens.sm),
                verticalAlignment = Alignment.Bottom
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    UndalTextField(
                        value = state.draft,
                        onValueChange = viewModel::onDraftChange,
                        label = "Message",
                        hint = "Type your question…",
                        maxLines = 4,
                        singleLine = false,
                        imeAction = ImeAction.Send,
                        onImeAction = viewModel::send,
                        helper = if (state.sending) "Sending…" else null
                    )
                }
                Spacer(Modifier.width(UndalDimens.sm))
                UndalPrimaryButton(
                    text = "Send",
                    onClick = viewModel::send,
                    enabled = state.sendEnabled,
                    loading = state.sending,
                    icon = Icons.Filled.Send,
                    fillWidth = false
                )
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatRepo.Message) {
    val fromKitchen = message.fromKitchen
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromKitchen) Arrangement.Start else Arrangement.End
    ) {
        Surface(
            shape = RoundedCornerShape(UndalDimens.radiusMd),
            color = if (fromKitchen) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
            modifier = Modifier.fillMaxWidth(0.86f)
        ) {
            Column(modifier = Modifier.padding(UndalDimens.sm)) {
                Text(
                    text = if (fromKitchen) "Kitchen" else "You",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (fromKitchen) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    }
                )
                if (message.tsMillis > 0L) {
                    Text(
                        text = TimeDhaka.formatInstant(java.time.Instant.ofEpochMilli(message.tsMillis)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    }
}

/** Shared by the history list: a compact one-line item summary. */
fun summaryLine(order: OrderRepo.OrderSummary): String = when {
    order.itemCount == 0 -> "No items"
    order.firstItemName.isBlank() -> "${order.itemCount} item(s)"
    order.itemCount == 1 -> order.firstItemName
    else -> "${order.firstItemName} + ${order.itemCount - 1} more"
}

/** Shared by the history list: the pair of dates an order carries. */
fun historyStamp(order: OrderRepo.OrderSummary): String =
    order.createdAt?.let { TimeDhaka.formatInstantDate(it) }.orEmpty()
