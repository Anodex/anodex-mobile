package dev.anodex.mobile.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Anodex's colour palette, ported token-for-token from the desktop app's
 * `styles/themes/midnight.css` and `styles/themes/light.css`.
 *
 * This is deliberately *not* expressed as a Material 3 [androidx.compose.material3.ColorScheme].
 * Anodex's palette carries roles Material has no slot for — `bgSurface2`, `textFaint`,
 * `accentSoft`, the four-step categorical series — and forcing them through Material's
 * primary/secondary/tertiary vocabulary would lose the names that make the design legible.
 * [AnodexTheme] derives a Material scheme *from* this one so Material widgets inherit correctly;
 * this stays the source of truth.
 *
 * Values must match the desktop exactly. If a token changes there, change it here — the two
 * palettes drifting is how a companion app stops looking like the thing it accompanies.
 */
@Immutable
data class AnodexColors(
    // Surfaces
    val bgBase: Color,
    val bgApp: Color,
    val bgSurface: Color,
    val bgSurface2: Color,
    val bgElevated: Color,
    val bgInput: Color,

    // Borders & dividers
    val border: Color,
    val borderStrong: Color,
    val borderFocus: Color,

    // Text
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val textOnAccent: Color,

    // Brand accents
    val accent: Color,
    val accentHover: Color,
    val accentSoft: Color,
    val accentCyan: Color,
    val accentViolet: Color,
    val accentVioletSoft: Color,
    val accentGreen: Color,
    val accentGreenSoft: Color,

    // Categorical chart series, in fixed order
    val series1: Color,
    val series2: Color,
    val series3: Color,
    val series4: Color,

    // Status
    val success: Color,
    val successSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val danger: Color,
    val dangerHover: Color,
    val dangerSoft: Color,
    val info: Color,
    val infoSoft: Color,

    // Inline `code` in chat markdown — needs its own token because it has to stay
    // readable against bgBase in both light and dark.
    val codeInlineText: Color,

    /** True for Midnight and any other dark rendition. Drives status-bar icon polarity. */
    val isDark: Boolean,
) {
    val series: List<Color> get() = listOf(series1, series2, series3, series4)

    /**
     * The wash a control wears while a finger is on it. See [dev.anodex.mobile.ui.theme.AnodexPress].
     *
     * Derived rather than declared, because there is exactly one right answer for a
     * given palette and no theme should be able to get it wrong: the page's own text
     * colour, which is a near-white on Midnight and a warm charcoal on Light, at an
     * alpha low enough to read as a press and not as a selection.
     *
     * Light needs less of it. The same alpha of charcoal on cream is a heavier mark
     * than that alpha of near-white on near-black, because the light theme's surfaces
     * sit much closer to the tint than the dark theme's do.
     */
    val pressTint: Color get() = text.copy(alpha = if (isDark) 0.09f else 0.06f)
}

/**
 * "Midnight" — Anodex's default dark palette, shown in desktop Settings as "Anodex".
 *
 * Taken from the Anodex logo: a near-black field, a cyan → blue → violet ramp for the "A" mark,
 * and cool blue glows on the hexagon edges. The surfaces are neutral dark greys — flat and
 * professional, no neon.
 */
val MidnightColors = AnodexColors(
    bgBase = Color(0xFF080808),
    bgApp = Color(0xFF0C0C0C),
    bgSurface = Color(0xFF111111),
    bgSurface2 = Color(0xFF161616),
    bgElevated = Color(0xFF1C1C1C),
    bgInput = Color(0xFF0F0F0F),

    border = Color(0xFF1F1F1F),
    borderStrong = Color(0xFF2A2A2A),
    // Fixed in every theme on the desktop, so fixed here too.
    borderFocus = Color(0xFF4F8CFF),

    text = Color(0xFFF0F0F0),
    textMuted = Color(0xFFA0A0A0),
    textFaint = Color(0xFF606060),
    textOnAccent = Color(0xFFFFFFFF),

    accent = Color(0xFF4F8CFF),
    accentHover = Color(0xFF3D7BE8),
    accentSoft = Color(0xFF4F8CFF).copy(alpha = 0.12f),
    // The one deliberate exception to the single-blue rule: paired with accent and accentViolet
    // for the logo's cyan → blue → violet ramp, used only for rare "arrival" moments that earn a
    // richer accent. Not a general-purpose token.
    accentCyan = Color(0xFF38BDF8),
    accentViolet = Color(0xFF7C5CFF),
    accentVioletSoft = Color(0xFF7C5CFF).copy(alpha = 0.12f),
    accentGreen = Color(0xFF74F0A8),
    accentGreenSoft = Color(0xFF74F0A8).copy(alpha = 0.12f),

    // Not derived from accent: the brand palette is monochromatic, and accent against
    // accentViolet measures ΔE 11.5 in normal vision — under the 15 floor, i.e. two series
    // nobody could tell apart. These are computed per surface with lightness staggered so the
    // pairs that collapse under deuteranopia separate on lightness instead. Validated all-pairs
    // against bgSurface: worst deutan ΔE 12.0. Re-validate before changing any value.
    series1 = Color(0xFF6E53FF),
    series2 = Color(0xFF00AC8F),
    series3 = Color(0xFFB67700),
    series4 = Color(0xFFC3006B),

    success = Color(0xFF3CCF7A),
    successSoft = Color(0xFF3CCF7A).copy(alpha = 0.12f),
    warn = Color(0xFFF5A623),
    warnSoft = Color(0xFFF5A623).copy(alpha = 0.12f),
    danger = Color(0xFFF05A5A),
    dangerHover = Color(0xFFD64D4D),
    dangerSoft = Color(0xFFF05A5A).copy(alpha = 0.12f),
    info = Color(0xFF4F8CFF),
    infoSoft = Color(0xFF4F8CFF).copy(alpha = 0.12f),

    codeInlineText = Color(0xFFA5D6FF),

    isDark = true,
)

/**
 * "Anodex Light" — the default light palette.
 *
 * Deliberately not stark white-on-near-black: surfaces are a soft warm off-white and body text is
 * a warm charcoal, so the theme doesn't read as a glare next to Midnight. Still comfortably
 * high-contrast.
 *
 * Only the surface/border/text tokens differ on the desktop — accents and status colours are
 * shared. The series steps are their own values rather than the dark set lightened: the same four
 * hues re-computed so every check still passes against cream (worst deutan ΔE 12.5). It is a
 * re-stepping, not a dimming.
 */
val LightColors = MidnightColors.copy(
    bgBase = Color(0xFFF2F0EB),
    bgApp = Color(0xFFF9F8F5),
    bgSurface = Color(0xFFF9F8F5),
    bgSurface2 = Color(0xFFF0EEE8),
    bgElevated = Color(0xFFE9E6DF),
    bgInput = Color(0xFFF9F8F5),

    border = Color(0xFFE3E0D8),
    borderStrong = Color(0xFFD1CDC2),

    text = Color(0xFF23211D),
    textMuted = Color(0xFF6F6A60),
    textFaint = Color(0xFFA29C8F),

    series1 = Color(0xFF5B4EC6),
    series2 = Color(0xFF00A084),
    series3 = Color(0xFFA36900),
    series4 = Color(0xFF980052),

    // The dark-mode pastel blue reads as near-invisible on a light surface.
    codeInlineText = Color(0xFF1A56DB),

    isDark = false,
)
