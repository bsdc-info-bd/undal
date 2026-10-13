package com.undal.syl.app.features.portal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.undal.syl.app.core.background.OrderAlertController
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalDangerButton
import com.undal.syl.app.core.design.UndalKeyValueRow
import com.undal.syl.app.core.design.UndalPrimaryButton
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.design.UndalSecondaryButton
import com.undal.syl.app.core.design.UndalStatusPill
import com.undal.syl.app.core.design.UndalTextField
import com.undal.syl.app.core.maps.UndalGoogleEmbedMap
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.core.util.Distance
import com.undal.syl.app.core.util.Money
import com.undal.syl.app.core.util.OrderStateMachine
import com.undal.syl.app.core.util.OrderStatus
import com.undal.syl.app.core.util.TimeDhaka
import com.undal.syl.app.core.util.UndalPhone

/**
 * One order, fully actionable (V-023 … V-034).
 *
 * Reached three ways: the ringing alert, the order queue, or a push deep link.
 * The screen never offers an impossible action — the buttons come straight from
 * the shared state machine, so what is on screen and what the database will
 * accept can never disagree.
 */
@Composable
fun VendorOrderStage(
    orderId: String,
    onBack: () -> Unit,
    sheet: String? = null,
    viewModel: VendorOrderViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val ringing by OrderAlertController.ringing.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(orderId) { viewModel.bind(orderId) }
    LaunchedEffect(sheet) { viewModel.openSheetFor(sheet) }

    val order = state.order

    UndalScaffold(
        title = order?.shortId?.ifBlank { orderId } ?: "Order",
        subtitle = order?.let { OrderStateMachine.label(it.status) } ?: "loading…",
        onBack = onBack,
        actions = {
            if (ringing?.orderId == orderId) {
                UndalStatusPill(
                    text = "RINGING",
                    containerColour = MaterialTheme.colorScheme.errorContainer,
                    contentColour = MaterialTheme.colorScheme.onErrorContainer,
                    icon = Icons.Filled.Notifications
                )
            }
        }
    ) { _ ->
        if (order == null) {
            Column(modifier = Modifier.fillMaxSize().padding(UndalDimens.screenPadding)) {
                Text(
                    text = if (state.loading) "Loading the order…" else "This order is not available on this account.",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            return@UndalScaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(UndalDimens.screenPadding)
        ) {
            if (state.message != null) {
                UndalAlertBanner(
                    text = state.message.orEmpty(),
                    icon = Icons.Filled.Info,
                    containerColour = if (state.messageIsError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    contentColour = if (state.messageIsError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    }
                )
                Spacer(Modifier.height(UndalDimens.sm))
            }

            if (ringing?.orderId == orderId) {
                UndalCard {
                    Text("This order is still ringing", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(UndalDimens.xs))
                    Text(
                        text = "The sound stops the moment the order is accepted or rejected — on this " +
                            "phone or on the other kitchen phone.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(UndalDimens.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.sm)) {
                        Column(modifier = Modifier.weight(1f)) {
                            UndalPrimaryButton(
                                text = "Accept…",
                                onClick = viewModel::openAcceptSheet,
                                icon = Icons.Filled.CheckCircle
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            UndalDangerButton(text = "Reject…", onClick = viewModel::openRejectSheet)
                        }
                    }
                }
                Spacer(Modifier.height(UndalDimens.sm))
            }

            UndalCard {
                Text("Order ${order.shortId}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                UndalKeyValueRow("Status", OrderStateMachine.label(order.status), emphasise = true)
                UndalKeyValueRow("Items", order.itemCount.toString())
                UndalKeyValueRow("Cash to collect", Money.format(order.totalAmountPaisa), emphasise = true)
                UndalKeyValueRow("Distance", Distance.formatKm(order.distanceKm))
                if (order.prepEndsAt != null) {
                    UndalKeyValueRow("Ready by", TimeDhaka.formatInstant(order.prepEndsAt))
                }
            }

            Spacer(Modifier.height(UndalDimens.sm))

            UndalCard {
                Text("Customer", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                UndalKeyValueRow("Name", order.customerName)
                UndalKeyValueRow("Phone", order.phone)
                UndalKeyValueRow("Address", order.addressTitle)
                Text(order.addressDesc, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = order.addressNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(UndalDimens.sm))
                UndalSecondaryButton(
                    text = "Call the customer",
                    onClick = { UndalPhone.dial(context, order.phone) },
                    icon = Icons.Filled.Phone
                )
            }

            Spacer(Modifier.height(UndalDimens.sm))

            UndalCard {
                Text("Where it is going", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "Distance from the kitchen: ${Distance.formatKm(order.distanceKm)} · cash on delivery",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(UndalDimens.sm))
                UndalGoogleEmbedMap(
                    latitude = order.customerLat,
                    longitude = order.customerLng,
                    title = order.addressTitle,
                    height = UndalDimens.mapHeight
                )
            }

            Spacer(Modifier.height(UndalDimens.md))

            // Actions come straight from the state machine: no impossible taps.
            when (order.status) {
                OrderStatus.PLACED -> {
                    UndalPrimaryButton(
                        text = "Accept with a prep time",
                        onClick = viewModel::openAcceptSheet,
                        icon = Icons.Filled.CheckCircle
                    )
                    Spacer(Modifier.height(UndalDimens.sm))
                    UndalDangerButton(text = "Reject this order", onClick = viewModel::openRejectSheet)
                }

                OrderStatus.ACCEPTED -> {
                    UndalPrimaryButton(
                        text = "Is the order ready?",
                        onClick = viewModel::openReadyConfirm
                    )
                    Spacer(Modifier.height(UndalDimens.sm))
                    UndalDangerButton(text = "Cancel with a note", onClick = viewModel::openRejectSheet)
                }

                OrderStatus.READY -> {
                    UndalPrimaryButton(text = "Hand it to the rider", onClick = viewModel::markInDelivery)
                }

                OrderStatus.IN_DELIVERY -> {
                    UndalPrimaryButton(text = "Cash collected — delivered", onClick = viewModel::openDeliveredSheet)
                }

                OrderStatus.DELIVERED -> {
                    UndalAlertBanner(
                        text = "Delivered. Thank you — the customer has been told.",
                        icon = Icons.Filled.CheckCircle,
                        containerColour = MaterialTheme.colorScheme.primaryContainer,
                        contentColour = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                OrderStatus.CANCELLED -> {
                    UndalAlertBanner(
                        text = "This order was cancelled. The customer can read the note you wrote.",
                        icon = Icons.Filled.Info
                    )
                }
            }

            Spacer(Modifier.height(UndalDimens.xl))
        }
    }

    if (state.showAcceptSheet) {
        AlertDialog(
            onDismissRequest = viewModel::dismissSheets,
            title = { Text("When will it be ready?") },
            text = {
                Column {
                    Text(
                        text = "The customer sees this promise, and a ticking notification keeps you honest.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(UndalDimens.xs))
                    Text(
                        text = "Cash on delivery: ${Money.format(order?.totalAmountPaisa ?: 0L)} " +
                            "for ${order?.itemCount ?: 0} item(s).",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(UndalDimens.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                        OrderStateMachine.prepMinuteOptions.take(3).forEach { minutes ->
                            FilterChip(
                                selected = state.selectedPrepMinutes == minutes && state.customPrepMinutes.isBlank(),
                                onClick = { viewModel.selectPrepMinutes(minutes) },
                                label = { Text("$minutes") }
                            )
                        }
                    }
                    Spacer(Modifier.height(UndalDimens.xs))
                    Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                        OrderStateMachine.prepMinuteOptions.drop(3).forEach { minutes ->
                            FilterChip(
                                selected = state.selectedPrepMinutes == minutes && state.customPrepMinutes.isBlank(),
                                onClick = { viewModel.selectPrepMinutes(minutes) },
                                label = { Text("$minutes") }
                            )
                        }
                    }
                    Spacer(Modifier.height(UndalDimens.sm))
                    UndalTextField(
                        value = state.customPrepMinutes,
                        onValueChange = viewModel::onCustomPrepChange,
                        label = "Custom minutes",
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                        helper = "Between 1 and 180 minutes"
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmAccept) {
                    Text("Accept ${viewModel.effectivePrepMinutes()} min", style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissSheets) { Text("Not yet") }
            }
        )
    }

    if (state.showRejectSheet) {
        AlertDialog(
            onDismissRequest = viewModel::dismissSheets,
            title = { Text("Why is this order being cancelled?") },
            text = {
                Column {
                    Text(
                        text = "The customer reads this reason word for word in the app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(UndalDimens.sm))
                    OrderStateMachine.rejectTemplates.forEach { template ->
                        FilterChip(
                            selected = state.rejectTemplate == template,
                            onClick = { viewModel.onRejectTemplateChange(template) },
                            label = { Text(template) },
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.height(UndalDimens.sm))
                    UndalTextField(
                        value = state.rejectReason,
                        onValueChange = viewModel::onRejectReasonChange,
                        label = "Note for the customer",
                        hint = "The hilsa is finished for today — sorry about that.",
                        error = state.rejectError,
                        maxLines = 3,
                        singleLine = false,
                        imeAction = ImeAction.Done
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmReject) {
                    Text("Cancel the order", style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissSheets) { Text("Keep cooking") }
            }
        )
    }

    if (state.showReadyConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissSheets,
            title = { Text("Is the order ready?") },
            text = {
                Text(
                    text = "Confirm only when the food is packed and waiting: the customer is told the " +
                        "instant you tap, and the prep countdown stops.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmReady) { Text("Yes, it is ready") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissSheets) { Text("Not yet") }
            }
        )
    }

    if (state.showDeliveredConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissSheets,
            title = { Text("Cash collected?") },
            text = {
                Column {
                    Text(
                        text = "Money in hand must match the order exactly before it is closed.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(UndalDimens.sm))
                    UndalTextField(
                        value = state.collectedInput,
                        onValueChange = viewModel::onCollectedChange,
                        label = "Collected (৳)",
                        keyboardType = KeyboardType.Decimal,
                        helper = "Order total ${Money.format(order?.totalAmountPaisa ?: 0L)}"
                    )
                    Spacer(Modifier.height(UndalDimens.xs))
                    UndalSecondaryButton(text = "Fill the exact amount", onClick = viewModel::prefillCollected)
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelivered) { Text("Delivered and recorded") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissSheets) { Text("Not yet") }
            }
        )
    }
}
