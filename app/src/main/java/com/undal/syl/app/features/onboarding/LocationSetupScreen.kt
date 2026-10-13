package com.undal.syl.app.features.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.navigation.compose.hiltViewModel
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalConfirmDialog
import com.undal.syl.app.core.design.UndalPrimaryButton
import com.undal.syl.app.core.design.UndalRationaleDialog
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.design.UndalSecondaryButton
import com.undal.syl.app.core.design.UndalTextButton
import com.undal.syl.app.core.design.UndalTextField
import com.undal.syl.app.core.maps.UndalGoogleEmbedMap
import com.undal.syl.app.core.maps.UndalOsmPicker
import com.undal.syl.app.core.maps.UndalPickedPointLabel
import com.undal.syl.app.core.theme.UndalDimens

/**
 * Pinning the delivery address (C-010 … C-019).
 *
 * Order of the screen is the order of the thinking: where are you on the map,
 * what should this pin look like, can the rider find the door, and only then the
 * save button. Both map engines are offered — the Google embed for the familiar
 * look, osmdroid for the offline fallback — so a customer on a weak connection
 * never gets stuck at the last mandatory step of registration.
 */
@Composable
fun LocationSetupScreen(
    onSaved: () -> Unit,
    onSignOut: () -> Unit,
    viewModel: LocationSetupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var showRationale by remember { mutableStateOf(false) }
    var showSignOutConfirm by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.onPermissionResult(true)
            viewModel.captureCurrentLocation(context)
        } else {
            viewModel.onPermissionResult(false)
        }
    }

    // Ask for the fix once, as soon as the screen opens, using the rationale
    // first — a silent permission prompt is the fastest way to a refusal.
    LaunchedEffect(Unit) {
        if (state.title.isEmpty() && state.accuracyMeters == null) {
            showRationale = true
        }
    }

    UndalScaffold(
        title = "Your delivery address",
        subtitle = "Pin the exact spot so the rider finds you first time"
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(UndalDimens.screenPadding)
        ) {
            if (state.banner != null) {
                UndalAlertBanner(
                    text = state.banner.orEmpty(),
                    icon = Icons.Filled.Info,
                    containerColour = MaterialTheme.colorScheme.secondaryContainer,
                    contentColour = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.height(UndalDimens.md))
            }

            UndalCard {
                Text("1 · Where is the food going?", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "Drag the pin onto your building. The kitchen uses this point to know whether " +
                        "you are inside the delivery area and how much the ride costs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(UndalDimens.md))

                if (state.showOsmFallback) {
                    UndalOsmPicker(
                        latitude = state.lat,
                        longitude = state.lng,
                        onPicked = viewModel::onPinMoved,
                        recentreLabel = "Use my current location",
                        onRecentre = {
                            if (viewModel.state.value.permissionNeeded) {
                                showRationale = true
                            } else {
                                permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                        }
                    )
                } else {
                    UndalGoogleEmbedMap(
                        latitude = state.lat,
                        longitude = state.lng,
                        title = "Your pin",
                        onOpenOsmFallback = viewModel::toggleOsmFallback
                    )
                    Spacer(Modifier.height(UndalDimens.sm))
                    Text(
                        text = "Move the pin precisely with the offline map:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(UndalDimens.sm))

                if (!state.showOsmFallback) {
                    UndalSecondaryButton(
                        text = "Adjust the pin by hand",
                        onClick = viewModel::toggleOsmFallback,
                        icon = Icons.Filled.LocationOn
                    )
                    Spacer(Modifier.height(UndalDimens.sm))
                } else {
                    UndalTextButton(
                        text = "Switch back to the Google view",
                        onClick = viewModel::toggleOsmFallback,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(UndalDimens.xs))
                }

                UndalPrimaryButton(
                    text = if (state.locating) "Finding your location…" else "Use my current location",
                    onClick = {
                        if (viewModel.state.value.permissionNeeded) {
                            showRationale = true
                        } else {
                            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    },
                    loading = state.locating,
                    icon = Icons.Filled.Place
                )

                Spacer(Modifier.height(UndalDimens.sm))
                UndalPickedPointLabel(
                    label = state.label.ifBlank { "Drag the pin onto your building to set the exact spot." }
                )
                if (state.accuracyNote.isNotBlank()) {
                    Text(
                        text = state.accuracyNote,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(UndalDimens.md))

            UndalCard {
                Text("2 · How should we describe it?", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "All three lines are required: the rider reads them in this order on the way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(UndalDimens.md))
                UndalTextField(
                    value = state.title,
                    onValueChange = viewModel::onTitleChange,
                    label = "Title",
                    hint = "Home",
                    error = state.titleError,
                    helper = "One short word you will recognise in your address list.",
                    leadingIcon = Icons.Filled.Home,
                    imeAction = ImeAction.Next,
                    enabled = !state.busy
                )
                Spacer(Modifier.height(UndalDimens.md))
                UndalTextField(
                    value = state.desc,
                    onValueChange = viewModel::onDescChange,
                    label = "Description",
                    hint = "Flat 4B, green building beside the mosque",
                    error = state.descError,
                    helper = "Building, floor and a landmark the rider can see.",
                    imeAction = ImeAction.Next,
                    maxLines = 2,
                    singleLine = false,
                    enabled = !state.busy
                )
                Spacer(Modifier.height(UndalDimens.md))
                UndalTextField(
                    value = state.note,
                    onValueChange = viewModel::onNoteChange,
                    label = "Delivery note for the rider",
                    hint = "Call when you reach the gate",
                    error = state.noteError,
                    helper = "Exactly what the rider should do when they arrive.",
                    imeAction = ImeAction.Done,
                    maxLines = 2,
                    singleLine = false,
                    enabled = !state.busy,
                    onImeAction = { viewModel.save(onSaved) }
                )
            }

            Spacer(Modifier.height(UndalDimens.lg))

            UndalPrimaryButton(
                text = "Save address and start ordering",
                onClick = { viewModel.save(onSaved) },
                loading = state.busy,
                icon = Icons.Filled.Done
            )

            Spacer(Modifier.height(UndalDimens.sm))

            UndalTextButton(
                text = "Sign out",
                onClick = { showSignOutConfirm = true },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(UndalDimens.xl))
        }
    }

    if (showRationale) {
        UndalRationaleDialog(
            title = "Why Undal needs your location",
            body = "Undal HomeKitchen is a single kitchen with a real delivery radius.",
            bullets = listOf(
                "Your pin decides whether we can reach you at all.",
                "The distance from our kitchen sets the delivery fee, per kilometre.",
                "The rider follows the pin, so a wrong point means a late meal.",
                "Undal never tracks you after you close the app."
            ),
            continueLabel = "Allow location",
            onContinue = {
                showRationale = false
                permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            },
            onDismiss = {
                showRationale = false
                viewModel.onPermissionResult(false)
            }
        )
    }

    if (showSignOutConfirm) {
        UndalConfirmDialog(
            title = "Sign out of Undal?",
            message = "You can sign back in at any time. Your saved address stays in your account.",
            confirmLabel = "Sign out",
            destructive = true,
            onConfirm = {
                showSignOutConfirm = false
                onSignOut()
            },
            onDismiss = { showSignOutConfirm = false }
        )
    }

    // A refusal — including "don't ask again" — is answered with a real path into
    // the system settings, never with a dead button (C-011).
    if (state.permissionNeeded) {
        UndalTextButton(
            text = "Open app settings to allow location",
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
