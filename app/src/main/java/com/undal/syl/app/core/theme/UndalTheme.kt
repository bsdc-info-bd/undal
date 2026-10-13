package com.undal.syl.app.core.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext

/**
 * The single theme entry point for the Undal app.
 *
 * - Dark and light follow the phone automatically.
 * - Android 12+ gets dynamic colour, so the app sits naturally in the phone's
 *   palette while the Undal brand mark, status pills and order-alert screens
 *   keep their meaning-carrying colours from [UndalPalette].
 */
val LocalUndalSemantics = staticCompositionLocalOf { UndalLightSemantics }

@Composable
fun UndalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> UndalDarkScheme
        else -> UndalLightScheme
    }
    val semantics = if (darkTheme) UndalDarkSemantics else UndalLightSemantics

    CompositionLocalProvider(LocalUndalSemantics provides semantics) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = UndalTypography,
            shapes = UndalShapes,
            content = content
        )
    }
}

/** Shortcut for the semantic colours that are not part of the Material scheme. */
val MaterialTheme.undalSemantics: UndalSemanticColors
    @Composable get() = LocalUndalSemantics.current
