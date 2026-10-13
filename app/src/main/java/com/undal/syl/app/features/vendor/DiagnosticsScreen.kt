package com.undal.syl.app.features.vendor

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalPrimaryButton
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.design.UndalSecondaryButton
import com.undal.syl.app.core.design.UndalStatusPill
import com.undal.syl.app.core.design.UndalTextButton
import com.undal.syl.app.core.fcm.AlarmChannels
import com.undal.syl.app.core.theme.UndalDimens

/**
 * The kitchen device wizard (V-004 · V-009 · V-087).
 *
 * This is the screen that decides whether the phone will ring at 11 pm. It runs
 * once before the console opens and stays reachable from there forever, because
 * a ROM update, a new battery mode or a "cleaner" app can silently undo any of
 * these settings months later.
 */
@Composable
fun DiagnosticsScreen(
    onContinue: () -> Unit,
    onOpenSupport: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { viewModel.refresh(context) }

    LaunchedEffect(Unit) {
        viewModel.refresh(context)
        viewModel.observeHeartbeat()
        viewModel.detectAutostartTargets(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Below Android 13 there is no runtime notification permission, but
            // the channels are still created on every launch.
            AlarmChannels.ensureChannels(context)
        }
    }

    UndalScaffold(
        title = "Make this phone ring",
        subtitle = "Three taps now, no missed orders later"
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(UndalDimens.screenPadding)
        ) {
            UndalAlertBanner(
                text = "This phone takes orders. Android blocks loud alarms by default, so Undal checks " +
                    "each setting with you once, and can re-check it any time from the console.",
                icon = Icons.Filled.Info
            )

            Spacer(Modifier.height(UndalDimens.md))

            state.checks.forEach { check ->
                UndalCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = when (check.status) {
                                DiagnosticsViewModel.Status.OK -> Icons.Filled.CheckCircle
                                DiagnosticsViewModel.Status.ATTENTION -> Icons.Filled.Info
                                DiagnosticsViewModel.Status.MISSING -> Icons.Filled.Warning
                            },
                            contentDescription = null,
                            tint = when (check.status) {
                                DiagnosticsViewModel.Status.OK -> MaterialTheme.colorScheme.primary
                                DiagnosticsViewModel.Status.ATTENTION -> MaterialTheme.colorScheme.secondary
                                DiagnosticsViewModel.Status.MISSING -> MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.size(UndalDimens.iconMd)
                        )
                        Spacer(Modifier.width(UndalDimens.sm))
                        Text(check.title, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        UndalStatusPill(
                            text = when (check.status) {
                                DiagnosticsViewModel.Status.OK -> "Ready"
                                DiagnosticsViewModel.Status.ATTENTION -> "Improve"
                                DiagnosticsViewModel.Status.MISSING -> "Required"
                            },
                            containerColour = when (check.status) {
                                DiagnosticsViewModel.Status.OK -> MaterialTheme.colorScheme.primaryContainer
                                DiagnosticsViewModel.Status.ATTENTION -> MaterialTheme.colorScheme.secondaryContainer
                                DiagnosticsViewModel.Status.MISSING -> MaterialTheme.colorScheme.errorContainer
                            },
                            contentColour = when (check.status) {
                                DiagnosticsViewModel.Status.OK -> MaterialTheme.colorScheme.onPrimaryContainer
                                DiagnosticsViewModel.Status.ATTENTION -> MaterialTheme.colorScheme.onSecondaryContainer
                                DiagnosticsViewModel.Status.MISSING -> MaterialTheme.colorScheme.onErrorContainer
                            }
                        )
                    }
                    Spacer(Modifier.height(UndalDimens.xs))
                    Text(
                        text = check.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // One-tap fixes, in the order the wizard wants them done.
                    when (check.id) {
                        "notifications" -> if (!state.notificationPermissionGranted) {
                            Spacer(Modifier.height(UndalDimens.sm))
                            UndalPrimaryButton(
                                text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    "Allow notifications"
                                } else {
                                    "Open notification settings"
                                },
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        runCatching { context.startActivity(AlarmChannels.appNotificationSettings(context)) }
                                    }
                                },
                                icon = Icons.Filled.Notifications
                            )
                        }

                        "order-channel" -> {
                            Spacer(Modifier.height(UndalDimens.sm))
                            UndalSecondaryButton(
                                text = "Open the new-order channel",
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            AlarmChannels.channelSettings(context, AlarmChannels.VENDOR_NEW_ORDER)
                                        )
                                    }
                                }
                            )
                        }

                        "battery" -> if (!state.batteryExempt) {
                            Spacer(Modifier.height(UndalDimens.sm))
                            UndalPrimaryButton(
                                text = "Exempt Undal from battery saving",
                                onClick = {
                                    runCatching {
                                        context.startActivity(AlarmChannels.requestIgnoreBatteryOptimisations(context))
                                    }
                                },
                                icon = Icons.Filled.Warning
                            )
                        }

                        "exact-alarms" -> if (!state.exactAlarmsAllowed) {
                            Spacer(Modifier.height(UndalDimens.sm))
                            UndalSecondaryButton(
                                text = "Allow exact alarms",
                                onClick = {
                                    runCatching { context.startActivity(AlarmChannels.exactAlarmSettings()) }
                                }
                            )
                        }

                        "alarm-volume" -> {
                            Spacer(Modifier.height(UndalDimens.sm))
                            UndalSecondaryButton(
                                text = "Open sound settings",
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(UndalDimens.sm))
            }

            UndalCard {
                Text("Test it now", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "Play the official order sound at alarm volume, feel the vibration, then move on. " +
                        "If you cannot hear it across the kitchen, fix the settings above first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(UndalDimens.md))
                Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.sm)) {
                    Column(modifier = Modifier.weight(1f)) {
                        UndalPrimaryButton(
                            text = if (state.soundTesting) "Stop the sound" else "Play the order sound",
                            onClick = { viewModel.testOrderSound(context) },
                            icon = if (state.soundTesting) Icons.Filled.Close else Icons.Filled.PlayArrow
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        UndalSecondaryButton(
                            text = "Test vibration",
                            onClick = { viewModel.testVibration(context) },
                            icon = Icons.Filled.Phone
                        )
                    }
                }
            }

            Spacer(Modifier.height(UndalDimens.sm))

            UndalCard {
                Text("Connection health", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text("Kitchen session: ${state.sessionSummary}", style = MaterialTheme.typography.bodyMedium)
                Text("Device token ends with: ${state.tokenSummary()}", style = MaterialTheme.typography.bodyMedium)
                Text("Last heartbeat: ${state.heartbeatAgeLabel}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(UndalDimens.sm))
                Text(
                    text = "The Worker closes the store automatically if this phone goes quiet for 15 " +
                        "minutes while the store is open, and pushes you a Reopen notice. The heartbeat " +
                        "below is what keeps that from happening.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(UndalDimens.sm))
                UndalSecondaryButton(
                    text = "Send a heartbeat now",
                    onClick = { viewModel.heartbeatNow(context) }
                )
            }

            if (state.autostartTargets.isNotEmpty()) {
                Spacer(Modifier.height(UndalDimens.sm))
                UndalCard {
                    Text("Autostart on this phone", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(UndalDimens.xs))
                    Text(
                        text = "This phone model blocks background apps until you allow autostart. " +
                            "Open the screen below and set Undal HomeKitchen to allowed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(UndalDimens.sm))
                    AlarmChannels.autostartCandidates()
                        .filter { (label, _) -> state.autostartTargets.contains(label) }
                        .forEach { (label, intent) ->
                            UndalSecondaryButton(
                                text = "Open $label",
                                onClick = { runCatching { context.startActivity(intent) } }
                            )
                            Spacer(Modifier.height(UndalDimens.xs))
                        }
                }
            }

            Spacer(Modifier.height(UndalDimens.lg))

            UndalPrimaryButton(
                text = "Everything is allowed — open the kitchen console",
                onClick = onContinue
            )
            Spacer(Modifier.height(UndalDimens.sm))
            UndalTextButton(
                text = "Read how Undal handles your data",
                onClick = onOpenSupport,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(UndalDimens.xl))
        }
    }
}
