package com.undal.syl.app.core.fcm

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import com.undal.syl.app.core.design.UndalRationaleDialog
import com.undal.syl.app.core.session.OnboardingProgress
import kotlinx.coroutines.launch

/**
 * Holds the one dependency the consent dialog needs, so the screen that shows it
 * does not have to reach into another view model for a repository.
 */
@dagger.hilt.android.lifecycle.HiltViewModel
class NotificationConsentViewModel @javax.inject.Inject constructor(
    val progress: OnboardingProgress
) : androidx.lifecycle.ViewModel()

/**
 * Asking the customer for permission to notify them — at the moment it means
 * something, not on the first launch.
 *
 * Android 13 made notifications an explicit grant, and the app never asked for
 * it: `postNotification` checked `areNotificationsEnabled()`, found it false and
 * logged quietly, so a customer on any current phone received **nothing** — no
 * "the kitchen accepted your order", no "your food is on the way". The kitchen's
 * half of this was reported as broken; this is the customer's half, and it was
 * broken for a different reason.
 *
 * Two decisions worth keeping:
 *   · the request happens when the customer is standing in the store, where the
 *     promise is concrete ("tell me when the kitchen accepts"), not as a cold
 *     system dialog in the first second of the first launch, which is how apps
 *     get denied;
 *   · `OnboardingProgress` remembers the refusal. Asking twice is nagging, and
 *     the customer can still turn it on from Android's own settings.
 */
@Composable
fun CustomerNotificationConsent(progress: OnboardingProgress) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Either answer is recorded on the next launch; nothing to do here. */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) return@LaunchedEffect
        if (progress.notificationConsentAsked()) return@LaunchedEffect
        ask = true
    }

    if (ask) {
        UndalRationaleDialog(
            title = "Know the moment your food moves",
            body = "Undal sends one short notice at each step of your order. Nothing else, no offers you " +
                "did not ask for.",
            bullets = listOf(
                "The kitchen accepted your order",
                "Your food has left the kitchen",
                "The rider is at your door"
            ),
            continueLabel = "Allow notifications",
            onContinue = {
                scope.launch { runCatching { progress.markNotificationConsentAsked() } }
                ask = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onDismiss = {
                scope.launch { runCatching { progress.markNotificationConsentAsked() } }
                ask = false
            }
        )
    }
}
