package com.undal.syl.app.features.vendor

import android.Manifest
import android.content.Intent
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

/**
 * Kitchen readiness (V-004 · V-009 · V-087 · PART I).
 *
 * The question this screen answers is not "are the settings nice" — it is **"if a
 * customer orders right now, will this phone ring?"** So it leads with the
 * answer, in one word, and then lists only what stands between the kitchen and
 * that word. Everything below is read from the operating system, the database or
 * the relay; nothing here is a setting the app merely believes it set.
 *
 * The last card is the one that matters: a real alert, sent by the relay, ringing
 * this phone through the same code path a real order uses. A green checklist can
 * be wrong; a phone that rings cannot.
 */
@Composable
fun DiagnosticsScreen(
    onContinue: () -> Unit,
    onOpenSupport: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
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

            DiagnosticsViewModel.Fix.AUTOSTART -> Unit

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
        title = "Will this phone ring?",
        subtitle = if (state.armed) "Everything an order needs is in place" else "Two minutes now, no missed orders"
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

            if (failing.isEmpty() && state.notice == null) {
                UndalAlertBanner(
                    text = "Every check passes. Scroll to the last card and ring this phone once — that is the " +
                        "only test that proves the whole chain.",
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

            UndalPrimaryButton(
                text = if (state.armed) {
                    "Open the kitchen console"
                } else {
                    "Open the console anyway"
                },
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

/**
 * The one-word answer, and the reason a kitchen phone is worth carrying.
 *
 * A slow pulse while armed, because "armed" is a live state, not a badge: the
 * pulse is what makes the difference between "the app said ok once" and "the app
 * is watching right now" visible at a glance from across the counter.
 */
@Composable
private fun ArmedHeader(armed: Boolean, blockers: Int, attention: Int) {
    val accent = if (armed) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    val container = if (armed) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val pulse = rememberInfiniteTransition(label = "armed-pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (armed) 1.06f else 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "armed-scale"
    )

    UndalCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .scale(scale)
                    .background(container, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (armed) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(Modifier.height(UndalDimens.xs))
                    Text(
                        text = if (armed) "ARMED" else "NOT YET",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                }
            }

            Spacer(Modifier.height(UndalDimens.md))

            Text(
                text = when {
                    armed -> "This phone will ring for a new order"
                    blockers > 0 -> "$blockers setting${if (blockers == 1) "" else "s"} would lose an order"
                    else -> "Worth improving before tonight"
                },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(UndalDimens.xs))
            Text(
                text = when {
                    armed && attention == 0 ->
                        "Notifications, the alarm channel, the battery exemption and the live watcher are all in " +
                            "place. Ring this phone at the bottom to prove the whole chain."
                    armed ->
                        "Nothing here can lose an order. $attention check${if (attention == 1) "" else "s"} below " +
                            "would still make the alert quieter or slower than it should be."
                    else ->
                        "Android blocks loud background alarms by default on a new install. Work down this list " +
                            "once — it takes about two minutes."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(UndalDimens.iconMd)
            )
            Spacer(Modifier.width(UndalDimens.sm))
            Text(
                text = check.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
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
