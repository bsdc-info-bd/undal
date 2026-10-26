package com.undal.syl.app.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Undal brand palette.
 *
 * Rich berry for appetite-led actions, warm saffron for emphasis, and a soft
 * paper-white surface so real food photography stays vivid. Foodpanda references
 * informed the colour energy and browsing hierarchy; Undal keeps its own mark,
 * content and voice. Text/status pairings retain separate semantic contrast.
 */
object UndalPalette {
    // Food-forward, high-contrast berry with Undal's own warm kitchen accent.
    // The reference screenshots informed the energy and hierarchy, not a reuse
    // of Foodpanda marks or artwork.
    val Brand = Color(0xFFD70F64)
    val BrandDark = Color(0xFF8C0A41)
    val BrandLight = Color(0xFFFCE7F0)
    val BrandMid = Color(0xFFB20D53)

    val Accent = Color(0xFFF3A62A)
    val AccentDark = Color(0xFF8B4A00)
    val AccentLight = Color(0xFFFFF0D5)

    val Danger = Color(0xFFB3263A)
    val DangerLight = Color(0xFFFCE9EC)
    val Success = Color(0xFF167347)
    val SuccessLight = Color(0xFFE5F4EC)
    val Warning = Color(0xFF8A5A00)
    val WarningLight = Color(0xFFFFF3D8)

    val Paper = Color(0xFFFFF9FB)
    val Ink = Color(0xFF21151B)
    val Muted = Color(0xFF665B62)
    val Outline = Color(0xFF81747B)
    val OutlineSoft = Color(0xFFF0DCE5)

    val NightSurface = Color(0xFF141015)
    val NightSurfaceHigh = Color(0xFF211920)
    val NightInk = Color(0xFFFFF1F7)
    val NightMuted = Color(0xFFC9B7C1)
    val NightBrand = Color(0xFFFF73B5)
    val NightAccent = Color(0xFFFFC36F)
}

val UndalLightScheme: ColorScheme = lightColorScheme(
    primary = UndalPalette.Brand,
    onPrimary = Color.White,
    primaryContainer = UndalPalette.BrandLight,
    onPrimaryContainer = UndalPalette.BrandDark,
    secondary = UndalPalette.AccentDark,
    onSecondary = Color.White,
    secondaryContainer = UndalPalette.AccentLight,
    onSecondaryContainer = UndalPalette.AccentDark,
    tertiary = UndalPalette.Success,
    onTertiary = Color.White,
    background = UndalPalette.Paper,
    onBackground = UndalPalette.Ink,
    surface = Color.White,
    onSurface = UndalPalette.Ink,
    surfaceVariant = Color(0xFFF4EDF1),
    onSurfaceVariant = UndalPalette.Muted,
    outline = UndalPalette.Outline,
    outlineVariant = UndalPalette.OutlineSoft,
    error = UndalPalette.Danger,
    onError = Color.White,
    errorContainer = UndalPalette.DangerLight,
    onErrorContainer = UndalPalette.Danger,
)

val UndalDarkScheme: ColorScheme = darkColorScheme(
    primary = UndalPalette.NightBrand,
    onPrimary = Color(0xFF3A001B),
    primaryContainer = Color(0xFF5B1438),
    onPrimaryContainer = Color(0xFFFFD8E7),
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
    surfaceVariant = Color(0xFF2C2228),
    onSurfaceVariant = UndalPalette.NightMuted,
    outline = Color(0xFFA08B96),
    outlineVariant = Color(0xFF4A3B43),
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
