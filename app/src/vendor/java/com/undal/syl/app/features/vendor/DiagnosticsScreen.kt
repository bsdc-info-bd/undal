@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.undal.syl.app.features.vendor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.undal.syl.app.core.config.UndalBuildInfo
import com.undal.syl.app.core.design.UndalAlertBanner
import com.undal.syl.app.core.design.UndalCard
import com.undal.syl.app.core.design.UndalPrimaryButton
import com.undal.syl.app.core.design.UndalScaffold
import com.undal.syl.app.core.design.UndalSecondaryButton
import com.undal.syl.app.core.design.UndalStatusPill
import com.undal.syl.app.core.design.UndalTextButton
import com.undal.syl.app.core.fcm.AlarmChannels
import com.undal.syl.app.core.theme.UndalDimens
import kotlinx.coroutines.delay

/** Device readiness checks and a relay test. Neither is a guarantee of future Android delivery. */
@Composable
fun DiagnosticsScreen(
    onContinue: () -> Unit,
    onOpenSupport: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showReady by remember { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { viewModel.refresh(context) }

    LaunchedEffect(Unit) {
        viewModel.refresh(context)
        viewModel.observeHeartbeat()
        viewModel.detectAutostartTargets(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            AlarmChannels.ensureChannels(context)
        }
    }

    // Coming back from a settings screen is the moment the answer may have
    // changed, so the checks are re-read whenever the screen is shown again.
    LaunchedEffect(state.test.running) {
        if (!state.test.running) {
            delay(400)
            viewModel.refresh(context)
        }
    }

    val fix = { target: DiagnosticsViewModel.Fix ->
        when (target) {
            DiagnosticsViewModel.Fix.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    runCatching { context.startActivity(AlarmChannels.appNotificationSettings(context)) }
                }
            }

            DiagnosticsViewModel.Fix.CHANNEL -> runCatching {
                context.startActivity(AlarmChannels.channelSettings(context, AlarmChannels.VENDOR_NEW_ORDER))
            }

            DiagnosticsViewModel.Fix.FULL_SCREEN -> runCatching {
                context.startActivity(AlarmChannels.fullScreenIntentSettings(context))
            }

            DiagnosticsViewModel.Fix.BATTERY -> runCatching {
                context.startActivity(AlarmChannels.requestIgnoreBatteryOptimisations(context))
            }

            DiagnosticsViewModel.Fix.EXACT_ALARMS -> runCatching {
                context.startActivity(AlarmChannels.exactAlarmSettings())
            }

            // Some ROMs rename or move this screen between versions, so the
            // answer decides what the vendor is told: opened, or not on this phone.
            DiagnosticsViewModel.Fix.AUTOSTART -> {
                if (!viewModel.openAutostartSettings(context)) {
                    viewModel.noteAutostartUnavailable()
                }
            }

            DiagnosticsViewModel.Fix.TOKEN -> viewModel.reregister(context)

            // Arming the watchdog is the same act as starting the watcher: the
            // promise is one, so the one place that starts it arms both.
            DiagnosticsViewModel.Fix.WATCH,
            DiagnosticsViewModel.Fix.WATCHDOG -> viewModel.startWatcher(context)

            DiagnosticsViewModel.Fix.VOLUME -> viewModel.raiseAlarmVolume(context)
        }
        Unit
    }

    UndalScaffold(
        title = "Alert readiness",
        subtitle = "Device checks and a real test alert",
        contentMaxWidth = 760.dp,
        bottomBar = {
            UndalPrimaryButton(
                text = if (state.armed) "Open kitchen console" else "Continue with checks outstanding",
                onClick = onContinue,
                modifier = Modifier.padding(horizontal = UndalDimens.screenPadding, vertical = UndalDimens.sm)
            )
        }
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(UndalDimens.screenPadding)
        ) {
            ArmedHeader(armed = state.armed, blockers = state.blockingFailures, attention = state.attentionCount)

            Spacer(Modifier.height(UndalDimens.md))

            // A failed read is said first, and it suppresses the "every check
            // passes" banner below: with no checks read at all, that banner would
            // be the one lie this screen must never tell.
            if (state.notice != null) {
                UndalAlertBanner(
                    text = state.notice.orEmpty(),
                    icon = Icons.Filled.Warning
                )
                Spacer(Modifier.height(UndalDimens.sm))
            }

            val failing = state.checks.filter { it.status != DiagnosticsViewModel.Status.OK }
            val ready = state.checks.filter { it.status == DiagnosticsViewModel.Status.OK }

            if (state.armed && failing.isEmpty() && state.notice == null) {
                UndalAlertBanner(
                    text = "Required checks pass. Send a test alert below and listen with the screen locked. " +
                        "A successful test confirms that attempt, not future delivery.",
                    icon = Icons.Filled.CheckCircle
                )
                Spacer(Modifier.height(UndalDimens.sm))
            }

            failing
                .sortedByDescending { it.blocking }
                .forEach { check ->
                    CheckCard(check = check, onFix = { target -> fix(target) })
                    Spacer(Modifier.height(UndalDimens.sm))
                }

            if (ready.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    UndalTextButton(
                        text = if (showReady) "Hide ${ready.size} passing checks" else "Show ${ready.size} passing checks",
                        onClick = { showReady = !showReady }
                    )
                }
                AnimatedVisibility(visible = showReady) {
                    Column {
                        ready.forEach { check ->
                            CheckCard(check = check, onFix = { target -> fix(target) })
                            Spacer(Modifier.height(UndalDimens.sm))
                        }
                    }
                }
            }

            Spacer(Modifier.height(UndalDimens.sm))

            RingTestCard(
                test = state.test,
                onRing = {
                    viewModel.ringMyPhone(context)
                }
            )

            Spacer(Modifier.height(UndalDimens.sm))

            UndalCard {
                Text("Background delivery · what to expect", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    "Orders use high-priority FCM data messages. After receipt, a foreground alarm service loops the sound until all waiting orders are answered. A live watcher and server re-alerts add recovery paths.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(UndalDimens.sm))
                Text(
                    "Normal process death is supported, but Force stop, a powered-off/offline phone, revoked permissions or manufacturer restrictions can prevent delivery. After Force stop, open Undal again. Allow notifications, unrestricted battery use and autostart, then test with the screen locked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(UndalDimens.sm))
            UndalCard {
                Text("Alert delivery timeline", style = MaterialTheme.typography.titleMedium)
                Text("Local evidence · latest 24 events · Bangladesh time. Playback state is not proof that a person heard the sound.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val events = state.alertEvents
                if (events.isEmpty()) Text("No alert events yet. Run Ring my phone to test the chain.", style = MaterialTheme.typography.bodyMedium)
                events.take(12).forEach { event ->
                    Spacer(Modifier.height(UndalDimens.sm))
                    Text(event, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(UndalDimens.sm))
                UndalSecondaryButton(text = "Refresh delivery evidence", onClick = { viewModel.refresh(context) })
                if (events.isNotEmpty()) {
                    UndalTextButton(text = "Copy diagnostic timeline", onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Undal alert diagnostics", events.joinToString("\n\n")))
                        android.widget.Toast.makeText(context, "Diagnostic timeline copied", android.widget.Toast.LENGTH_SHORT).show()
                    })
                }
            }
            Spacer(Modifier.height(UndalDimens.sm))

            UndalCard {
                Text("Manual sound test", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = "Plays the official order sound at alarm volume on this phone only, no relay involved. " +
                        "If you cannot hear it from across the kitchen, fix the settings above first.",
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
                Text(
                    "Device token ends with: ${state.tokenTail.ifBlank { "not registered yet" }}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("Last heartbeat: ${state.heartbeatAgeLabel}", style = MaterialTheme.typography.bodyMedium)
                // The project the app talks to. If this is ever not the project
                // the kitchen's data lives in, every database call is refused and
                // this one line is what says so.
                Text(
                    "Firebase project: ${UndalBuildInfo.firebaseProjectId.ifBlank { "not in this build" }}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("Worker: ${UndalBuildInfo.workerHost}", style = MaterialTheme.typography.bodyMedium)
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

            UndalTextButton(
                text = "Read how Undal handles your data",
                onClick = onOpenSupport,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(UndalDimens.xl))
        }
    }
}

/** Static device-readiness summary. Passing checks do not prove delivery or audibility. */
@Composable
private fun ArmedHeader(armed: Boolean, blockers: Int, attention: Int) {
    UndalCard {
        Icon(if (armed) Icons.Filled.CheckCircle else Icons.Filled.Warning, contentDescription = null,
            tint = if (armed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(UndalDimens.sm))
        Text(if (armed) "Required device checks pass" else "Review alert setup",
            style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(UndalDimens.xs))
        Text(
            when {
                blockers > 0 -> "$blockers required check(s) need attention. Start with the fixes below."
                attention > 0 -> "$attention optional check(s) may improve alert reliability."
                armed -> "Device settings are ready for a test alert."
                else -> "Read the checks below before testing this phone."
            }, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(UndalDimens.sm))
        Text("These checks are not a delivery guarantee. Test audibly with the screen locked. " +
            "Force stop, an offline phone and manufacturer restrictions can still prevent alerts.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * One check, with its repair attached.
 *
 * The repair is a button rather than an instruction on purpose: every failure
 * here is fixed by a specific Android settings screen that differs per phone
 * model, and telling somebody "open battery settings and look for Undal" is how
 * a checklist gets ignored.
 */
@Composable
private fun CheckCard(check: DiagnosticsViewModel.Check, onFix: (DiagnosticsViewModel.Fix) -> Unit) {
    val (icon, tint) = when (check.status) {
        DiagnosticsViewModel.Status.OK ->
            Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
        DiagnosticsViewModel.Status.ATTENTION ->
            Icons.Filled.Info to MaterialTheme.colorScheme.secondary
        DiagnosticsViewModel.Status.MISSING ->
            Icons.Filled.Warning to MaterialTheme.colorScheme.error
    }

    UndalCard {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(UndalDimens.sm),
            verticalArrangement = Arrangement.spacedBy(UndalDimens.xs)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(UndalDimens.iconMd)
            )
            Spacer(Modifier.width(UndalDimens.sm))
            Text(
                text = check.title,
                style = MaterialTheme.typography.titleMedium
            )
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

        if (check.status != DiagnosticsViewModel.Status.OK && check.fix != null) {
            Spacer(Modifier.height(UndalDimens.sm))
            if (check.blocking) {
                UndalPrimaryButton(
                    text = fixLabel(check.fix),
                    onClick = { onFix(check.fix) },
                    icon = when (check.fix) {
                        DiagnosticsViewModel.Fix.NOTIFICATIONS -> Icons.Filled.Notifications
                        DiagnosticsViewModel.Fix.FULL_SCREEN -> Icons.Filled.Lock
                        DiagnosticsViewModel.Fix.TOKEN -> Icons.Filled.Refresh
                        else -> Icons.Filled.Warning
                    }
                )
            } else {
                UndalSecondaryButton(text = fixLabel(check.fix), onClick = { onFix(check.fix) })
            }
        }
    }
}

private fun fixLabel(fix: DiagnosticsViewModel.Fix): String = when (fix) {
    DiagnosticsViewModel.Fix.NOTIFICATIONS -> "Allow notifications"
    DiagnosticsViewModel.Fix.CHANNEL -> "Open the new-order channel"
    DiagnosticsViewModel.Fix.FULL_SCREEN -> "Allow alerts over the lock screen"
    DiagnosticsViewModel.Fix.BATTERY -> "Exempt Undal from battery saving"
    DiagnosticsViewModel.Fix.EXACT_ALARMS -> "Allow exact alarms"
    DiagnosticsViewModel.Fix.AUTOSTART -> "Open autostart settings"
    DiagnosticsViewModel.Fix.TOKEN -> "Register this device again"
    DiagnosticsViewModel.Fix.WATCH -> "Start the live watcher"
    DiagnosticsViewModel.Fix.WATCHDOG -> "Arm the watchdog"
    DiagnosticsViewModel.Fix.VOLUME -> "Raise the alarm volume now"
}

/**
 * The proof.
 *
 * Everything above verifies what this device can see about itself. This sends a
 * real alert from the relay to this phone, so the answer covers the half the
 * device cannot see: whether the server can reach it at all.
 */
@Composable
private fun RingTestCard(test: DiagnosticsViewModel.TestAlert, onRing: () -> Unit) {
    // A blue-fading icon while the relay is being asked, so a slow network does
    // not look like a dead button.
    val transition = rememberInfiniteTransition(label = "ring-test")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "ring-test-alpha"
    )

    UndalCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (test.running) Icons.Filled.Refresh else Icons.Filled.Notifications,
                contentDescription = null,
                tint = if (test.running) MaterialTheme.colorScheme.primary.copy(alpha = alpha)
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(UndalDimens.iconMd)
            )
            Spacer(Modifier.width(UndalDimens.sm))
            Text("Ring this phone", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(UndalDimens.xs))
        Text(
            text = "Sends a real order alert through the Undal relay. The phone rings exactly as it will for a " +
                "customer order, and the test stops itself after 45 seconds.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (test.message.isNotBlank()) {
            Spacer(Modifier.height(UndalDimens.sm))
            val container = if (test.ok) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.errorContainer
            val content = if (test.ok) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onErrorContainer
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(container, MaterialTheme.shapes.medium)
                    .padding(UndalDimens.sm)
            ) {
                Text(
                    text = test.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = content
                )
            }
        }

        Spacer(Modifier.height(UndalDimens.md))
        UndalPrimaryButton(
            text = if (test.running) "Ringing…" else "Ring my phone now",
            onClick = onRing,
            icon = Icons.Filled.Phone,
            loading = test.running
        )
    }
}
