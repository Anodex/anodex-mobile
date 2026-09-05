package dev.anodex.mobile.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * The Anodex palette in scope. Read it as `AnodexTheme.colors` rather than through this directly.
 */
val LocalAnodexColors: ProvidableCompositionLocal<AnodexColors> =
    staticCompositionLocalOf { MidnightColors }

val LocalAnodexTypography: ProvidableCompositionLocal<AnodexTypography> =
    staticCompositionLocalOf { AnodexTypography() }

/** Token accessors. `AnodexTheme.colors.accent`, `AnodexTheme.type.body`. */
object AnodexTheme {
    val colors: AnodexColors
        @Composable get() = LocalAnodexColors.current

    val type: AnodexTypography
        @Composable get() = LocalAnodexTypography.current
}

/**
 * Wraps the app in Anodex's design system.
 *
 * Two colour systems are provided, and the split is deliberate. [LocalAnodexColors] is the source
 * of truth and what app code should read. A Material 3 [androidx.compose.material3.ColorScheme] is
 * derived from it so that Material components — ripples, text-field decorations, anything pulled
 * in later — pick up the right colours instead of defaulting to purple. Material's scheme is a
 * projection of Anodex's palette, never the other way round.
 *
 * Dynamic colour (Material You) is **not** supported and should not be added. Anodex's identity is
 * its palette; a phone recolouring the app from the user's wallpaper would make the companion stop
 * looking like the thing it accompanies, which is the entire point of porting these tokens.
 */
@Composable
fun AnodexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) MidnightColors else LightColors
    val reducedMotion = rememberSystemReducedMotion()

    val materialScheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.textOnAccent,
            primaryContainer = colors.accentSoft,
            onPrimaryContainer = colors.text,
            secondary = colors.accentViolet,
            onSecondary = colors.textOnAccent,
            background = colors.bgApp,
            onBackground = colors.text,
            surface = colors.bgSurface,
            onSurface = colors.text,
            surfaceVariant = colors.bgSurface2,
            onSurfaceVariant = colors.textMuted,
            error = colors.danger,
            onError = colors.textOnAccent,
            errorContainer = colors.dangerSoft,
            outline = colors.border,
            outlineVariant = colors.borderStrong,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.textOnAccent,
            primaryContainer = colors.accentSoft,
            onPrimaryContainer = colors.text,
            secondary = colors.accentViolet,
            onSecondary = colors.textOnAccent,
            background = colors.bgApp,
            onBackground = colors.text,
            surface = colors.bgSurface,
            onSurface = colors.text,
            surfaceVariant = colors.bgSurface2,
            onSurfaceVariant = colors.textMuted,
            error = colors.danger,
            onError = colors.textOnAccent,
            errorContainer = colors.dangerSoft,
            outline = colors.border,
            outlineVariant = colors.borderStrong,
        )
    }

    // System bar icons have to invert with the theme, or they vanish into the background. The app
    // draws edge to edge (see MainActivity), so the bars themselves stay transparent.
    val view = LocalView.current
    if (!view.isInEditMode) {
        val context = LocalContext.current
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(
        LocalAnodexColors provides colors,
        LocalAnodexTypography provides AnodexTypography(),
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(colorScheme = materialScheme, content = content)
    }
}
