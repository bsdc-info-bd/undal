package com.undal.syl.app.features.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalConfirmDialog
import com.undal.syl.app.core.design.UndalDangerButton
import com.undal.syl.app.core.design.UndalKeyValueRow
import com.undal.syl.app.core.design.UndalPasswordField
import com.undal.syl.app.core.design.UndalPrimaryButton
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.design.UndalSecondaryButton
import com.undal.syl.app.core.design.UndalTextButton
import com.undal.syl.app.core.design.UndalTextField
import com.undal.syl.app.core.maps.UndalLocationMap
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.core.util.TimeDhaka
import com.undal.syl.app.core.util.UndalDigits
import com.undal.syl.app.core.util.Validators
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.undal.syl.app.core.theme.undalSemantics

/**
 * Profile and account (C-079 · C-080).
 *
 * The address is shown here but edited on the map: the coordinates the delivery
 * radius depends on come from a pin, not from typing. Everything that can be
 * changed without leaving this screen — name, phone, photo, password — is saved
 * immediately, with the rider in mind: the name they call and the number they
 * ring must be right before the next order goes out.
 */
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onEditAddress: () -> Unit,
    onOpenOrders: () -> Unit,
    onOpenInbox: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var draftName by remember(state.name) { mutableStateOf(state.name) }
    var draftPhone by remember(state.phone) { mutableStateOf(state.phone) }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshAddress() }

    // Coming back from the mail app is the moment the answer has usually changed,
    // so the flag is re-read on every resume — quietly, because a notice on every
    // return would be its own kind of broken. The bounded watch covers the other
    // half of the same problem: a customer who taps the link *while* this screen
    // is open, in a split-screen or a second device, and waits for it to notice.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, state.emailVerified) {
        if (state.emailVerified) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) viewModel.checkEmailVerification(quiet = true)
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            viewModel.watchForVerification()
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.uploadPhoto(uri)
    }

    UndalScaffold(
        title = "Your account",
        subtitle = state.displayName,
        onBack = onBack,
        actions = {
            UndalSecondaryButton(
                text = if (state.photoUrl.isBlank()) "Add photo" else "Change photo",
                onClick = {
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                enabled = !state.uploading,
                icon = Icons.Filled.Person
            )
        }
    ) { _ ->
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
                    icon = if (state.noticeIsError) Icons.Filled.Warning else Icons.Filled.Check,
                    containerColour = if (state.noticeIsError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.undalSemantics.successContainer
                    },
                    contentColour = if (state.noticeIsError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.undalSemantics.onSuccessContainer
                    },
                    action = {
                        TextButton(onClick = viewModel::clearNotice) { Text("Close") }
                    }
                )
            }

            Spacer(Modifier.height(UndalDimens.sm))

            // ---- identity ---------------------------------------------------
            UndalCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(UndalDimens.avatarLg)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (state.photoUrl.isNotBlank()) {
                            AsyncImage(
                                model = state.photoUrl,
                                contentDescription = "Your photo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                Icons.Filled.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(UndalDimens.iconLg)
                            )
                        }
                    }
                    Spacer(Modifier.width(UndalDimens.md))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(state.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = state.email.ifBlank { "Signed in" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (state.uploading) "Uploading…" else "Tap “Add photo” to change it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (state.emailVerified) {
                    Spacer(Modifier.height(UndalDimens.xs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(UndalDimens.iconSm)
                        )
                        Spacer(Modifier.width(UndalDimens.xs))
                        Text(
                            text = "Email confirmed",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    Spacer(Modifier.height(UndalDimens.sm))
                    UndalAlertBanner(
                        text = "Your email is not confirmed yet. Confirming it keeps password " +
                            "recovery reliable — open the link we mailed you, then check again here.",
                        icon = Icons.Filled.Email,
                        containerColour = MaterialTheme.colorScheme.secondaryContainer,
                        contentColour = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(Modifier.height(UndalDimens.xs))
                    UndalSecondaryButton(
                        text = if (state.checkingVerification) "Checking…" else "Check again",
                        onClick = { viewModel.checkEmailVerification(quiet = false) },
                        enabled = !state.checkingVerification,
                        icon = Icons.Filled.Refresh
                    )
                    Spacer(Modifier.height(UndalDimens.xs))
                    UndalTextButton(
                        text = if (state.resendingVerification) "Sending…" else "Send me a new link",
                        onClick = viewModel::resendVerification,
                        enabled = !state.resendingVerification
                    )
                    if (state.verificationCheckedAtMillis > 0L) {
                        Spacer(Modifier.height(UndalDimens.xs))
                        Text(
                            text = if (state.verificationReachable) {
                                "Last checked at " + TimeDhaka.wallClock(state.verificationCheckedAtMillis)
                            } else {
                                "We could not reach Firebase for the last check."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(UndalDimens.sm))

            // ---- name and phone ---------------------------------------------
            UndalCard {
                Text("The rider's details", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                UndalTextField(
                    value = draftName,
                    onValueChange = { draftName = it },
                    label = "Name",
                    hint = "The name the rider asks for",
                    leadingIcon = Icons.Filled.Person
                )
                Spacer(Modifier.height(UndalDimens.sm))
                UndalTextField(
                    value = draftPhone,
                    // `Char.isDigit()` is true for the Bangla row too, which would
                    // have written ০১৭১২৩৪৫৬৭৮ into the profile — a number the
                    // Worker, the rider's dialer and the order document all read as
                    // ASCII. Numbers the app stores are ASCII, whatever keyboard
                    // typed them.
                    onValueChange = {
                        draftPhone = UndalDigits.toAscii(it).filter { ch -> ch in '0'..'9' || ch == '+' }.take(15)
                    },
                    label = "Mobile number",
                    hint = "01712345678",
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Done,
                    leadingIcon = Icons.Filled.Check
                )
                Spacer(Modifier.height(UndalDimens.sm))
                UndalPrimaryButton(
                    text = if (state.savingBasics) "Saving…" else "Save details",
                    onClick = { viewModel.saveBasics(draftName, draftPhone) },
                    // The phone is compared as a number, not as a string: the
                    // profile prints 01712 345678 and a customer may type
                    // 01712345678 or +8801712345678, and none of those is a
                    // change worth offering to save.
                    enabled = !state.savingBasics &&
                        (draftName != state.name ||
                            Validators.normalizeBdMobile(draftPhone) !=
                            Validators.normalizeBdMobile(state.phone)),
                    loading = state.savingBasics,
                    icon = Icons.Filled.Check
                )
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
                    Text("Delivery address", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    UndalSecondaryButton(
                        text = if (state.hasAddress) "Re-pin" else "Pin it",
                        onClick = onEditAddress,
                        icon = Icons.Filled.Edit,
                        fillWidth = false
                    )
                }
                Spacer(Modifier.height(UndalDimens.xs))
                if (state.address != null) {
                    UndalKeyValueRow("Title", state.address?.title.orEmpty())
                    UndalKeyValueRow("Landmark", state.address?.desc.orEmpty())
                    if (!state.address?.note.isNullOrBlank()) {
                        UndalKeyValueRow("Rider note", state.address?.note.orEmpty())
                    }
                    Spacer(Modifier.height(UndalDimens.xs))
                    Text(
                        text = state.addressPinLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(UndalDimens.sm))
                    UndalLocationMap(
                        latitude = state.address?.lat ?: 0.0,
                        longitude = state.address?.lng ?: 0.0,
                        title = state.address?.title.orEmpty(),
                        height = UndalDimens.mapHeight
                    )
                } else {
                    Text(
                        text = "No address saved yet. Pin it once and every order uses it — the delivery fee " +
                            "and the radius are calculated from that point.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(UndalDimens.sm))

            // ---- password ---------------------------------------------------
            UndalCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(UndalDimens.iconMd)
                    )
                    Spacer(Modifier.width(UndalDimens.sm))
                    Text("Password", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "Changing the password needs the current one, so a phone left unlocked on a table " +
                        "cannot lock its owner out.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(UndalDimens.sm))
                UndalPasswordField(
                    value = currentPassword,
                    onValueChange = { currentPassword = it },
                    label = "Current password"
                )
                Spacer(Modifier.height(UndalDimens.sm))
                UndalPasswordField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = "New password",
                    helper = "At least 6 characters."
                )
                Spacer(Modifier.height(UndalDimens.sm))
                UndalPasswordField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = "Repeat the new password",
                    imeAction = ImeAction.Done
                )
                Spacer(Modifier.height(UndalDimens.sm))
                UndalSecondaryButton(
                    text = if (state.changingPassword) "Changing…" else "Change password",
                    onClick = {
                        viewModel.changePassword(currentPassword, newPassword, confirmPassword)
                        currentPassword = ""
                        newPassword = ""
                        confirmPassword = ""
                    },
                    enabled = !state.changingPassword &&
                        currentPassword.isNotBlank() && newPassword.length >= 6,
                    loading = state.changingPassword,
                    icon = Icons.Filled.Lock
                )
            }

            Spacer(Modifier.height(UndalDimens.sm))

            UndalCard {
                Text("Your activity", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.sm)) {
                    UndalSecondaryButton(
                        text = "Your orders",
                        onClick = onOpenOrders,
                        modifier = Modifier.weight(1f)
                    )
                    UndalSecondaryButton(
                        text = "Notifications",
                        onClick = onOpenInbox,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(UndalDimens.sm))

            // ---- session ----------------------------------------------------
            UndalCard {
                Text("Session", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "Signing out removes this phone's notification token, so a shared phone cannot keep " +
                        "receiving your order alerts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(UndalDimens.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.sm)) {
                    UndalSecondaryButton(
                        text = "Sign out",
                        onClick = viewModel::signOut,
                        icon = Icons.Filled.ExitToApp,
                        modifier = Modifier.weight(1f)
                    )
                    UndalDangerButton(
                        text = "Delete my account",
                        onClick = { showDeleteConfirm = true },
                        enabled = !state.deleting,
                        loading = state.deleting,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(UndalDimens.xxl))
        }
    }

    if (showDeleteConfirm) {
        UndalConfirmDialog(
            title = "Delete your account?",
            message = "This removes your profile, your saved address, your notification tokens and the " +
                "account itself. Orders already placed stay with the kitchen for their records, but you " +
                "will not be able to sign in again with this email.",
            confirmLabel = "Delete everything",
            destructive = true,
            onConfirm = {
                showDeleteConfirm = false
                viewModel.deleteAccount()
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }
}
