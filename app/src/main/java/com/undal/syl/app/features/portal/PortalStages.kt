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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.undal.syl.app.core.config.UndalBuildInfo
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalKeyValueRow
import com.undal.syl.app.core.design.UndalPrimaryButton
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.design.UndalSecondaryButton
import com.undal.syl.app.core.design.UndalStatusPill
import com.undal.syl.app.core.design.UndalTextButton
import com.undal.syl.app.core.theme.UndalDimens

/**
 * Honest, working shells for the two portals.
 *
 * Response 2 ships the account, the address, the push pipeline and the alert
 * loop; the ordering screens and the kitchen console arrive in Responses 3 and 4.
 * Until then these stages do real work — they verify the session, register the
 * device, watch the heartbeat, resend the verification email and let both roles
 * reach the diagnostics, the policy pages and sign-out — instead of showing an
 * empty page.
 */
@Composable
fun CustomerHomeStage(
    onOpenSupport: () -> Unit
) {
    val vm: PortalViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.refresh(context) }

    UndalScaffold(
        title = state.greeting,
        subtitle = state.email.ifBlank { "Signed in" },
        actions = {
            UndalTextButton(text = "Sign out", onClick = { vm.signOut(context) }, icon = Icons.Filled.ExitToApp)
        }
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(UndalDimens.screenPadding)
        ) {
            if (state.loading) {
                UndalCard { Text("Checking your account…", style = MaterialTheme.typography.bodyLarge) }
                return@Column
            }

            if (!state.emailVerified) {
                UndalCard {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        UndalStatusPill(
                            text = "Verify email",
                            containerColour = MaterialTheme.colorScheme.secondaryContainer,
                            contentColour = MaterialTheme.colorScheme.onSecondaryContainer,
                            icon = Icons.Filled.Email
                        )
                    }
                    Spacer(Modifier.height(UndalDimens.sm))
                    Text(
                        text = "We sent a verification link to ${state.email}. Confirming it keeps password " +
                            "recovery reliable — ordering works either way.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(UndalDimens.sm))
                    UndalSecondaryButton(text = "Resend the link", onClick = vm::resendVerification)
                    if (state.infoMessage != null) {
                        Spacer(Modifier.height(UndalDimens.xs))
                        Text(state.infoMessage.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(UndalDimens.sm))
            }

            UndalCard {
                Text("Your account is ready", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "The address you pinned is what the kitchen uses to check the delivery radius and " +
                        "to calculate the per-kilometre fee.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(UndalDimens.sm))
                UndalKeyValueRow("Saved address", state.addressSummary.ifBlank { "Not set yet" })
                UndalKeyValueRow("Notifications", if (state.notificationsEnabled) "Allowed" else "Blocked")
            }

            Spacer(Modifier.height(UndalDimens.sm))

            UndalAlertBanner(
                text = "The store, cart and live order tracking are the next build step. Everything you have " +
                    "set up now — account, address, notifications — is already live and will be used " +
                    "unchanged when ordering opens.",
                icon = Icons.Filled.Build
            )

            Spacer(Modifier.height(UndalDimens.sm))

            UndalCard {
                Text("Open the kitchen on the web", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = UndalBuildInfo.workerBaseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(UndalDimens.sm))
                UndalSecondaryButton(
                    text = "Delivery, payment and privacy",
                    onClick = onOpenSupport,
                    icon = Icons.Filled.Info
                )
            }

            Spacer(Modifier.height(UndalDimens.lg))
        }
    }
}

@Composable
fun VendorConsoleStage(
    onOpenDiagnostics: () -> Unit,
    onOpenOrders: () -> Unit
) {
    val vm: PortalViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.refresh(context) }

    UndalScaffold(
        title = "Kitchen console",
        subtitle = "Undal HomeKitchen · Sylhet",
        actions = {
            UndalTextButton(text = "Sign out", onClick = { vm.signOut(context) }, icon = Icons.Filled.ExitToApp)
        }
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(UndalDimens.screenPadding)
        ) {
            UndalCard {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    UndalStatusPill(
                        text = if (state.notificationsEnabled) "Alerts on" else "Alerts off",
                        containerColour = if (state.notificationsEnabled) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        },
                        contentColour = if (state.notificationsEnabled) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                        icon = Icons.Filled.Notifications
                    )
                    Spacer(Modifier.width(UndalDimens.sm))
                    UndalStatusPill(
                        text = "Heartbeat ${state.heartbeatLabel}",
                        containerColour = MaterialTheme.colorScheme.surfaceVariant,
                        contentColour = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(UndalDimens.sm))
                UndalKeyValueRow("Session", state.sessionSummary)
                UndalKeyValueRow("Device token", state.tokenTail.ifBlank { "registering…" })
            }

            Spacer(Modifier.height(UndalDimens.sm))

            UndalCard {
                Text("Order command centre", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "The live queue, the menu manager, the schedule builder and the campaign tools are " +
                        "the next build steps. The alert loop is already live: a new order rings this phone, " +
                        "and answering it here writes the status the customer sees.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(UndalDimens.sm))
                UndalPrimaryButton(
                    text = "Open the order queue",
                    onClick = onOpenOrders,
                    icon = Icons.Filled.ShoppingCart
                )
                Spacer(Modifier.height(UndalDimens.sm))
                UndalSecondaryButton(
                    text = "Device diagnostics",
                    onClick = onOpenDiagnostics,
                    icon = Icons.Filled.Build
                )
            }

            Spacer(Modifier.height(UndalDimens.sm))

            UndalCard {
                Text("Before a service", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "Run the diagnostics once per phone and once after any system update. Battery " +
                        "exemptions and autostart settings are the two that quietly break order alerts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(UndalDimens.lg))
        }
    }
}

@Composable
fun SupportScreen(onBack: () -> Unit) {
    UndalScaffold(title = "Delivery, payment and privacy", onBack = onBack) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(UndalDimens.screenPadding)
        ) {
            UndalCard {
                Text("How delivery works", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "One kitchen in Sylhet delivers itself. The fee is calculated per kilometre from " +
                        "the kitchen, rounded up to whole kilometres, and you see the exact amount before " +
                        "you place the order. Outside our radius we cannot deliver, and the app tells you " +
                        "that plainly instead of taking an order we cannot serve.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(UndalDimens.sm))
            UndalCard {
                Text("Payment", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "Cash on delivery only. There is no card, no wallet and no online payment in " +
                        "Undal, so nobody ever asks you for card details. You pay the rider when the food " +
                        "reaches your door, and the amount is fixed at the moment you order.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(UndalDimens.sm))
            UndalCard {
                Text("Your data", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "Undal stores your name, email, phone number and the one delivery address you " +
                        "pinned. The address is used to check the delivery radius, to calculate the fee and " +
                        "to show the rider where to go. The app does not track your location in the " +
                        "background — it only reads it when you press the location button, and never after " +
                        "you close the app.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(UndalDimens.sm))
                Text(
                    text = "You can delete your account and every document attached to it from the profile " +
                        "screen. Order records without your account are kept only as the kitchen's sales " +
                        "history.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(UndalDimens.sm))
            UndalCard {
                Text("Cancelling", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "Once you place an order, only the kitchen can cancel it — because the cooking has " +
                        "already been planned around it. Tell the kitchen in the order chat and they will " +
                        "cancel with a note you can read.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(UndalDimens.sm))
            UndalCard {
                Text("Contact the kitchen", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                UndalKeyValueRow(
                    "Support number",
                    UndalBuildInfo.supportPhone.ifBlank { "Set the SUPPORT_PHONE repository secret" }
                )
                UndalKeyValueRow("App version", UndalBuildInfo.versionName)
            }
            Spacer(Modifier.height(UndalDimens.xl))
        }
    }
}
