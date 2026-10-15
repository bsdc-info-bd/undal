package com.undal.syl.app

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.undal.syl.app.core.background.OrderAlertController
import com.undal.syl.app.core.fcm.AlarmChannels
import com.undal.syl.app.core.telemetry.UndalTelemetry
import com.undal.syl.app.core.theme.UndalTheme
import com.undal.syl.app.features.root.UndalRoot
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single activity for both portals.
 *
 * Responsibilities beyond hosting Compose:
 *  · receive deep links from push notifications, the Worker's smart-open pages
 *    and shared dish links, and hand them to the navigation graph,
 *  · when the kitchen phone was woken by an order alert, dismiss the keyguard so
 *    the ringing order is on screen instead of behind a lock (PART I-2),
 *  · keep `singleTask` behaviour so a second push does not stack a second copy
 *    of the app.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @javax.inject.Inject
    lateinit var telemetry: UndalTelemetry

    private var deepLink by mutableStateOf<String?>(null)

    /**
     * Set when this activity was launched by the crash guard after a crash, so
     * the root can say what happened instead of pretending nothing did.
     */
    private var recoveredFromCrash by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        AlarmChannels.ensureChannels(this)
        deepLink = extractDeepLink(intent)
        recoveredFromCrash = com.undal.syl.app.core.util.UndalCrashGuard.isRecoveryLaunch(intent)
        showOverLockScreen()

        setContent {
            UndalTheme {
                UndalRoot(initialDeepLink = deepLink, recoveredFromCrash = recoveredFromCrash)
            }
        }

        // "The app is open" is the first frame on the glass, not the first line of
        // code: this is where the cold-start trace from UndalApp ends (P-025).
        Choreographer.getInstance().postFrameCallback { telemetry.endAppStart() }

        // The app got through its opening. Telling the crash guard so is what
        // stops a crash an hour later from being counted against the startup
        // budget that a broken cold start would have used.
        window.decorView.postDelayed(
            { com.undal.syl.app.core.util.UndalCrashGuard.markSuccessfulStart(this) },
            com.undal.syl.app.core.util.CrashRecoveryPolicy.SUCCESSFUL_START_MILLIS
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink = extractDeepLink(intent)
        showOverLockScreen()

        // A ringing order takes over the screen even if the app was already open
        // on another page: the kitchen must never have to hunt for it.
        OrderAlertController.ringing.value?.let { ringing ->
            OrderAlertController.requestRoute("vendor/order/${ringing.orderId}")
        }
    }

    /**
     * A ringing order may reach a locked phone, and then it has to be *on* that
     * phone rather than behind it.
     *
     * It used to do this for every launch, which meant a customer's Undal drew
     * itself over their lock screen and turned their screen on whenever the
     * activity was recreated — a notification arriving while the phone was locked
     * in a pocket was enough. The lock screen is only ever bypassed now for the
     * one thing that justifies it: an order this phone is ringing for.
     */
    private fun showOverLockScreen() {
        // Two ways to know an order is what woke this phone. The controller is
        // the truth when the alert service is in this process already; the deep
        // link is the truth when a full-screen intent started a *fresh* process,
        // where the controller has not been written to yet — which is exactly the
        // killed-app case the alert ladder exists for, and the one where the lock
        // screen most has to get out of the way.
        val ringing = OrderAlertController.ringing.value != null ||
            deepLink?.startsWith("vendor/order/") == true
        if (!ringing) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                runCatching { setShowWhenLocked(false) }
                runCatching { setTurnScreenOn(false) }
            }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            runCatching { setShowWhenLocked(true) }
            runCatching { setTurnScreenOn(true) }
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard?.isKeyguardLocked == true) {
            runCatching { keyguard.requestDismissKeyguard(this, null) }
        }
    }

    /**
     * Deep links arrive in three shapes and all three mean the same thing:
     *   · `undal://vendor/order/abc`
     *   · `https://<worker-host>/dish/xyz` (App Link, verified through assetlinks)
     *   · a push `clickAction` such as `customer/order/abc`
     */
    private fun extractDeepLink(intent: Intent?): String? {
        val data: Uri = intent?.data ?: return intent?.getStringExtra(EXTRA_DEEP_LINK)
            ?: intent?.getStringExtra("deepLink")
        return when (data.scheme) {
            "undal" -> (data.host ?: "").plus(data.path.orEmpty()).trimStart('/')
            "https", "http" -> data.path?.trimStart('/')
            else -> intent.getStringExtra(EXTRA_DEEP_LINK)
        }
    }

    companion object {
        const val EXTRA_DEEP_LINK = "undal.deepLink"

        /**
         * Set by [com.undal.syl.app.core.util.UndalCrashGuard] when this launch is
         * the app reopening after a guarded crash. The activity reads it once and
         * shows the sentence; it is never written by anything else.
         */
        const val EXTRA_RECOVERED = "undal.recovered"

        /**
         * Builds the intent every notification, alert action and QR code uses.
         * Keeping it in one place means the activity never has to guess which
         * shape of link it was opened with.
         */
        fun intentFor(context: Context, deepLink: String): Intent =
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("undal://" + deepLink.trimStart('/'))
                putExtra(EXTRA_DEEP_LINK, deepLink)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
    }
}
