package com.undal.syl.app.features.cart

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.scale
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.undal.syl.app.core.cart.CartLine
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalConfirmDialog
import com.undal.syl.app.core.design.UndalEmptyState
import com.undal.syl.app.core.design.UndalKeyValueRow
import com.undal.syl.app.core.design.UndalPrimaryButton
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.design.UndalSecondaryButton
import com.undal.syl.app.core.design.UndalStatusPill
import com.undal.syl.app.core.design.UndalTextButton
import com.undal.syl.app.core.design.UndalTextField
import com.undal.syl.app.core.maps.UndalLocationMap
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.core.theme.UndalMotion
import com.undal.syl.app.core.util.Money
import com.undal.syl.app.features.store.QuantityStepper

/**
 * Cart and checkout in one screen (C-046 … C-062).
 *
 * The order of the page is the order of the money: what you chose, what it costs,
 * where it goes, what discount applies, what you will hand to the rider — and only
 * then the button. Every blocker is printed above the button with its own reason,
 * so nobody taps a disabled control and wonders why.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CartScreen(
    onBack: () -> Unit,
    onBrowseMenu: () -> Unit,
    onEditAddress: () -> Unit,
    onOpenOrder: (String) -> Unit,
    viewModel: CartViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var couponInput by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshAddress() }

    UndalScaffold(
        title = "Your cart",
        subtitle = if (state.itemCount == 0) "Nothing chosen yet" else "${state.itemCount} item(s)",
        onBack = onBack,
        actions = {
            if (state.lines.isNotEmpty()) {
                TextButton(onClick = { showClearConfirm = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Empty the cart", modifier = Modifier.size(UndalDimens.iconSm))
                }
            }
        },
        bottomBar = {
            if (state.lines.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = UndalDimens.elevationBar) {
                    Column(modifier = Modifier.padding(UndalDimens.screenPadding)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Total", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.weight(1f))
                            Text(state.totalLabel, style = MaterialTheme.typography.titleLarge)
                        }
                        Spacer(Modifier.height(UndalDimens.sm))
                        UndalPrimaryButton(
                            text = when {
                                state.placing -> "Placing your order…"
                                state.blockers.isNotEmpty() -> "Cannot order yet"
                                else -> "Place order · cash on delivery"
                            },
                            onClick = viewModel::placeOrder,
                            enabled = state.canPlace,
                            loading = state.placing,
                            icon = Icons.Filled.Check
                        )
                    }
                }
            }
        }
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = UndalDimens.screenPadding)
        ) {
            if (state.message != null) {
                Spacer(Modifier.height(UndalDimens.sm))
                UndalAlertBanner(
                    text = state.message.orEmpty(),
                    icon = Icons.Filled.Warning,
                    containerColour = MaterialTheme.colorScheme.errorContainer,
                    contentColour = MaterialTheme.colorScheme.onErrorContainer,
                    action = {
                        TextButton(onClick = viewModel::clearMessage) {
                            Text("Close", color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                )
            }

            if (state.priceNotes.isNotEmpty()) {
                Spacer(Modifier.height(UndalDimens.sm))
                UndalAlertBanner(
                    text = state.priceNotes.joinToString(" "),
                    icon = Icons.Filled.Info,
                    containerColour = MaterialTheme.colorScheme.secondaryContainer,
                    contentColour = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            if (state.lastRemoved != null) {
                Spacer(Modifier.height(UndalDimens.sm))
                Surface(
                    shape = RoundedCornerShape(UndalDimens.radiusMd),
                    color = MaterialTheme.colorScheme.inverseSurface
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(UndalDimens.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Removed ${state.lastRemoved?.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = viewModel::undoRemove) {
                            Text("Undo", color = MaterialTheme.colorScheme.inversePrimary)
                        }
                    }
                }
            }

            if (state.loading) {
                Spacer(Modifier.height(UndalDimens.xl))
                Text("Reading your cart…", style = MaterialTheme.typography.bodyLarge)
                return@Column
            }

            if (state.lines.isEmpty()) {
                Spacer(Modifier.height(UndalDimens.xl))
                UndalEmptyState(
                    icon = Icons.Filled.ShoppingCart,
                    title = "Your cart is empty",
                    message = if (state.openState.serviceable) {
                        "The kitchen is open. Pick something from the menu and it will appear here."
                    } else {
                        state.openState.detail
                    },
                    actionLabel = "Browse the menu",
                    onAction = onBrowseMenu
                )
                return@Column
            }

            Spacer(Modifier.height(UndalDimens.sm))

            state.lines.forEach { line ->
                CartLineRow(
                    line = line,
                    unavailable = line.id in state.unavailableLineIds,
                    onIncrement = { viewModel.increment(line) },
                    onDecrement = { viewModel.decrement(line) },
                    onRemove = { viewModel.remove(line) }
                )
                Spacer(Modifier.height(UndalDimens.sm))
            }

            UndalCard {
                Text("Add a note for the kitchen", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                UndalTextField(
                    value = state.note,
                    onValueChange = viewModel::onNoteChange,
                    label = "Order note",
                    hint = "Pack the curry separately, ring the bell twice…",
                    maxLines = 3,
                    singleLine = false,
                    helper = "Optional. Per-dish notes go on the dish page.",
                    imeAction = ImeAction.Done
                )
            }

            Spacer(Modifier.height(UndalDimens.sm))

            // ---- coupon -----------------------------------------------------
            UndalCard {
                Text("Coupon", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                if (state.couponCode.isBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            UndalTextField(
                                value = couponInput,
                                onValueChange = {
                                    // Codes are upper-case A–Z, 0–9, - and _: a
                                    // space pasted from a WhatsApp message is not
                                    // part of the code, and neither is the ৳ sign.
                                    couponInput = it.uppercase()
                                        .filter { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' }
                                        .take(24)
                                },
                                label = "Code",
                                hint = "UNDAL50",
                                enabled = !state.couponChecking,
                                imeAction = ImeAction.Done,
                                onImeAction = {
                                    if (couponInput.isNotBlank()) viewModel.applyCoupon(couponInput)
                                }
                            )
                        }
                        Spacer(Modifier.width(UndalDimens.sm))
                        UndalSecondaryButton(
                            text = if (state.couponChecking) "Checking…" else "Apply",
                            onClick = { viewModel.applyCoupon(couponInput) },
                            enabled = !state.couponChecking && couponInput.isNotBlank(),
                            fillWidth = false
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        UndalStatusPill(
                            text = state.couponCode,
                            containerColour = MaterialTheme.colorScheme.primaryContainer,
                            contentColour = MaterialTheme.colorScheme.onPrimaryContainer,
                            icon = Icons.Filled.Check
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = viewModel::removeCoupon) { Text("Remove") }
                    }
                }
                // The kitchen's own offer, applied without typing anything. It is
                // shown even before a code is entered, because a discount the
                // customer cannot see is a discount they will not believe.
                if (state.autoApplied && state.couponCode.isBlank()) {
                    Spacer(Modifier.height(UndalDimens.xs))
                    UndalStatusPill(
                        text = state.autoHeadline.ifBlank { "Applied automatically" },
                        containerColour = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColour = MaterialTheme.colorScheme.onTertiaryContainer,
                        icon = Icons.Filled.Check
                    )
                    Spacer(Modifier.height(UndalDimens.xs))
                    Text(
                        text = state.autoMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (state.couponMessage != null) {
                    Spacer(Modifier.height(UndalDimens.xs))
                    Text(
                        text = state.couponMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.couponIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }

            Spacer(Modifier.height(UndalDimens.sm))

            // ---- money ------------------------------------------------------
            UndalCard {
                Text("Bill", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                UndalKeyValueRow("Food subtotal", state.subtotalLabel)
                UndalKeyValueRow("Delivery", if (state.distanceKm == null) "—" else state.deliveryLabel)
                if (state.discountPaisa > 0L) {
                    UndalKeyValueRow(
                        "Coupon ${state.couponCode.ifBlank { "applied automatically" }}",
                        state.discountLabel
                    )
                }
                Spacer(Modifier.height(UndalDimens.xs))
                UndalKeyValueRow("Total to pay the rider", state.totalLabel, emphasise = true)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = state.distanceCaption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(UndalDimens.sm))
                Surface(
                    shape = RoundedCornerShape(UndalDimens.radiusSm),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "Cash on delivery only — no card, no wallet, no online payment.",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(UndalDimens.sm)
                    )
                }
            }

            Spacer(Modifier.height(UndalDimens.sm))

            // ---- address ----------------------------------------------------
            UndalCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(UndalDimens.iconMd)
                    )
                    Spacer(Modifier.width(UndalDimens.sm))
                    Text(
                        text = state.address?.title?.ifBlank { "Your delivery address" } ?: "No address saved",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onEditAddress) {
                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(if (state.address == null) "Add" else "Change")
                    }
                }
                val address = state.address
                if (address != null) {
                    Spacer(Modifier.height(UndalDimens.xs))
                    Text(address.desc, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(UndalDimens.xs))
                    Text(
                        text = "Rider note: ${address.note}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(UndalDimens.sm))
                    UndalLocationMap(
                        latitude = address.lat,
                        longitude = address.lng,
                        title = address.title.ifBlank { "Delivery point" },
                        caption = address.title.ifBlank { "Delivery point" },
                        height = UndalDimens.mapHeight
                    )
                }
            }

            if (state.blockers.isNotEmpty()) {
                Spacer(Modifier.height(UndalDimens.sm))
                UndalCard {
                    Text("Before you can order", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(UndalDimens.xs))
                    state.blockers.forEach { blocker ->
                        Row(modifier = Modifier.padding(vertical = 3.dp)) {
                            Icon(
                                Icons.Filled.Clear,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(UndalDimens.xs))
                            Text(blocker, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            Spacer(Modifier.height(UndalDimens.lg))

            UndalTextButton(
                text = "Clear the cart",
                onClick = { showClearConfirm = true },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(UndalDimens.xxl))
        }
    }

    if (showClearConfirm) {
        UndalConfirmDialog(
            title = "Empty the cart?",
            message = "Every line will be removed. This cannot be undone — but the cart is only on this phone.",
            confirmLabel = "Empty the cart",
            destructive = true,
            onConfirm = {
                viewModel.clearCart()
                showClearConfirm = false
            },
            onDismiss = { showClearConfirm = false }
        )
    }

    // ---- success (C-062) ----------------------------------------------------
    val placedId = state.placedOrderId
    if (placedId != null) {
        AlertDialog(
            onDismissRequest = { },
            title = {
                Text(
                    text = "Order ${state.placedShortId} is with the kitchen",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column {
                    // The one celebratory beat in the app (C-062): the badge lands
                    // with a low-bouncy spring instead of a hard pop.
                    var badgeShown by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { badgeShown = true }
                    val badgeScale by animateFloatAsState(
                        targetValue = if (badgeShown) 1f else 0.55f,
                        animationSpec = UndalMotion.gentle(),
                        label = "order-placed-badge"
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .size(UndalDimens.iconLg * 1.5f)
                                .scale(badgeScale)
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(UndalDimens.sm)
                            )
                        }
                        Spacer(Modifier.width(UndalDimens.sm))
                        Text(
                            text = "Payment on delivery",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Spacer(Modifier.height(UndalDimens.sm))
                    Text(
                        text = "The kitchen has been alerted on their phone and usually accepts within " +
                            "a minute or two — as soon as it accepts, your prep countdown starts on " +
                            "the order page. You will pay ${state.placedTotalLabel} in cash when the " +
                            "food arrives.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(UndalDimens.sm))
                    Text(
                        text = "Keep the app or the notification reachable: every status change is pushed " +
                            "to you, and you can chat with the kitchen inside the order.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissSuccess()
                        onOpenOrder(placedId)
                    }
                ) {
                    Text("Follow the order", style = MaterialTheme.typography.labelLarge)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CartLineRow(
    line: CartLine,
    unavailable: Boolean,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onRemove: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onRemove()
                true
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(UndalDimens.radiusMd)),
                contentAlignment = Alignment.CenterEnd
            ) {
                Row(
                    modifier = Modifier.padding(UndalDimens.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Remove",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    ) {
        Surface(
            shape = RoundedCornerShape(UndalDimens.radiusMd),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = UndalDimens.elevationCard
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(UndalDimens.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(UndalDimens.radiusSm))
                ) {
                    if (line.imageUrl.isNotBlank()) {
                        AsyncImage(
                            model = line.imageUrl,
                            contentDescription = line.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.Filled.ShoppingCart,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).size(UndalDimens.iconSm)
                        )
                    }
                }
                Spacer(Modifier.width(UndalDimens.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = line.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${Money.format(line.unitAmountPaisa)} each",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = if (unavailable) TextDecoration.LineThrough else TextDecoration.None
                    )
                    if (line.note.isNotBlank()) {
                        Text(
                            text = "Note: ${line.note}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (unavailable) {
                        Spacer(Modifier.height(2.dp))
                        UndalStatusPill(
                            text = "Finished for today",
                            containerColour = MaterialTheme.colorScheme.errorContainer,
                            contentColour = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Spacer(Modifier.height(UndalDimens.xs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        QuantityStepper(
                            quantity = line.quantity,
                            onAdd = onIncrement,
                            onRemove = onDecrement,
                            enabled = !unavailable
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = Money.format(line.lineTotalPaisa),
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                }
            }
        }
    }
}
