package dev.anodex.mobile.ui.components

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.LocalReducedMotion

/**
 * A small state, with a halo when it is working.
 *
 * The dot itself never blinks or fades: whatever it says stays legible at every
 * moment, because a status light that spends half its time invisible is one you
 * cannot check at a glance. The motion is a ring rippling outward *around* it —
 * the desktop's rule, ported.
 *
 * Standing still under reduce-motion leaves the dot exactly as informative, which
 * is the test for whether motion was ever carrying the meaning.
 */
@Composable
fun StatusDot(
    colour: Color,
    modifier: Modifier = Modifier,
    /** Whether to ripple. False is a plain dot, and the usual case. */
    running: Boolean = false,
    size: Dp = 8.dp,
) {
    val reducedMotion = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "statusHalo")

    // 0.6 out to 2.1, fading as it goes, then held at nothing for the tail of the
    // cycle — so it reads as an occasional pulse rather than a continuous throb.
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_200, easing = LinearOutSlowInEasing)),
        label = "halo",
    )

    Canvas(modifier.size(size * 2.6f)) {
        val centre = Offset(this.size.width / 2, this.size.height / 2)
        val radius = size.toPx() / 2

        if (running && !reducedMotion) {
            // The desktop holds the last 30% of the cycle at nothing, which is what
            // turns a pulse into a heartbeat rather than a hum.
            val ripple = (progress / 0.7f).coerceAtMost(1f)
            if (progress < 0.7f) {
                drawCircle(
                    color = colour.copy(alpha = 0.9f * (1f - ripple)),
                    radius = radius * (0.6f + ripple * 1.5f),
                    center = centre,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }

        drawCircle(color = colour, radius = radius, center = centre)
    }
}

/** The dot's colour for a run's state, so two screens cannot disagree about it. */
@Composable
fun runningColour(): Color = AnodexTheme.colors.accent
