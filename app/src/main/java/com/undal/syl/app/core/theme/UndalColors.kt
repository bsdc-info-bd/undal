package com.undal.syl.app.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Undal brand palette.
 *
 * Deep kitchen green for trust and action, warm turmeric for appetite and
 * emphasis, and a paper-warm neutral surface in light mode so photographs of
 * food read correctly. Every pair below was picked to clear a 4.5:1 contrast
 * ratio against its own surface.
 */
object UndalPalette {
    val Brand = Color(0xFF0B5A2B)
    val BrandDark = Color(0xFF073D1D)
    val BrandLight = Color(0xFFE4F3E7)
    val BrandMid = Color(0xFF137A3B)

    val Accent = Color(0xFFE8871E)
    val AccentDark = Color(0xFFB8650F)
    val AccentLight = Color(0xFFFFF1DE)

    val Danger = Color(0xFFB3261E)
    val DangerLight = Color(0xFFFDECEC)
    val Success = Color(0xFF1E7B43)
    val SuccessLight = Color(0xFFE7F5EC)
    val Warning = Color(0xFF9A6700)
    val WarningLight = Color(0xFFFFF4DC)

    val Paper = Color(0xFFFFFBF4)
    val Ink = Color(0xFF14261B)
    val Muted = Color(0xFF5B6B60)
    val Outline = Color(0xFFD8DED6)
    val OutlineStrong = Color(0xFFB8C1B8)

    val NightSurface = Color(0xFF101613)
    val NightSurfaceHigh = Color(0xFF1A211C)
    val NightInk = Color(0xFFE6EDE7)
    val NightMuted = Color(0xFFA9B6AC)
    val NightBrand = Color(0xFF63D08A)
    val NightAccent = Color(0xFFFFB865)
}

val UndalLightScheme: ColorScheme = lightColorScheme(
    primary = UndalPalette.Brand,
    onPrimary = Color.White,
    primaryContainer = UndalPalette.BrandLight,
    onPrimaryContainer = UndalPalette.BrandDark,
    secondary = UndalPalette.Accent,
    onSecondary = Color.White,
    secondaryContainer = UndalPalette.AccentLight,
    onSecondaryContainer = UndalPalette.AccentDark,
    tertiary = UndalPalette.Success,
    onTertiary = Color.White,
    background = UndalPalette.Paper,
    onBackground = UndalPalette.Ink,
    surface = Color.White,
    onSurface = UndalPalette.Ink,
    surfaceVariant = Color(0xFFF1F4EE),
    onSurfaceVariant = UndalPalette.Muted,
    outline = UndalPalette.Outline,
    outlineVariant = UndalPalette.OutlineStrong,
    error = UndalPalette.Danger,
    onError = Color.White,
    errorContainer = UndalPalette.DangerLight,
    onErrorContainer = UndalPalette.Danger,
)

val UndalDarkScheme: ColorScheme = darkColorScheme(
    primary = UndalPalette.NightBrand,
    onPrimary = Color(0xFF04310F),
    primaryContainer = Color(0xFF14502A),
    onPrimaryContainer = Color(0xFFD5F5DF),
    secondary = UndalPalette.NightAccent,
    onSecondary = Color(0xFF3A2200),
    secondaryContainer = Color(0xFF5A3A0B),
    onSecondaryContainer = Color(0xFFFFE8C9),
    tertiary = Color(0xFF7BD89F),
    onTertiary = Color(0xFF05301A),
    background = UndalPalette.NightSurface,
    onBackground = UndalPalette.NightInk,
    surface = UndalPalette.NightSurfaceHigh,
    onSurface = UndalPalette.NightInk,
    surfaceVariant = Color(0xFF232B24),
    onSurfaceVariant = UndalPalette.NightMuted,
    outline = Color(0xFF3A443B),
    outlineVariant = Color(0xFF4B564C),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Semantic colours that are not part of the Material scheme. */
data class UndalSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val closed: Color,
    val closedContainer: Color,
    val onClosedContainer: Color,
)

val UndalLightSemantics = UndalSemanticColors(
    success = UndalPalette.Success,
    onSuccess = Color.White,
    successContainer = UndalPalette.SuccessLight,
    onSuccessContainer = Color(0xFF0A3D20),
    warning = UndalPalette.Warning,
    warningContainer = UndalPalette.WarningLight,
    onWarningContainer = Color(0xFF5C3D00),
    closed = UndalPalette.Danger,
    closedContainer = UndalPalette.DangerLight,
    onClosedContainer = UndalPalette.Danger,
)

val UndalDarkSemantics = UndalSemanticColors(
    success = Color(0xFF7BD89F),
    onSuccess = Color(0xFF04310F),
    successContainer = Color(0xFF14502A),
    onSuccessContainer = Color(0xFFD5F5DF),
    warning = Color(0xFFF5C766),
    warningContainer = Color(0xFF4A3600),
    onWarningContainer = Color(0xFFFFE7AE),
    closed = Color(0xFFFFB4AB),
    closedContainer = Color(0xFF8C1D18),
    onClosedContainer = Color(0xFFFFDAD6),
)
