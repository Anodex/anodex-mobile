package dev.anodex.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Anodex's structural tokens — spacing, radii, elevation, layout — ported from the desktop app's
 * `styles/theme.css`, which shares them across every colour theme.
 *
 * Spacing and radii port across unchanged: they are a rhythm, not a density, and 4dp on a phone
 * reads as the same relationship 4px does on a monitor. Typography does *not* port unchanged —
 * see [AnodexTypography] for why, and what was done instead.
 */
object Spacing {
    val x1: Dp = 4.dp
    val x2: Dp = 8.dp
    val x3: Dp = 12.dp
    val x4: Dp = 16.dp
    val x5: Dp = 20.dp
    val x6: Dp = 24.dp
    val x8: Dp = 32.dp
    val x10: Dp = 40.dp
}

/** Less rounded, more professional — the desktop's own note on this scale. */
object Radii {
    val sm = RoundedCornerShape(4.dp)
    val md = RoundedCornerShape(6.dp)
    val lg = RoundedCornerShape(8.dp)
    val xl = RoundedCornerShape(12.dp)
    val pill = RoundedCornerShape(999.dp)
}

/**
 * Shadow depths. The desktop's are minimal and pure black at 40–50% alpha — no neon glows.
 *
 * Compose expresses elevation as a Dp that drives both a shadow and (in Material) a surface tint.
 * Anodex has no tint-on-elevation behaviour, so surfaces are separated by *colour* — bgSurface
 * against bgSurface2 against bgElevated — and these are used sparingly, for genuinely floating
 * things only.
 */
object Elevation {
    val none: Dp = 0.dp
    val sm: Dp = 1.dp
    val md: Dp = 4.dp
    val lg: Dp = 12.dp
}

/**
 * Touch sizing, which has no desktop counterpart and cannot be ported.
 *
 * The desktop's buttons are 26px tall — correct for a mouse, unreachable for a thumb. Android's
 * accessibility floor is 48dp, and nothing interactive in this app may be smaller. Where Anodex's
 * visual proportions want a smaller control, keep the control small and expand its *touch* target
 * with padding rather than shrinking the target.
 */
object Touch {
    val minTarget: Dp = 48.dp
}
