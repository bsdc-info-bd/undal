package com.undal.syl.app.features.vendor.orders

import java.util.Locale
import android.content.Intent
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.undal.syl.app.core.design.UndalEmptyState
import com.undal.syl.app.core.design.UndalFullScreenLoader
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalConfirmDialog
import com.undal.syl.app.core.design.UndalKeyValueRow
import com.undal.syl.app.core.design.UndalPrimaryButton
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.design.UndalSecondaryButton
import com.undal.syl.app.core.design.UndalTextField
import com.undal.syl.app.core.maps.UndalLocationMap
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.core.theme.undalSemantics
import com.undal.syl.app.core.util.Money
import com.undal.syl.app.core.util.OrderStateMachine
import com.undal.syl.app.core.util.OrderStatus
import com.undal.syl.app.core.util.TimeDhaka
import com.undal.syl.app.core.util.UndalDigits
import com.undal.syl.app.core.util.UndalPhone
import com.undal.syl.app.features.vendor.VendorAgePill
import com.undal.syl.app.features.vendor.VendorChip
import com.undal.syl.app.features.vendor.VendorSection
import com.undal.syl.app.features.vendor.VendorStatusPill
import com.undal.syl.app.features.vendor.tick
import kotlinx.coroutines.launch

/**
 * One order, end to end (V-025 … V-040, V-043 … V-047).
 *
 * The buttons that exist on this screen are exactly the moves the state machine
 * allows, so the kitchen cannot tap an impossible state. Everything the customer
 * will be told — accepted, ready, on the way, delivered, cancelled with a note —
 * is written here and pushed from here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VendorOrderDetailScreen(
    orderId: String,
    sheet: String? = null,
    openChatInitially: Boolean = false,
    onBack: () -> Unit,
    onOpenChats: () -> Unit,
    viewModel: VendorOrderDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var readyChecked by rememberSaveable { mutableStateOf(false) }
    var collectedChecked by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(orderId, openChatInitially) { viewModel.bind(orderId, openChatInitially) }

    // A push can carry `?sheet=accept|reject|ready|delivered` straight into the
    // right confirmation (V-030, V-031, V-033).
    LaunchedEffect(orderId, sheet) {
        when (sheet?.lowercase(Locale.ENGLISH)) {
            "accept" -> viewModel.openSheet(VendorOrderDetailViewModel.Sheet.Accept)
            "reject" -> viewModel.openSheet(VendorOrderDetailViewModel.Sheet.Reject)
            "ready" -> viewModel.openSheet(VendorOrderDetailViewModel.Sheet.Ready)
            "delivered" -> viewModel.openSheet(VendorOrderDetailViewModel.Sheet.Delivered)
            else -> Unit
        }
    }

    LaunchedEffect(state.shareText) {
        val text = state.shareText
        if (text.isBlank()) return@LaunchedEffect
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share this order"))
        viewModel.clearShareText()
    }

    LaunchedEffect(state.chatOpen) {
        if (state.chatOpen) {
            scope.launch {
                runCatching {
                    listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                }
            }
        }
    }

    val order = state.order

    UndalScaffold(
        title = if (order == null) "Order" else "Order ${order.shortId.ifBlank { order.id.takeLast(4) }}",
        subtitle = order?.let { "${it.statusLabel} · ${it.ageLabel()} old" }.orEmpty(),
        onBack = onBack,
        contentMaxWidth = 840.dp,
        actions = {
            IconButton(onClick = {
                viewModel.toggleChat()
                if (!state.chatOpen) onOpenChats()
            }, enabled = order != null) {
                Icon(Icons.Filled.Email, contentDescription = if (state.unread > 0) "Chat · ${state.unread} unread" else "Open chat")
            }
            IconButton(onClick = viewModel::readyShareText, enabled = order != null) {
                Icon(Icons.Filled.Share, contentDescription = "Share order")
            }
        },
        bottomBar = {
            if (order != null && !state.chatOpen && !OrderStateMachine.isTerminal(order.status)) {
                UndalPrimaryButton(
                    text = when (order.status) {
                        OrderStatus.PLACED -> "Accept · choose prep time"
                        OrderStatus.ACCEPTED -> "Confirm packed and ready"
                        OrderStatus.READY -> "Handed to rider · mark out"
                        OrderStatus.IN_DELIVERY -> "Delivered · confirm cash"
                        else -> "Order finished"
                    },
                    onClick = {
                        when (order.status) {
                            OrderStatus.PLACED -> viewModel.openSheet(VendorOrderDetailViewModel.Sheet.Accept)
                            OrderStatus.ACCEPTED -> {
                                readyChecked = false
                                viewModel.openSheet(VendorOrderDetailViewModel.Sheet.Ready)
                            }
                            OrderStatus.READY -> { haptics.tick(); viewModel.markInDelivery() }
                            OrderStatus.IN_DELIVERY -> {
                                collectedChecked = false
                                viewModel.openSheet(VendorOrderDetailViewModel.Sheet.Delivered)
                            }
                            else -> Unit
                        }
                    },
                    enabled = !state.busy, loading = state.busy,
                    modifier = Modifier.padding(horizontal = UndalDimens.screenPadding, vertical = UndalDimens.sm)
                )
            }
        }
    ) { _ ->
        if (order == null) {
            if (state.loading && state.infoMessage == null) {
                UndalFullScreenLoader(message = "Reading the order…")
            } else {
                UndalEmptyState(icon = Icons.Filled.Warning, title = "Order unavailable",
                    message = state.infoMessage ?: "This order could not be found. Reopen it from the queue to try again.",
                    actionLabel = "Back to queue", onAction = onBack)
            }
            return@UndalScaffold
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(UndalDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(UndalDimens.sm)
        ) {
            if (state.infoMessage != null) {
                item(key = "order-message") {
                    UndalAlertBanner(text = state.infoMessage.orEmpty(), icon = Icons.Filled.Info,
                        action = { TextButton(onClick = viewModel::dismissMessage) { Text("Close") } })
                }
            }
            if (state.fromCache) {
                item {
                    UndalAlertBanner(
                        text = "Showing a saved copy. Reconnect and check the latest order status before acting.",
                        icon = Icons.Filled.Info
                    )
                }
            }

            if (order.isOverdue()) {
                item {
                    UndalAlertBanner(
                        text = "Overdue: the promised prep time has passed and the customer is waiting. " +
                            "Mark it ready, or tell them why in the chat.",
                        icon = Icons.Filled.Warning
                    )
                }
            }

            item {
                UndalCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(UndalDimens.sm)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(UndalDimens.sm),
                            verticalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                            VendorStatusPill(status = order.status)
                            VendorAgePill(order = order)
                        }
                        Spacer(Modifier.height(UndalDimens.xs))
                        Text(
                            text = Money.format(order.totalAmountPaisa) + " · cash on delivery",
                            style = MaterialTheme.typography.titleLarge
                        )
                        if (order.status == OrderStatus.ACCEPTED && order.prepEndsAtMillis != null) {
                            val remaining = TimeDhaka.countdown(java.time.Instant.ofEpochMilli(order.prepEndsAtMillis))
                            Text(
                                text = "Promised in $remaining (${order.prepMinutes} min)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (order.isOverdue()) {
                                    MaterialTheme.undalSemantics.closed
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                        }
                        if (order.couponCode.isNotBlank()) {
                            Text(
                                text = "Coupon ${order.couponCode} took ${Money.format(order.discountPaisa)} off " +
                                    "a ${Money.format(order.subtotalPaisa)} basket.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (order.status == OrderStatus.DELIVERED) {
                            Text(
                                text = "Collected ${Money.format(order.collectedAmountPaisa ?: order.totalAmountPaisa)} in cash.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.undalSemantics.success
                            )
                        }
                        if (order.status == OrderStatus.CANCELLED) {
                            Text(
                                text = "Cancelled: ${order.cancelNote.ifBlank { "no reason written" }}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.undalSemantics.closed
                            )
                        }
                    }
                }
            }

            item {
                ActionCard(state = state,
                    onReject = { viewModel.openSheet(VendorOrderDetailViewModel.Sheet.Reject) })
            }

            item {
                VendorSection(
                    title = "What to cook",
                    subtitle = "${order.itemCount} item(s) — read the notes, they travel with the dish."
                ) {
                    Column {
                        order.lines.forEach { line ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${line.qty} ×",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(UndalDimens.xs))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = line.name, style = MaterialTheme.typography.bodyLarge)
                                    if (line.note.isNotBlank()) {
                                        Text(
                                            text = "Note: ${line.note}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(
                                    text = Money.format(line.lineTotalPaisa),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Spacer(Modifier.height(UndalDimens.xs))
                        }
                        UndalKeyValueRow("Subtotal", Money.format(order.subtotalPaisa))
                        UndalKeyValueRow(
                            "Delivery",
                            "${Money.format(order.deliveryPaisa)} · ${UndalDigits.format(order.distanceKm, 1)} km"
                        )
                        if (order.discountPaisa > 0L) {
                            UndalKeyValueRow("Discount", "− ${Money.format(order.discountPaisa)}")
                        }
                        UndalKeyValueRow("Cash to collect", Money.format(order.totalAmountPaisa), emphasise = true)
                        if (order.note.isNotBlank()) {
                            Spacer(Modifier.height(UndalDimens.xs))
                            UndalAlertBanner(
                                text = "Order note: ${order.note}",
                                icon = Icons.Filled.Info
                            )
                        }
                    }
                }
            }

            item {
                VendorSection(title = "The customer", subtitle = "Tap to call, long-press to copy.") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.customerPhotoUrl.isNotBlank()) {
                            AsyncImage(
                                model = state.customerPhotoUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(UndalDimens.avatarLg)
                                    .clip(CircleShape)
                            )
                        } else {
                            Icon(
                                Icons.Filled.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(UndalDimens.iconLg)
                            )
                        }
                        Spacer(Modifier.width(UndalDimens.sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = order.customerName.ifBlank { "Customer" },
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = order.phone.ifBlank { "no number on the order" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.combinedClickable(
                                    // UndalPhone, not a hand-built tel: intent: the
                                    // number may have been typed on a Bangla keypad
                                    // (০১৭…) and a phone with no dialer must not take
                                    // the kitchen's screen down with it.
                                    onClick = {
                                        if (order.phone.isNotBlank() &&
                                            !UndalPhone.dial(context, order.phone)
                                        ) {
                                            clipboard.setText(AnnotatedString(order.phone))
                                        }
                                    },
                                    onLongClick = {
                                        if (order.phone.isNotBlank()) {
                                            clipboard.setText(AnnotatedString(order.phone))
                                        }
                                    }
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(UndalDimens.xs))
                    Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                        UndalSecondaryButton(
                            text = "Call",
                            onClick = {
                                if (order.phone.isNotBlank() &&
                                    !UndalPhone.dial(context, order.phone)
                                ) {
                                    // No dialer here: the number still leaves with
                                    // the vendor, on the clipboard, so the call can
                                    // be made from a phone that has one.
                                    clipboard.setText(AnnotatedString(order.phone))
                                }
                            },
                            icon = Icons.Filled.Call,
                            enabled = order.phone.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        )
                        UndalSecondaryButton(
                            text = "Copy address",
                            onClick = {
                                clipboard.setText(
                                    AnnotatedString(
                                        listOf(order.addressTitle, order.addressDesc, order.addressNote)
                                            .filter { it.isNotBlank() }
                                            .joinToString("\n")
                                    )
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            item {
                VendorSection(
                    title = "Where it goes",
                    subtitle = "The kitchen pin and the customer's pin on one street map, framed so " +
                        "both are on screen. Drag or pinch to look around — the order address does not change."
                ) {
                    Column {
                        Text(
                            text = order.addressTitle.ifBlank { "No address title on the order" },
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = order.addressDesc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (order.addressNote.isNotBlank()) {
                            Text(
                                text = "Landmark: ${order.addressNote}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(UndalDimens.xs))
                        UndalLocationMap(
                            latitude = order.customerLat,
                            longitude = order.customerLng,
                            title = order.addressTitle.ifBlank { "Delivery point" },
                            caption = order.addressTitle.ifBlank { "Delivery point" },
                            height = UndalDimens.mapHeightTall
                        )
                    }
                }
            }

            item {
                VendorSection(title = "Who did what, when", subtitle = "The same timeline the customer sees.") {
                    Column {
                        order.timeline.forEach { step ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (step.done) Icons.Filled.Check else Icons.Filled.Info,
                                    contentDescription = null,
                                    tint = if (step.done) {
                                        MaterialTheme.undalSemantics.success
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                    modifier = Modifier.size(UndalDimens.iconSm)
                                )
                                Spacer(Modifier.width(UndalDimens.xs))
                                Text(
                                    text = step.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = step.atMillis?.let {
                                        TimeDhaka.formatInstant(java.time.Instant.ofEpochMilli(it))
                                    }.orEmpty(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
            }

            if (state.chatOpen) {
                item {
                    VendorSection(
                        title = "Chat with the customer",
                        subtitle = chatSubtitle(state)
                    ) {
                        Column {
                            if (state.messages.isEmpty()) {
                                Text(
                                    text = "No messages yet. A quick word goes a long way when an order runs late.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            state.messages.forEach { message ->
                                val mine = message.fromKitchen
                                Surface(
                                    color = if (mine) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(UndalDimens.radiusMd),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(UndalDimens.xs)) {
                                        Text(
                                            text = if (mine) "Kitchen" else "Customer",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
                                        if (message.tsMillis > 0L) {
                                            Text(
                                                text = TimeDhaka.formatInstant(
                                                    java.time.Instant.ofEpochMilli(message.tsMillis)
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(UndalDimens.xs))
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                QUICK_REPLIES.forEach { quick ->
                                    VendorChip(
                                        label = quick,
                                        selected = false,
                                        onClick = { viewModel.onMessageChange(quick) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(UndalDimens.xs))
                            UndalTextField(
                                value = state.messageText,
                                onValueChange = viewModel::onMessageChange,
                                label = "Message",
                                hint = "The rider is five minutes away.",
                                maxLines = 3
                            )
                            Spacer(Modifier.height(UndalDimens.xs))
                            UndalPrimaryButton(
                                text = "Send",
                                onClick = viewModel::sendMessage,
                                enabled = state.messageText.isNotBlank(),
                                icon = Icons.Filled.Email
                            )
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------- sheets
        when (state.sheet) {
            VendorOrderDetailViewModel.Sheet.Accept -> AlertDialog(
                onDismissRequest = viewModel::dismissSheet,
                title = { Text("Accept this order?") },
                text = {
                    Column {
                        Text(
                            text = "The customer will be told you accepted, and the countdown starts " +
                                "the moment you choose a prep time. Cash to collect: " +
                                Money.format(order.totalAmountPaisa) + ".",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(UndalDimens.sm))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            VendorOrderDetailViewModel.PREP_CHOICES.forEach { minutes ->
                                VendorChip(
                                    label = "$minutes min",
                                    selected = state.prepMinutes == minutes && state.customPrepText.isBlank(),
                                    onClick = { viewModel.onPrepSelected(minutes) }
                                )
                            }
                        }
                        Spacer(Modifier.height(UndalDimens.xs))
                        UndalTextField(
                            value = state.customPrepText,
                            onValueChange = { viewModel.onCustomPrepChange(UndalDigits.digitsOnly(it, 3)) },
                            label = "Or type your own minutes",
                            hint = "35",
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            haptics.tick()
                            viewModel.confirmAccept()
                        }
                    ) {
                        Text("Accept in ${viewModel.effectivePrepMinutes()} min")
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissSheet) { Text("Not yet") }
                }
            )

            VendorOrderDetailViewModel.Sheet.Reject -> AlertDialog(
                onDismissRequest = viewModel::dismissSheet,
                title = { Text("Cancel this order?") },
                text = {
                    Column {
                        Text(
                            text = "The customer reads your words. Nothing is charged — Undal is cash on " +
                                "delivery, so a cancelled order costs them nothing but time.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(UndalDimens.sm))
                        VendorOrderDetailViewModel.REJECT_TEMPLATES.forEach { template ->
                            VendorChip(
                                label = template.take(34) + "…",
                                selected = state.rejectTemplate == template,
                                onClick = { viewModel.onRejectTemplate(template) },
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.height(UndalDimens.xs))
                        UndalTextField(
                            value = state.rejectNote,
                            onValueChange = viewModel::onRejectNote,
                            label = "Note the customer will read",
                            hint = "We ran out of the bhorta rice tonight.",
                            maxLines = 3
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            haptics.tick()
                            viewModel.confirmReject()
                        },
                        enabled = state.rejectNote.trim().length >= 3
                    ) {
                        Text("Cancel the order")
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissSheet) { Text("Keep it") }
                }
            )

            VendorOrderDetailViewModel.Sheet.Ready -> UndalConfirmDialog(
                title = "Is the order ready?",
                message = "This tells the customer the food is packed and waiting. The prep sound stops " +
                    "on this phone.",
                confirmLabel = "Yes, it is ready",
                destructive = false,
                onConfirm = {
                    haptics.tick()
                    viewModel.confirmReady()
                },
                onDismiss = viewModel::dismissSheet
            )

            VendorOrderDetailViewModel.Sheet.Delivered -> AlertDialog(
                onDismissRequest = viewModel::dismissSheet,
                title = { Text("Mark delivered") },
                text = {
                    Column {
                        Text(
                            text = "Count the cash before you confirm. The order only closes when the " +
                                "amount matches ${Money.format(order.totalAmountPaisa)} exactly.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(UndalDimens.sm))
                        UndalTextField(
                            value = state.collectedText,
                            onValueChange = { viewModel.onCollectedChange(UndalDigits.cleanDecimal(it)) },
                            label = "Cash collected (taka)",
                            hint = Money.formatPlain(order.totalAmountPaisa),
                            // The customer may hand over ৳305.50; a keypad without a
                            // dot would make that collectable amount unrecordable.
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                        )
                        Spacer(Modifier.height(UndalDimens.xs))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.combinedClickable(
                                onClick = { collectedChecked = !collectedChecked },
                                onLongClick = { collectedChecked = !collectedChecked }
                            )
                        ) {
                            Checkbox(checked = collectedChecked, onCheckedChange = { collectedChecked = it })
                            Spacer(Modifier.width(UndalDimens.xs))
                            Text(
                                text = "I counted the cash in my hand.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            haptics.tick()
                            viewModel.confirmDelivered()
                        },
                        enabled = collectedChecked
                    ) {
                        Text("Delivered")
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissSheet) { Text("Not yet") }
                }
            )

            VendorOrderDetailViewModel.Sheet.None -> Unit
        }

        if (state.celebrate) {
            val collected = Money.format(order.collectedAmountPaisa ?: order.totalAmountPaisa)
            AlertDialog(
                onDismissRequest = viewModel::dismissCelebration,
                icon = { Icon(Icons.Filled.Done, contentDescription = null) },
                title = { Text("Delivered — $collected") },
                text = {
                    Text(
                        text = "Today's till so far is in the dashboard. If the day is over, take the CSV " +
                            "and close the store so nobody orders into a dark kitchen.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    TextButton(onClick = viewModel::dismissCelebration) { Text("Good") }
                }
            )
        }
    }
}

/** One line that says whether the customer is typing, has read, or neither. */
private fun chatSubtitle(state: VendorOrderDetailViewModel.State): String = when {
    state.customerTyping -> "The customer is typing…"
    state.customerSeenAtMillis > 0L -> {
        val seenAt = TimeDhaka.formatInstant(java.time.Instant.ofEpochMilli(state.customerSeenAtMillis))
        "They last read this chat at $seenAt."
    }

    else -> "Text only, exactly like the customer sees it."
}

@Composable
private fun ActionCard(
    state: VendorOrderDetailViewModel.State,
    onReject: () -> Unit
) {
    val order = state.order ?: return
    VendorSection(
        title = "Next step",
        subtitle = when {
            state.busy -> "Sending to the customer…"
            order.isWaiting -> "Answer quickly — the customer is watching the screen."
            order.status == OrderStatus.ACCEPTED -> "Cook, then confirm when it is packed."
            order.status == OrderStatus.READY -> "Hand it to the rider and mark it out."
            order.status == OrderStatus.IN_DELIVERY -> "Collect exactly the cash and close the order."
            else -> "This order is finished. Nothing more to do."
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
            when (order.status) {
                OrderStatus.PLACED, OrderStatus.ACCEPTED -> {
                    UndalSecondaryButton("Cancel with a reason", onClick = onReject, enabled = !state.busy)
                }
                OrderStatus.READY, OrderStatus.IN_DELIVERY -> Unit

                OrderStatus.DELIVERED -> {
                    val closedAt = order.deliveredAtMillis
                        ?.let { TimeDhaka.formatInstant(java.time.Instant.ofEpochMilli(it)) }
                        .orEmpty()
                    Text(
                        text = "Closed at $closedAt.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                OrderStatus.CANCELLED -> {
                    Text(
                        text = "Cancelled. Nothing to cook, nothing to collect.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            if (!OrderStateMachine.isTerminal(order.status)) {
                Text(
                    text = "Review the items and notes before using the action below. Changes need a connection.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private val QUICK_REPLIES = listOf(
    "On the way",
    "Need ten more minutes",
    "Packed and waiting for the rider"
)
