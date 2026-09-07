package dev.anodex.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import dev.anodex.mobile.ui.theme.AnodexTheme

/**
 * Two flat planes behind an otherwise empty screen.
 *
 * Every assistant's home screen is a black void, and Anodex's looked like one too —
 * which reads less like restraint than like a screen that failed to load. This is
 * the smallest thing that fixes it: two angled planes at four or five percent
 * opacity, in the violet and cyan the mark is already made of, echoing its facets.
 *
 * Static, deliberately. The house rule is that motion conveys state rather than
 * decorating, and a home screen that shimmers while nothing is happening is the
 * exact thing that rule exists to prevent. It is a texture, not an animation.
 */
@Composable
fun FacetField(modifier: Modifier = Modifier) {
    val colors = AnodexTheme.colors

    Canvas(modifier) {
        val w = size.width
        val h = size.height

        // Angled across the screen rather than square to it, because the mark's own
        // facets are diagonal and a horizontal band would read as a divider.
        val upper = Path().apply {
            moveTo(0f, h * 0.28f)
            lineTo(w, h * 0.10f)
            lineTo(w, h * 0.50f)
            lineTo(0f, h * 0.70f)
            close()
        }
        val lower = Path().apply {
            moveTo(0f, h * 0.60f)
            lineTo(w, h * 0.43f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }

        drawPath(
            upper,
            Brush.linearGradient(
                colors = listOf(
                    colors.accentViolet.copy(alpha = 0.055f),
                    colors.accent.copy(alpha = 0f),
                ),
                start = Offset(0f, 0f),
                end = Offset(w, h),
            ),
        )
        drawPath(
            lower,
            Brush.linearGradient(
                colors = listOf(
                    colors.accentCyan.copy(alpha = 0.045f),
                    colors.accent.copy(alpha = 0f),
                ),
                start = Offset(w, 0f),
                end = Offset(0f, h),
            ),
        )
    }
}
