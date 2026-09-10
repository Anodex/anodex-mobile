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

    /**
     * The same four colours again, re-stepped to be legible **as text**.
     *
     * `accent`, `danger`, `warn` and `success` were shared across both themes, and
     * they were chosen against a near-black field. Measured as text on the light
     * palette's cream, every one of them fails WCAG AA — accent at 3.03:1, danger at
     * 3.13:1, and warn and success at 1.91:1 and 1.90:1, which is barely legible at
     * all. On Midnight the same values measure 5.3 to 9.7. The light theme was
     * genuinely a second-class citizen here, which `AGENTS.md` says it must not be.
     *
     * A re-stepping, not a dimming — the same thing the series colours already do,
     * and for the same reason. Each ink is its base colour walked down in lightness
     * at constant hue and saturation until it clears 4.5:1 against every ground it
     * lands on: `bgApp`, `bgSurface2`, `bgElevated`, `bgBase` and its own 12% soft
     * wash. On Midnight the bases already clear all of those, so the inks *are* the
     * bases and nothing changes.
     *
     * **Ink is for text and for glyphs read at text size.** Fills, borders, status
     * dots and meter bars keep the base colour: those are large blocks, they answer
     * to the 3:1 non-text threshold, and they already pass.
     *
     * `ContrastTest` measures all of this on every push. It is not decoration: the
     * failure here was never writing an obviously wrong colour, it was adding a
     * token and never once looking at it against the pale ground.
     */
    val accentInk: Color,
    val dangerInk: Color,
    val warnInk: Color,
    val successInk: Color,

    /**
     * The logo ramp's two brightest steps, same treatment.
     *
     * These are worse than the four above, not better: on cream, `accentGreen`
     * measures 1.14:1 and `accentCyan` 1.72:1 — under the 3:1 floor for a *graphical*
     * object, never mind text. A diff's added lines and an agent run's "working" dot
     * were effectively invisible in the light theme.
     *
     * `accentViolet` is left alone. It clears 3:1 as a shape, and the only places it
     * appears are an avatar fill and the two flat planes behind an empty
     * conversation, which are both large blocks by design.
     */
    val accentGreenInk: Color,
    val accentCyanInk: Color,

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

    /**
     * The block drawn where a line of text has not arrived yet — see [ListSkeleton].
     *
     * A wash of the page's own text colour, for the reason a placeholder exists: it
     * stands in for words. Derived rather than pointed at a surface token, because
     * the surface that used to serve — `bgElevated` — climbs *towards* white on the
     * light theme, and a white bar on a cream card reads as a highlight rather than
     * as something missing. A tint of the text is darker than its ground in Light
     * and lighter than its ground in Midnight, which is correct in both.
     */
    val placeholder: Color get() = text.copy(alpha = 0.09f)
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

    // Nothing to re-step. Measured against every dark ground these read between
    // 5.3:1 and 9.7:1, so an ink of their own would be a second name for the same
    // colour and a second thing to keep in step.
    accentInk = Color(0xFF4F8CFF),
    dangerInk = Color(0xFFF05A5A),
    warnInk = Color(0xFFF5A623),
    successInk = Color(0xFF3CCF7A),
    accentGreenInk = Color(0xFF74F0A8),
    accentCyanInk = Color(0xFF38BDF8),

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
    // A ladder, and it did not used to be one.
    //
    // `bgApp` and `bgSurface` were the same value, byte for byte, and so was
    // `bgInput`. In a theme that separates surfaces by colour rather than by shadow
    // — which this one does, deliberately; see [Elevation] — that means a filled
    // card had no boundary at all on the light theme, and neither did a search
    // field or the resting shape of a loading list. Only the things that happened
    // to draw a border survived.
    //
    // Midnight climbs from near-black towards grey as a surface rises. Light climbs
    // from a warm ground towards white, which is the same relationship rather than
    // the same direction. Every step here is at least as separated as the matching
    // step in Midnight — `ContrastTest` measures exactly that, holding the light
    // theme to the dark theme's own weakest step so neither can quietly flatten.
    bgBase = Color(0xFFE8E4DB),
    bgApp = Color(0xFFF1EEE7),
    bgSurface = Color(0xFFF8F6F2),
    bgSurface2 = Color(0xFFFDFCFA),
    bgElevated = Color(0xFFFFFFFF),
    bgInput = Color(0xFFFFFFFF),

    border = Color(0xFFE3E0D8),
    borderStrong = Color(0xFFD1CDC2),

    text = Color(0xFF23211D),
    // Half a step deeper than it was. Against the new deepest ground — the drawer's
    // — the old value measured 4.24:1, just under the floor. Same warm hue.
    textMuted = Color(0xFF645E54),
    textFaint = Color(0xFFA29C8F),

    series1 = Color(0xFF5B4EC6),
    series2 = Color(0xFF00A084),
    series3 = Color(0xFFA36900),
    series4 = Color(0xFF980052),

    // The dark-mode pastel blue reads as near-invisible on a light surface.
    codeInlineText = Color(0xFF1A56DB),

    // Worst-case ratios against bgApp, bgSurface2, bgElevated, bgBase and the 12%
    // wash of the matching base colour: 5.14, 5.24, 4.76, 5.27. Same hues.
    accentInk = Color(0xFF1F58C7),
    dangerInk = Color(0xFFB3261E),
    warnInk = Color(0xFF8A5A00),
    successInk = Color(0xFF146B3C),
    // Worst case 4.66 apiece, on the same four grounds.
    accentGreenInk = Color(0xFF0D7538),
    accentCyanInk = Color(0xFF056C9A),

    isDark = false,
)
