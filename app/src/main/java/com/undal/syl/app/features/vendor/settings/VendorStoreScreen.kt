package com.undal.syl.app.features.vendor.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.undal.syl.app.core.config.UndalBuildInfo
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalConfirmDialog
import com.undal.syl.app.core.design.UndalKeyValueRow
import com.undal.syl.app.core.design.UndalPrimaryButton
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.design.UndalSecondaryButton
import com.undal.syl.app.core.design.UndalStatusPill
import com.undal.syl.app.core.design.UndalTextField
import com.undal.syl.app.core.design.UndalTextButton
import com.undal.syl.app.core.maps.UndalOsmPicker
import com.undal.syl.app.core.maps.UndalRadiusPreview
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.core.theme.undalSemantics
import com.undal.syl.app.core.util.Money
import com.undal.syl.app.core.util.UndalDigits
import com.undal.syl.app.features.vendor.VendorSection
import com.undal.syl.app.features.vendor.menu.VendorPhotoRow
import com.undal.syl.app.features.vendor.storeShareText

/**
 * Store settings (V-044, V-079 … V-086, V-091 … V-093, V-095, V-096, V-110).
 *
 * Four things live on this screen, in the order they cost money: who the store is,
 * what delivery costs, where the kitchen is, and whether this phone will actually
 * shout when an order arrives. Logout sits at the very bottom, behind a question.
 */
@Composable
fun VendorStoreScreen(
    onBack: () -> Unit,
    onOpenPolicy: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onPreviewAsCustomer: () -> Unit,
    onSignOut: () -> Unit,
    viewModel: VendorStoreViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmCleanup by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var shareText by remember { mutableStateOf("") }

    if (confirmCleanup) {
        UndalConfirmDialog(
            title = "Permanently clear finished history?",
            message = "This deletes all delivered and cancelled orders, their chats and live mirrors, plus this kitchen account’s notifications created before cleanup starts. These orders disappear from customer history and sales reports too. Active orders, menu, accounts and device tokens are kept. Export records first. This cannot be undone.",
            confirmLabel = "Delete finished history",
            destructive = true,
            onConfirm = { confirmCleanup = false; viewModel.cleanupHistory() },
            onDismiss = { confirmCleanup = false }
        )
    }

    UndalScaffold(
        title = "Store settings",
        subtitle = state.openState.headline.ifBlank { "Sylhet · Asia/Dhaka" },
        onBack = onBack,
        actions = {
            UndalTextButton(
                text = "Preview",
                onClick = onPreviewAsCustomer,
                icon = Icons.Filled.ShoppingCart
            )
        }
    ) { _ ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(UndalDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(UndalDimens.sm)
        ) {
            item {
                VendorSection(title = "Storage & history", subtitle = "Keep your kitchen database lean without touching live orders.") {
                    Column(verticalArrangement = Arrangement.spacedBy(UndalDimens.md)) {
                        Text("Permanently delete delivered and cancelled orders, their chats, live mirrors, and this kitchen account’s notification history. Customer order history and sales reports will also lose those orders. Export records you need first.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        state.cleanupProgress?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                        state.cleanupNotice?.let { notice ->
                            UndalAlertBanner(
                                text = notice,
                                icon = Icons.Filled.Info
                            )
                        }
                        UndalSecondaryButton(
                            text = if (state.cleaning) "Cleaning history…" else "Clear finished history",
                            onClick = { confirmCleanup = true },
                            enabled = !state.busy && state.vendorClaim
                        )
                        UndalTextButton(
                            text = if (state.checkingCleanup) "Checking the history path…" else "Check first — delete nothing",
                            onClick = viewModel::checkCleanupPath,
                            enabled = !state.busy && state.vendorClaim,
                            icon = Icons.Filled.Check
                        )
                        Text(
                            "The check asks the Worker to walk the whole path — the Firestore index, the slower scan that replaces it, the live mirrors and the notification history — and report what it finds without deleting a single order.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (!state.vendorClaim) {
                item {
                    UndalAlertBanner(
                        text = "This account is signed in without the kitchen claim. Orders will not be " +
                            "accepted until you sign in with the kitchen account.",
                        icon = Icons.Filled.Warning
                    )
                }
            }

            item {
                VendorSection(
                    title = "The store",
                    subtitle = "The name, banner and logo every customer sees on the store page."
                ) {
                    Column {
                        UndalTextField(
                            value = state.nameText,
                            onValueChange = viewModel::onName,
                            label = "Store name",
                            hint = "Undal HomeKitchen",
                            singleLine = true
                        )
                        Spacer(Modifier.height(UndalDimens.xs))
                        VendorPhotoRow(
                            currentUrl = state.store.bannerUrl,
                            progress = state.uploadProgress,
                            label = "Banner",
                            onPicked = viewModel::uploadBanner
                        )
                        Spacer(Modifier.height(UndalDimens.xs))
                        VendorPhotoRow(
                            currentUrl = state.store.logoUrl,
                            progress = state.uploadProgress,
                            label = "Logo",
                            onPicked = viewModel::uploadLogo
                        )
                        Spacer(Modifier.height(UndalDimens.xs))
                        UndalPrimaryButton(
                            text = "Save the store profile",
                            onClick = viewModel::saveProfile,
                            enabled = !state.busy && state.uploadProgress == null,
                            loading = state.busy,
                            icon = Icons.Filled.Check
                        )
                    }
                }
            }

            item {
                VendorSection(
                    title = "Delivery money",
                    subtitle = "Fee per whole kilometre, the radius you deliver inside, and the smallest " +
                        "basket you will cook."
                ) {
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                            UndalTextField(
                                value = state.perKmText,
                                onValueChange = { viewModel.onPerKm(UndalDigits.cleanDecimal(it)) },
                                label = "Per km (taka)",
                                hint = "20",
                                keyboardType = KeyboardType.Decimal,
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            UndalTextField(
                                value = state.minOrderText,
                                onValueChange = { viewModel.onMinOrder(UndalDigits.cleanDecimal(it)) },
                                label = "Minimum basket (taka)",
                                hint = "150",
                                keyboardType = KeyboardType.Decimal,
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(UndalDimens.sm))
                        Text(
                            text = "Delivery radius: ${UndalDigits.formatWhole(state.radiusKm)} km",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Slider(
                            value = state.radiusKm.toFloat(),
                            onValueChange = { viewModel.onRadius(it.toDouble()) },
                            valueRange = 1f..15f,
                            steps = 13
                        )
                        UndalRadiusPreview(
                            storeLat = if (state.store.hasPublishedPin) state.store.storeLat else state.pinLat,
                            storeLng = if (state.store.hasPublishedPin) state.store.storeLng else state.pinLng,
                            radiusKm = state.radiusKm,
                            height = UndalDimens.mapHeight
                        )
                        Spacer(Modifier.height(UndalDimens.xs))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.undalSemantics.warning,
                                modifier = Modifier.size(UndalDimens.iconSm)
                            )
                            Spacer(Modifier.width(UndalDimens.xs))
                            Text(
                                text = viewModel.radiusNotice(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(UndalDimens.sm))
                        UndalTextField(
                            value = state.feeDistanceText,
                            onValueChange = { viewModel.onFeeDistance(UndalDigits.cleanDecimal(it, maxDecimals = 1)) },
                            label = "Fee calculator — type a distance in km",
                            hint = "4.5",
                            // A distance is a decimal: the kilometres the fee is
                            // rounded up from, never a whole number by assumption.
                            keyboardType = KeyboardType.Decimal,
                            singleLine = true
                        )
                        Spacer(Modifier.height(UndalDimens.xs))
                        UndalStatusPill(
                            text = viewModel.feePreview(),
                            containerColour = MaterialTheme.colorScheme.surfaceVariant,
                            contentColour = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(UndalDimens.xs))
                        UndalPrimaryButton(
                            text = "Save delivery settings",
                            onClick = viewModel::saveDelivery,
                            enabled = !state.busy,
                            loading = state.busy,
                            icon = Icons.Filled.Check
                        )
                    }
                }
            }

            item {
                VendorSection(
                    title = "Where the kitchen is",
                    subtitle = "Every distance and fee is measured from this pin. Move it if the map is off."
                ) {
                    Column {
                        UndalOsmPicker(
                            latitude = if (state.pinLat == 0.0) 24.8949 else state.pinLat,
                            longitude = if (state.pinLng == 0.0) 91.8687 else state.pinLng,
                            onPicked = viewModel::onPinPicked,
                            recentreLabel = "My kitchen",
                            onRecentre = null
                        )
                        Spacer(Modifier.height(UndalDimens.xs))
                        Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                            UndalSecondaryButton(
                                text = if (state.locating) "Finding this phone…" else "Use this phone's GPS",
                                onClick = { viewModel.useCurrentLocation(context) },
                                enabled = !state.locating,
                                icon = Icons.Filled.Place,
                                modifier = Modifier.weight(1f)
                            )
                            UndalPrimaryButton(
                                text = "Save the pin",
                                onClick = viewModel::savePin,
                                enabled = !state.busy,
                                loading = state.busy,
                                icon = Icons.Filled.LocationOn,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(UndalDimens.xs))
                        UndalTextField(
                            value = state.pinLabel,
                            onValueChange = viewModel::onPinLabel,
                            label = "Address customers read",
                            hint = "Zindabazar, Sylhet — beside the mosque",
                            maxLines = 2
                        )
                        Spacer(Modifier.height(UndalDimens.xs))
                        Text(
                            text = "Pin: ${UndalDigits.format(state.pinLat, 5)}, ${UndalDigits.format(state.pinLng, 5)}" +
                                if (state.store.hasPublishedPin) {
                                    " · published"
                                } else {
                                    " · this pin is not published yet"
                                },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                VendorSection(
                    title = "Sounds and alerts",
                    subtitle = "An order the kitchen does not hear is an order the customer cancels."
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
                        UndalSecondaryButton(
                            text = "Test the order alert",
                            onClick = { viewModel.testOrderSound(context) },
                            icon = Icons.Filled.Notifications
                        )
                        UndalSecondaryButton(
                            text = "Test the preparation tick",
                            onClick = { viewModel.testTickSound(context) },
                            icon = Icons.Filled.Notifications
                        )
                        UndalTextButton(
                            text = "Open device diagnostics",
                            onClick = onOpenDiagnostics,
                            icon = Icons.Filled.Build
                        )
                        Text(
                            text = "The alert repeats the real order sound until the order is answered. " +
                                "The preparation tick is a soft ten-second tone used only while a countdown " +
                                "is running.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                VendorSection(
                    title = "This build",
                    subtitle = "What the owner reports when something looks wrong."
                ) {
                    Column {
                        UndalKeyValueRow("Version", UndalBuildInfo.versionName)
                        UndalKeyValueRow("Build", UndalBuildInfo.versionCode.toString())
                        UndalKeyValueRow("Worker", UndalBuildInfo.workerHost)
                        UndalKeyValueRow("Timezone", UndalBuildInfo.storeTimezoneId)
                        Spacer(Modifier.height(UndalDimens.xs))
                        UndalSecondaryButton(
                            text = "Share the store link",
                            onClick = {
                                val name = state.nameText.ifBlank { "Undal HomeKitchen" }
                                shareText = storeShareText(name, UndalBuildInfo.workerHost)
                            },
                            icon = Icons.Filled.Share
                        )
                        Spacer(Modifier.height(UndalDimens.xs))
                        UndalTextButton(
                            text = "Privacy and policy pages",
                            onClick = onOpenPolicy,
                            icon = Icons.Filled.Info
                        )
                    }
                }
            }

            item {
                UndalCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(UndalDimens.sm)) {
                        Text(text = "Signing out", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Signing out stops the alert service, the presence heartbeat and the " +
                                "cron worker on this phone, so the store goes offline until you are back.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(UndalDimens.xs))
                        UndalSecondaryButton(
                            text = "Sign out",
                            onClick = { confirmSignOut = true },
                            icon = Icons.Filled.ExitToApp
                        )
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(UndalDimens.iconSm)
                    )
                    Spacer(Modifier.width(UndalDimens.xs))
                    Text(
                        text = "Undal HomeKitchen · one kitchen, one phone, cash on delivery.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        state.message?.let { message ->
            UndalAlertBanner(
                text = message,
                icon = Icons.Filled.Check,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(UndalDimens.screenPadding)
            )
        }

        if (confirmSignOut) {
            UndalConfirmDialog(
                title = "Sign out of the kitchen?",
                message = "New orders will ring nowhere until you sign back in. The store document is not " +
                    "touched — it stays exactly as you left it.",
                confirmLabel = "Sign out",
                destructive = true,
                onConfirm = {
                    confirmSignOut = false
                    onSignOut()
                },
                onDismiss = { confirmSignOut = false }
            )
        }

    }

    LaunchedEffect(shareText) {
        if (shareText.isBlank()) return@LaunchedEffect
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        context.startActivity(Intent.createChooser(intent, "Share the store link"))
        shareText = ""
    }
}
