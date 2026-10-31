package com.undal.syl.app.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.undal.syl.app.core.theme.UndalDimens

/** Pure decisions so window and large-text behaviour can be regression-tested. */
object UndalLayoutPolicy {
    fun useSplit(widthDp: Float, fontScale: Float): Boolean =
        widthDp.isFinite() && fontScale.isFinite() && widthDp >= 840f && fontScale in 0.5f..1.3f

    fun useInlineActions(widthDp: Float, fontScale: Float): Boolean =
        widthDp.isFinite() && fontScale.isFinite() && widthDp >= 360f && fontScale in 0.5f..1.3f

    fun useInlineComposer(widthDp: Float, fontScale: Float): Boolean =
        widthDp >= 480f && useInlineActions(widthDp, fontScale)

    fun menuCardWidth(widthDp: Float, fontScale: Float): Float {
        val scale = if (fontScale.isFinite()) fontScale.coerceAtLeast(1f) else 1f
        return (if (widthDp >= 600f) 220f else 148f) * scale
    }
}

/** Content stays readable on tablets; the caller owns scroll and insets. */
@Composable
fun UndalReadingColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
        Column(modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()) { content() }
    }
}

/** A pair stacks on phones and at large text instead of squeezing two columns. */
@Composable
fun UndalAdaptivePair(
    modifier: Modifier = Modifier,
    primary: @Composable () -> Unit,
    secondary: @Composable () -> Unit
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (UndalLayoutPolicy.useSplit(maxWidth.value, fontScale)) {
            Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.xl)) {
                Box(Modifier.weight(1f)) { primary() }
                Box(Modifier.weight(1f)) { secondary() }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(UndalDimens.lg)) {
                primary()
                secondary()
            }
        }
    }
}

/** Editorial hierarchy without a remote font, invented metrics or a fake offer. */
@Composable
fun UndalPageIntro(
    eyebrow: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    // The intro is the first thing on the page, so it is the first thing to
    // arrive — and it arrives only where the system is willing to animate. A
    // phone with animations switched off gets this block already in place, on the
    // very first frame, which is the difference between a reveal and content that
    // never shows up.
    UndalReveal(modifier = modifier.fillMaxWidth()) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(UndalDimens.radiusLg),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(
            modifier = Modifier
                .background(Brush.linearGradient(listOf(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
                )))
                .padding(UndalDimens.xl)
        ) {
            Text(eyebrow, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(UndalDimens.sm))
            Text(title, style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(UndalDimens.sm))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
    }
}

/** Buttons stack when their actual container or the user's text size needs room. */
@Composable
fun UndalActionGroup(
    modifier: Modifier = Modifier,
    primary: @Composable () -> Unit,
    secondary: (@Composable () -> Unit)? = null
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (secondary == null) {
            primary()
        } else if (UndalLayoutPolicy.useInlineActions(maxWidth.value, fontScale)) {
            Row(horizontalArrangement = Arrangement.spacedBy(UndalDimens.sm)) {
                Box(Modifier.weight(1f)) { primary() }
                Box(Modifier.weight(1f)) { secondary() }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(UndalDimens.sm)) {
                primary()
                secondary()
            }
        }
    }
}
