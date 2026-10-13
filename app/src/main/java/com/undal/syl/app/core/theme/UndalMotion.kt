package com.undal.syl.app.core.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing

/**
 * One motion vocabulary for the whole app. Kitchen staff use this at 3 am with
 * greasy fingers: movement is quick, never bouncy enough to misread, and the
 * order-alert pulse is the only thing that insists on attention.
 */
object UndalMotion {
    const val Instant = 90
    const val Quick = 180
    const val Standard = 280
    const val Slow = 420

    fun <T> snap(): FiniteAnimationSpec<T> = tween(durationMillis = Instant)
    fun <T> quick(): FiniteAnimationSpec<T> = tween(durationMillis = Quick)
    fun <T> standard(): FiniteAnimationSpec<T> = tween(durationMillis = Standard, easing = FastOutSlowInEasing)
    fun <T> gentle(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    fun <T> firm(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    /** Screen-level push used between auth steps and portals. */
    val screenPushIn: EnterTransition = fadeIn(animationSpec = tween(Standard)) +
        slideInVertically(animationSpec = tween(Standard, easing = FastOutSlowInEasing)) { it / 12 }

    val screenPushOut: ExitTransition = fadeOut(animationSpec = tween(Quick)) +
        slideOutVertically(animationSpec = tween(Quick)) { it / 16 }

    /** The kitchen alert pulse (used by the ringing screen and status badges). */
    const val AlertPulseMillis = 700
}
