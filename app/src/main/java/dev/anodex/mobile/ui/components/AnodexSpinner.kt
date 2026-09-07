package dev.anodex.mobile.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.LocalReducedMotion

/** Which loader to draw. */
enum class SpinnerVariant {
    /** The everyday one. */
    RING,

    /**
     * A runner lapping the Anodex hexagon.
     *
     * Reserved for brand moments — loading a model, starting the engine — the same
     * rule the desktop applies. Everywhere is nowhere: used for every wait, it stops
     * meaning "this one is significant".
     */
    HEX,
}

/**
 * Something is happening.
 *
 * The arc grows and shrinks as it orbits, so it reads as *effort* rather than plain
 * rotation — a ring turning at a constant rate says a machine is idling, and one
 * that stretches and gathers says work is being done. Ported from the desktop's own
 * spinner, down to the two periods being deliberately different (1.5s to go round,
 * 1.4s to breathe) so the two never sync into something mechanical.
 *
 * Honours the system's reduce-motion setting by standing still. A still arc is a
 * perfectly good "waiting"; a spinning one somebody asked not to see is not.
 */
@Composable
fun AnodexSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    thickness: Dp = 2.dp,
    variant: SpinnerVariant = SpinnerVariant.RING,
    tint: Color = AnodexTheme.colors.textMuted,
) {
    val reducedMotion = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "spinner")

    val turn by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_500, easing = LinearEasing)),
        label = "turn",
    )

    // 6% of the circle at its tightest, 62% at its fullest — the desktop's own
    // dash-array, in the units Compose draws arcs with.
    val sweep by transition.animateFloat(
        initialValue = 0.06f * 360f,
        targetValue = 0.62f * 360f,
        animationSpec = infiniteRepeatable(tween(1_400), RepeatMode.Reverse),
        label = "sweep",
    )

    val stroke = with(LocalDensity.current) { thickness.toPx() }

    when (variant) {
        SpinnerVariant.RING -> Canvas(modifier.size(size)) {
            val inset = stroke / 2
            drawArc(
                color = tint,
                startAngle = if (reducedMotion) -90f else turn,
                sweepAngle = if (reducedMotion) 90f else sweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }

        SpinnerVariant.HEX -> {
            // The mark's own facet, traced. Measured once rather than per frame:
            // this is on screen for the whole of a model load, which is minutes.
            val path = remember { PathParser().parsePathString(HEX_PATH).toPath() }
            val measure = remember(path) { PathMeasure().apply { setPath(path, false) } }
            val length = remember(measure) { measure.length }

            Canvas(modifier.size(size)) {
                val factor = this.size.minDimension / VIEWPORT
                scale(factor, pivot = Offset.Zero) {
                    val outline = Stroke(
                        width = stroke / factor,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    )

                    // The track it runs on, so the shape is a hexagon even at the
                    // moment the runner is on the far side of it.
                    drawPath(path, tint.copy(alpha = 0.15f), style = outline)

                    val start = if (reducedMotion) 0f else (turn / 360f) * length
                    val runner = androidx.compose.ui.graphics.Path()
                    val segment = length * 0.26f

                    // Wrapped by hand: getSegment does not run past the end, so a
                    // runner crossing the start would otherwise be cut in half.
                    measure.getSegment(start, minOf(start + segment, length), runner, true)
                    if (start + segment > length) {
                        measure.getSegment(0f, (start + segment) - length, runner, true)
                    }

                    drawPath(runner, tint, style = outline)
                }
            }
        }
    }
}

private const val VIEWPORT = 24f

/** `Icon.tsx`'s hexagon, the same one the desktop's brand spinner laps. */
private const val HEX_PATH = "M12 3l7.8 4.5v9L12 21l-7.8-4.5v-9L12 3z"
