package com.undal.syl.app.core.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.undal.syl.app.core.theme.UndalDimens
import com.undal.syl.app.core.theme.UndalMotion

/** Standard content card: white surface in light mode, raised tone in dark. */
@Composable
fun UndalCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val base = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(UndalDimens.radiusMd))
    Card(
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
        shape = RoundedCornerShape(UndalDimens.radiusMd),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = UndalDimens.elevationCard)
    ) {
        Column(modifier = Modifier.padding(UndalDimens.cardPadding)) { content() }
    }
}

/** Card with a brand tint, used for store status and alert banners. */
@Composable
fun UndalTintedCard(
    modifier: Modifier = Modifier,
    containerColour: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primaryContainer,
    contentColour: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onPrimaryContainer,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(UndalDimens.radiusMd),
        color = containerColour,
        contentColor = contentColour
    ) {
        Column(modifier = Modifier.padding(UndalDimens.cardPadding)) { content() }
    }
}

@Composable
fun UndalSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = UndalDimens.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(UndalDimens.iconMd)
            )
            Spacer(Modifier.width(UndalDimens.sm))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        action?.invoke()
    }
}

/** Shimmer skeleton used while Firestore data streams in (C-028). */
@Composable
fun ShimmerBlock(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(UndalDimens.radiusSm)
) {
    val transition = rememberInfiniteTransition(label = "undal-shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = UndalMotion.AlertPulseMillis * 2),
            repeatMode = RepeatMode.Reverse
        ),
        label = "undal-shimmer-progress"
    )
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.surface
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = Offset(0f, 0f),
                    end = Offset(600f * progress + 1f, 0f)
                )
            )
    )
}

@Composable
fun ShimmerListRow(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = UndalDimens.sm)) {
        ShimmerBlock(modifier = Modifier.size(64.dp))
        Spacer(Modifier.width(UndalDimens.md))
        Column(modifier = Modifier.weight(1f)) {
            ShimmerBlock(modifier = Modifier.fillMaxWidth(0.7f).height(16.dp))
            Spacer(Modifier.height(UndalDimens.sm))
            ShimmerBlock(modifier = Modifier.fillMaxWidth(0.4f).height(12.dp))
        }
    }
}

/** Hand-designed empty state (C-038): icon, honest reason, one way forward. */
@Composable
fun UndalEmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(UndalDimens.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(UndalDimens.radiusPill),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.padding(UndalDimens.lg).size(UndalDimens.iconLg),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(UndalDimens.lg))
        Text(text = title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(UndalDimens.xs))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(UndalDimens.lg))
            UndalSecondaryButton(text = actionLabel, onClick = onAction, modifier = Modifier.fillMaxWidth(0.7f))
        }
    }
}

/** Centred loader used while a blocking operation runs. */
@Composable
fun UndalFullScreenLoader(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(UndalDimens.md))
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
