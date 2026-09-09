package dev.anodex.mobile.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Which edge a panel comes in from. */
enum class PanelSide { LEFT, RIGHT }

/**
 * A panel that slides in from an edge, and follows the finger while it does.
 *
 * The drawer used to appear with `AnimatedVisibility`: a tap on the hamburger played
 * a fixed 300ms slide and that was the only way in or out. It looked fine and felt
 * like a slideshow, because the thing being dragged was never actually attached to
 * the hand dragging it. Here the panel's position *is* the gesture — drag it halfway
 * and it sits halfway, let go and it finishes the way you threw it, and the scrim
 * behind darkens in step so the page underneath is visibly being covered rather than
 * switched off.
 *
 * Must be called inside a [Box] that fills the screen; it aligns itself against that
 * box's edges and draws its own scrim over everything already in it.
 *
 * **The edge belongs to Android first.** On gesture navigation the outermost band of
 * both edges is the system's back gesture and no app sees those touches, so
 * [EDGE_GRAB] is deliberately wider than that band: a swipe starting right at the
 * edge goes back, one starting a thumb's width inside opens the panel. Claiming the
 * whole edge is possible — `systemGestureExclusionRects` — but Android caps it at
 * 200dp per edge, so it would work at the bottom of the screen and nowhere else,
 * which is worse than a rule that holds everywhere.
 */
@Composable
fun BoxScope.SlidingPanel(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    side: PanelSide,
    modifier: Modifier = Modifier,
    /** How far across it reaches, leaving the page visible beside it. */
    widthFraction: Float = 0.86f,
    /** Dark enough to push the page back, light enough that it is plainly still there. */
    scrimAlpha: Float = 0.55f,
    /**
     * Whether the closed-state edge grab is live.
     *
     * False where the panel has nothing to show — the files panel over a chat that
     * belongs to no workspace. A gesture that opens an empty panel teaches that the
     * gesture is broken.
     */
    edgeGrabEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val panelWidth = LocalConfiguration.current.screenWidthDp.dp * widthFraction
    val widthPx = with(LocalDensity.current) { panelWidth.toPx() }
    val scope = rememberCoroutineScope()

    // 0 closed, 1 open. Kept as a fraction rather than as pixels so a rotation, which
    // changes the width under it, cannot leave the panel parked at an offset that no
    // longer means anything.
    val progress = remember { Animatable(if (open) 1f else 0f) }

    // Which way a finger has to move to open this one.
    val towardsOpen = if (side == PanelSide.LEFT) 1f else -1f

    LaunchedEffect(open) { progress.animateTo(if (open) 1f else 0f, SETTLE) }

    val drag = rememberDraggableState { delta ->
        scope.launch {
            progress.snapTo((progress.value + towardsOpen * delta / widthPx).coerceIn(0f, 1f))
        }
    }

    val dragging = Modifier.draggable(
        state = drag,
        orientation = Orientation.Horizontal,
        // So a panel still gliding into place can be caught and dragged back, rather
        // than ignoring the hand until it has finished its animation.
        startDragImmediately = progress.isRunning,
        // Where a half-finished gesture ends up. Thrown hard enough it goes the way
        // it was thrown, even from an inch in; released slowly, the halfway mark
        // decides. A panel that snapped back whenever the finger stopped short would
        // make a slow, careful drag the one gesture that never works.
        onDragStopped = { velocity ->
            val towards = towardsOpen * velocity
            val target = when {
                towards > FLING -> true
                towards < -FLING -> false
                else -> progress.value > 0.5f
            }

            onOpenChange(target)
            progress.animateTo(
                targetValue = if (target) 1f else 0f,
                animationSpec = SETTLE,
                // Carried into the settle so the panel keeps the speed it was let go
                // at instead of stopping dead and starting again.
                initialVelocity = towardsOpen * velocity / widthPx,
            )
        },
    )

    // Read as a boolean rather than as the raw fraction: this decides whether the
    // panel exists at all, and recomposing the whole thing on every frame of a drag
    // to answer a question whose answer changes twice is how a smooth gesture stops
    // being smooth. The moving parts read the fraction in draw, not in composition.
    val showing by remember { derivedStateOf { progress.value > 0f } }

    // Always present, and first, so the panel and its scrim sit over it. Composed
    // conditionally it would vanish the instant a drag moved the panel off zero —
    // taking the pointer handler that was mid-gesture with it.
    if (edgeGrabEnabled) {
        Box(
            Modifier
                .align(if (side == PanelSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
                .fillMaxHeight()
                .width(EDGE_GRAB)
                .then(dragging)
        )
    }

    if (showing) {
        Box(
            Modifier
                .fillMaxSize()
                // Drawn, not backgrounded: the alpha is read in the draw phase, so a
                // drag darkens the page without recomposing anything behind it.
                .drawBehind { drawRect(Color.Black, alpha = scrimAlpha * progress.value) }
                // The gesture people already expect from every drawer on the phone.
                .pointerInput(Unit) { detectTapGestures { onOpenChange(false) } }
                .then(dragging)
        )

        Box(
            modifier
                .align(if (side == PanelSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
                .width(panelWidth)
                .fillMaxHeight()
                .graphicsLayer {
                    translationX = -towardsOpen * (1f - progress.value) * widthPx
                }
                .then(dragging)
        ) {
            content()
        }
    }
}

/**
 * How wide the invisible strip is that starts the gesture.
 *
 * Wider than a drawer handle needs to be, because the outer part of it is not ours:
 * see the note on [SlidingPanel] about the system back gesture. Forty leaves a usable
 * band inside Android's.
 */
private val EDGE_GRAB = 40.dp

/**
 * How hard a throw decides the outcome on its own, in pixels per second.
 *
 * Below this the panel goes wherever it was left — past halfway it opens, short of it
 * it closes.
 */
private const val FLING = 400f

/**
 * No bounce.
 *
 * A panel that overshoots and comes back reads as a physical object that has been
 * thrown, which is charming exactly once. This is a sheet of the interface sliding
 * into place, and it should arrive and stop.
 */
private val SETTLE = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)
