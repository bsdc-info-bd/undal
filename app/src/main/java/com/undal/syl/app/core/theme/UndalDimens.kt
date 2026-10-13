package com.undal.syl.app.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing, radius and elevation tokens. Screens never hard-code a magic number;
 * everything visual comes from here so the whole app breathes consistently.
 */
object UndalDimens {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp

    val screenPadding: Dp = 16.dp
    val sectionGap: Dp = 20.dp
    val cardPadding: Dp = 16.dp
    val rowGap: Dp = 10.dp

    val touchTarget: Dp = 48.dp
    val iconSm: Dp = 18.dp
    val iconMd: Dp = 24.dp
    val iconLg: Dp = 32.dp

    val radiusSm: Dp = 10.dp
    val radiusMd: Dp = 14.dp
    val radiusLg: Dp = 20.dp
    val radiusPill: Dp = 999.dp

    val elevationFlat: Dp = 0.dp
    val elevationCard: Dp = 2.dp
    val elevationRaised: Dp = 6.dp

    /** Bottom bars and sheets: raised enough to read as a separate surface. */
    val elevationBar: Dp = 8.dp

    val avatarSm: Dp = 32.dp
    val avatarMd: Dp = 48.dp
    val avatarLg: Dp = 72.dp

    val mapHeight: Dp = 240.dp
    val mapHeightTall: Dp = 320.dp
}

val UndalShapes = Shapes(
    extraSmall = RoundedCornerShape(UndalDimens.radiusSm),
    small = RoundedCornerShape(UndalDimens.radiusSm),
    medium = RoundedCornerShape(UndalDimens.radiusMd),
    large = RoundedCornerShape(UndalDimens.radiusLg),
    extraLarge = RoundedCornerShape(28.dp)
)
