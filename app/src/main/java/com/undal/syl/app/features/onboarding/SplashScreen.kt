package com.undal.syl.app.features.onboarding

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.undal.syl.app.R
import com.undal.syl.app.core.config.UndalBuildInfo
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.core.theme.UndalMotion

/**
 * The branded splash (C-001).
 *
 * The window-level splash (core-splashscreen, configured in the theme) covers the
 * first frame; this composable takes over while the app decides where to send the
 * person. The mark breathes very slowly — enough to prove the app is alive on a
 * slow network, never enough to look like a game.
 */
@Composable
fun UndalSplash(
    message: String,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "undal-splash")
    val scale by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = UndalMotion.Standard * 6),
            repeatMode = RepeatMode.Reverse
        ),
        label = "undal-splash-scale"
    )

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(UndalDimens.xl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.ic_undal_mark),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier
                    .size(140.dp)
                    .scale(scale)
            )
            Spacer(Modifier.height(UndalDimens.lg))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(UndalDimens.xs))
            Text(
                text = stringResource(R.string.shell_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(UndalDimens.xxl))
            CircularProgressIndicator(
                modifier = Modifier.size(UndalDimens.iconMd),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(UndalDimens.md))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(UndalDimens.xs))
            Text(
                text = stringResource(R.string.shell_version_label, UndalBuildInfo.versionName),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
