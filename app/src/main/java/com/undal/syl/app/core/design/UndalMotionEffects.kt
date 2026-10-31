package com.undal.syl.app.core.design

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.undal.syl.app.core.theme.UndalMotion
import kotlin.math.roundToInt

/**
 * Motion that answers to the person holding the phone.
 *
 * The web storefront has `prefers-reduced-motion`, and the app has the same
 * switch under a different name: **Developer options → Animator duration scale →
 * off**, which is also what the "Remove animations" accessibility setting writes.
 * Until now nothing in the app read it. The shimmer swept forever, badges bounced
 * and screens slid, on a phone whose owner had asked the system to stop moving
 * things — which is not a taste preference, it is usually vestibular disorder or
 * a migraine, and it is the reason the switch exists.
 *
 * So every decoration here asks first, and the answer is read from the system
 * rather than from a setting of our own: one switch, in the place the person
 * already knows.
 *
 * What is deliberately **not** behind this switch: the order alert. A ringing
 * kitchen phone is sound, a full-screen intent, a notification and a watchdog —
 * the pulse around a status badge is decoration and follows the switch, but
 * nothing that carries an alarm is ever gated on an animation, and nothing here
 * is allowed to become one. `docs/ALERT-RELIABILITY.md` is the contract.
 *
 * Where to use [UndalReveal]: on the blocks a screen opens with — an intro, a
 * status panel, a receipt summary — and **not** on every card in a lazy list. A
 * lazy list recycles its items, so an item that reveals on the way down would
 * reveal again on the way back up, which reads as a stutter rather than as
 * arrival. The stagger below is capped for the same reason the web one is: the
 * fortieth card should not still be fading in two seconds later.
 */

/** How far a revealing block travels, in dp. A nudge, not a journey. */
private val UndalRevealTravel = 12.dp

/** The last index that is delayed. After it, everything arrives together. */
const val UndalRevealMaxIndex: Int = 8

/** Milliseconds between one revealing block and the next. */
const val UndalStaggerStepMillis: Int = 45

/** The delay for one index, capped: a long list is not a queue. */
fun undalStaggerMillis(index: Int): Int {
    val step = if (index < 0) 0 else if (index > UndalRevealMaxIndex) UndalRevealMaxIndex else index
    return step * UndalStaggerStepMillis
}

/**
 * True when the system is willing to animate.
 *
 * A scale of zero is "off". Anything else — including the value being absent,
 * which is what an emulator without Developer options reports — is "on", because
 * guessing "off" would take the app's motion away from everybody who never asked
 * for that, and guessing "on" costs one animation on a phone that has its own
 * reasons to shorten it.
 */
fun undalAnimationsAllowed(context: Context): Boolean {
    return try {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE
        )
        scale != 0f
    } catch (error: Exception) {
        // `Settings.Global.getFloat` throws `SettingNotFoundException`, which is
        // an `AndroidException` and so not a `RuntimeException` — catching the
        // narrower type would have let a missing setting take a screen down, in a
        // repository whose whole crash discipline is that nothing may. A resolver
        // that refuses is not a reason to stop rendering; it is a reason to
        // animate, which is the default the rest of the system assumes.
        true
    }
}

/** The system's animation preference, read once per composition. */
@Composable
fun rememberUndalMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) { undalAnimationsAllowed(context) }
}

/**
 * Fades and lifts a block into place the first time it is composed.
 *
 * With the system's animations off this is a plain [Box]: the first composition
 * already has the target value, so there is nothing to animate and no frame in
 * which the content is invisible. That last part matters — a reveal that starts
 * at alpha zero on a phone that will never animate it is content that never
 * arrives.
 *
 * @param index position in the group this block belongs to; each one waits
 *   [UndalStaggerStepMillis] longer than the one before it, up to a cap.
 */
@Composable
fun UndalReveal(
    modifier: Modifier = Modifier,
    index: Int = 0,
    motionEnabled: Boolean = rememberUndalMotionEnabled(),
    content: @Composable () -> Unit
) {
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { arrived = true }
    val visible = arrived || !motionEnabled
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = UndalMotion.Standard,
            delayMillis = undalStaggerMillis(index),
            easing = FastOutSlowInEasing
        ),
        label = "undal-reveal-$index"
    )
    Box(
        modifier = modifier.graphicsLayer {
            // A draw-only transform: no relayout per frame, which is the
            // difference between a reveal and a jank counter on a long list.
            alpha = progress
            translationY = (1f - progress) * UndalRevealTravel.toPx()
        }
    ) {
        content()
    }
}

/**
 * Counts a whole number up to its new value instead of jumping to it.
 *
 * A receipt total that changes while a customer is reading it is the one number
 * on the screen they must not misread, and a jump is easier to miss than a
 * movement: the eye follows the count and lands on the answer. The caller formats
 * the result with the same money rules it already uses — this returns paisa in
 * and paisa out, and never rounds, converts or formats anything, because the
 * price a customer reads is the price the server charged and one more place to
 * do arithmetic is one more place to be wrong.
 *
 * With the system's animations off, or on the first composition, this is simply
 * the value.
 */
@Composable
fun rememberUndalCountUp(
    target: Int,
    motionEnabled: Boolean = rememberUndalMotionEnabled()
): Int {
    val animated by animateFloatAsState(
        targetValue = target.toFloat(),
        animationSpec = tween(
            durationMillis = UndalMotion.Quick,
            easing = FastOutSlowInEasing
        ),
        label = "undal-count-up"
    )
    if (!motionEnabled) return target
    val counted = animated.roundToInt()
    // A count that overshoots its own total, however briefly, is a price that was
    // never charged. It cannot with this easing, and it must not be allowed to.
    return if (target >= 0) minOf(counted, target) else maxOf(counted, target)
}
