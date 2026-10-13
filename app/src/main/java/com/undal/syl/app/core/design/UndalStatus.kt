package com.undal.syl.app.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.undal.syl.app.core.theme.UndalDimens

/**
 * Status vocabulary: open / closed / auto-closed / warning / info.
 *
 * Colour alone never carries the meaning — every pill pairs its colour with a
 * label, and every banner pairs it with an icon, which keeps the app usable for
 * colour-blind users and in strong Sylhet sunlight.
 */
@Composable
fun UndalStatusPill(
    text: String,
    containerColour: Color,
    contentColour: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    Row(
        modifier = modifier
            .background(containerColour, RoundedCornerShape(UndalDimens.radiusPill))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColour, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = contentColour)
    }
}

@Composable
fun UndalAlertBanner(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    containerColour: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColour: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColour, RoundedCornerShape(UndalDimens.radiusMd))
            .padding(UndalDimens.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColour, modifier = Modifier.size(UndalDimens.iconSm))
            Spacer(Modifier.width(UndalDimens.sm))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColour,
            modifier = Modifier.weight(1f)
        )
        if (action != null) {
            Spacer(Modifier.width(UndalDimens.sm))
            action()
        }
    }
}

/** A labelled progress bar used for upload progress and metric bars. */
@Composable
fun UndalProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    containerColour: Color = MaterialTheme.colorScheme.surfaceVariant,
    barColour: Color = MaterialTheme.colorScheme.primary
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(containerColour, RoundedCornerShape(3.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(6.dp)
                .background(barColour, RoundedCornerShape(3.dp))
        )
    }
}

/** Row of small labels used for dish tags (veg, spicy, popular, combo). */
@Composable
fun UndalTagRow(tags: List<String>, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(UndalDimens.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tags.forEach { tag ->
            UndalStatusPill(
                text = tag,
                containerColour = MaterialTheme.colorScheme.surfaceVariant,
                contentColour = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
