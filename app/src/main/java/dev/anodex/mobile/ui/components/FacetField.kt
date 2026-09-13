package dev.anodex.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import dev.anodex.mobile.ui.theme.AnodexTheme

/**
 * A light behind an otherwise empty screen.
 *
 * Every assistant's home screen is a black void, and Anodex's looked like one too —
 * which reads less like restraint than like a screen that failed to load.
 *
 * This used to be two angled planes echoing the mark's facets. The idea was right and
 * the execution was not: a gradient drawn inside a `Path` still stops at the path's
 * edge, so both planes ended in a visible diagonal line across the screen. On a device
 * it read as two surfaces that did not quite meet — the exact "unfinished" quality it
 * was added to remove.
 *
 * So it is one wash over the whole area with no boundary anywhere in it. A radial
 * glow centred below the bottom edge, violet at its heart and gone well before the
 * middle of the screen, with the mark's cyan lifting one corner. Nothing is clipped,
 * so there is no edge to catch.
 *
 * The light comes from the bottom because that is where the composer is, and the
 * screen should feel lit by the thing you are about to use.
 *
 * Static, deliberately. The house rule is that motion conveys state rather than
 * decorating, and a home screen that shimmers while nothing is happening is the exact
 * thing that rule exists to prevent. It is a texture, not an animation.
 */
@Composable
fun FacetField(modifier: Modifier = Modifier) {
    val colors = AnodexTheme.colors

    Canvas(modifier) {
        val w = size.width
        val h = size.height

        // Centred below the bottom edge so only the top of the glow is on screen —
        // the part of a radial gradient with no visible falloff ring.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    colors.accentViolet.copy(alpha = 0.16f),
                    colors.accentViolet.copy(alpha = 0.05f),
                    colors.accent.copy(alpha = 0f),
                ),
                center = Offset(w * 0.5f, h * 1.12f),
                radius = h * 0.78f,
            ),
        )

        // A second, weaker source in the mark's cyan, off to one side. Enough to keep
        // the wash from reading as one flat colour, far too faint to find if you go
        // looking for a second light.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    colors.accentCyan.copy(alpha = 0.06f),
                    colors.accent.copy(alpha = 0f),
                ),
                center = Offset(w * 0.06f, h * 1.02f),
                radius = h * 0.5f,
            ),
        )
    }
}
