package dev.anodex.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Where a Settings section sits on the mark's cyan-to-violet ramp.
 *
 * The index gives each section a colour by its position in the list, so the
 * colour is a fact about where you are rather than a decoration — and the
 * section screen behind it then carries the same colour, so the room matches the
 * door you came through.
 *
 * Two ramps, not one, and the difference matters. [sectionTint] is the logo's
 * own colours, for chips and washes, which are graphical objects held to 3:1.
 * [sectionInk] is the readable rendition, for headings and ticks, which are text
 * and held to 4.5:1. Using the first one for text is how a heading ends up at
 * 3.43:1 on cream, which is where this started — see `ContrastTest`, which
 * measures every step of both ramps against every surface in both themes.
 */
fun sectionTint(index: Int, count: Int, colors: AnodexColors): Color =
    ramp(index, count, colors.accentCyan, colors.accent, colors.accentViolet)

/** The same position on the ramp, in colours that may be read as text. */
fun sectionInk(index: Int, count: Int, colors: AnodexColors): Color =
    ramp(index, count, colors.accentCyanInk, colors.accentInk, colors.accentVioletInk)

private fun ramp(index: Int, count: Int, start: Color, middle: Color, end: Color): Color {
    val position = index.toFloat() / (count - 1).coerceAtLeast(1)
    return if (position < 0.5f) lerp(start, middle, position * 2f)
    else lerp(middle, end, (position - 0.5f) * 2f)
}
