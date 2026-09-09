package dev.anodex.mobile.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Which edge a panel comes in from. */
enum class PanelSide { LEFT, RIGHT }

/**
 * One sliding panel: how far open it is, and the gestures that move it.
 *
 * Hoisted out of the panel itself because the two gestures belong to two different
 * places on screen. Dragging a panel *closed* happens on the panel, which is drawn
 * over everything and is supposed to be. Dragging one *open* happens on the page,
 * which is not — see [panelEdgeGrab].
 */
@Stable
class PanelSwipe internal constructor(
    internal val side: PanelSide,
    internal val panelWidth: Dp,
    internal val widthPx: Float,
    internal val progress: Animatable<Float, AnimationVector1D>,
    internal val edgeGrabEnabled: Boolean,
    private val scope: CoroutineScope,
    private val onOpenChange: (Boolean) -> Unit,
) {
    /** Which way a finger has to move to open this one. */
    internal val towardsOpen = if (side == PanelSide.LEFT) 1f else -1f

    /** Close it outright — a tap on the scrim, which is not a throw. */
    internal fun close() {
        onOpenChange(false)
        scope.launch { progress.animateTo(0f, SETTLE) }
    }

    internal fun dragBy(deltaPx: Float) {
        scope.launch {
            progress.snapTo((progress.value + towardsOpen * deltaPx / widthPx).coerceIn(0f, 1f))
        }
    }

    /**
     * Where a half-finished gesture ends up.
     *
     * Thrown hard enough it goes the way it was thrown, even from an inch in;
     * released slowly, the halfway mark decides. A panel that snapped back whenever
     * the finger stopped short would make a slow, careful drag the one gesture that
     * never works.
     */
    internal fun settle(velocityPx: Float) {
        val towards = towardsOpen * velocityPx
        val target = when {
            towards > FLING -> true
            towards < -FLING -> false
            else -> progress.value > 0.5f
        }

        onOpenChange(target)
        scope.launch {
            progress.animateTo(
                targetValue = if (target) 1f else 0f,
                animationSpec = SETTLE,
                // Carried into the settle so the panel keeps the speed it was let go
                // at instead of stopping dead and starting again.
                initialVelocity = towardsOpen * velocityPx / widthPx,
            )
        }
    }
}

/**
 * The state one [SlidingPanel] runs on, and the thing to hand [panelEdgeGrab].
 *
 * The offset is kept as a fraction rather than as pixels so a rotation, which changes
 * the width under it, cannot leave a panel parked at an offset that no longer means
 * anything.
 */
@Composable
fun rememberPanelSwipe(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    side: PanelSide,
    /** How far across it reaches, leaving the page visible beside it. */
    widthFraction: Float = 0.86f,
    /**
     * Whether the edge swipe is live.
     *
     * False where the panel has nothing to show — the files panel over a chat that
     * belongs to no workspace. A gesture that opens an empty panel teaches that the
     * gesture is broken.
     */
    edgeGrabEnabled: Boolean = true,
): PanelSwipe {
    val panelWidth = LocalConfiguration.current.screenWidthDp.dp * widthFraction
    val widthPx = with(LocalDensity.current) { panelWidth.toPx() }
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(if (open) 1f else 0f) }
    val latestOnOpenChange by rememberUpdatedState(onOpenChange)

    LaunchedEffect(open) { progress.animateTo(if (open) 1f else 0f, SETTLE) }

    return remember(side, panelWidth, widthPx, edgeGrabEnabled, progress, scope) {
        PanelSwipe(
            side = side,
            panelWidth = panelWidth,
            widthPx = widthPx,
            progress = progress,
            edgeGrabEnabled = edgeGrabEnabled,
            scope = scope,
            onOpenChange = { latestOnOpenChange(it) },
        )
    }
}

/**
 * The edge swipe that opens a panel. **Belongs on the page, not over it.**
 *
 * This was an invisible strip laid over the left edge of the app, and it broke the
 * app. Compose's hit test stops at the topmost thing under the finger: a node that is
 * hit takes the gesture out of reach of everything behind it, whether or not it ever
 * consumes anything. So forty dp of the left edge — which is most of the menu button —
 * stopped responding to taps entirely, and the only clue was that swiping still
 * worked.
 *
 * As a modifier on the page it is an *ancestor* of the buttons rather than a sibling
 * drawn above them, and ancestors get pointer events last. A tap reaches the button
 * underneath, produces no horizontal movement, and this never claims it. A drag from
 * the edge crosses touch slop, and by then nothing else wants it. The same arrangement
 * that lets a button live inside a scrollable list.
 *
 * **The edge belongs to Android first.** On gesture navigation the outermost band of
 * both edges is the system's back gesture and no app sees those touches, so [EDGE_GRAB]
 * is deliberately wider than that band: a swipe starting right at the edge goes back,
 * one starting a thumb's width inside opens the panel.
 */
fun Modifier.panelEdgeGrab(swipe: PanelSwipe): Modifier =
    if (!swipe.edgeGrabEnabled) {
        this
    } else {
        this.pointerInput(swipe) {
            val edge = EDGE_GRAB.toPx()

            awaitEachGesture {
                // Not `requireUnconsumed`: nothing has had a chance to consume yet on
                // the pass this runs in, and waiting for an unconsumed down would miss
                // the gesture entirely once a child has claimed the press.
                val down = awaitFirstDown(requireUnconsumed = false)

                val fromEdge = when (swipe.side) {
                    PanelSide.LEFT -> down.position.x <= edge
                    PanelSide.RIGHT -> size.width - down.position.x <= edge
                }
                // Returning here ends this gesture without touching it, which is the
                // whole point: a tap anywhere, including inside the band, is somebody
                // else's.
                if (!fromEdge) return@awaitEachGesture

                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)

                val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    swipe.dragBy(overSlop)
                    change.consume()
                } ?: return@awaitEachGesture

                horizontalDrag(slop.id) { change ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    swipe.dragBy(change.positionChange().x)
                    change.consume()
                }

                swipe.settle(tracker.calculateVelocity().x)
            }
        }
    }

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
 * box's edges and draws its own scrim over everything already in it. Unlike the edge
 * grab, these two *are* meant to be overlays: while a panel is open the page behind it
 * is not supposed to be touchable.
 */
@Composable
fun BoxScope.SlidingPanel(
    swipe: PanelSwipe,
    modifier: Modifier = Modifier,
    /** Dark enough to push the page back, light enough that it is plainly still there. */
    scrimAlpha: Float = 0.55f,
    content: @Composable () -> Unit,
) {
    // Read as a boolean rather than as the raw fraction: this decides whether the
    // panel exists at all, and recomposing the whole thing on every frame of a drag
    // to answer a question whose answer changes twice is how a smooth gesture stops
    // being smooth. The moving parts read the fraction in draw, not in composition.
    val showing by remember(swipe) { derivedStateOf { swipe.progress.value > 0f } }

    if (showing) {
        val dragState = rememberDraggableState { delta -> swipe.dragBy(delta) }

        // Two of the same gesture, differing in one flag, and the difference matters.
        //
        // `startDragImmediately` claims the finger the moment it lands instead of
        // waiting for it to travel — which is what lets a panel still gliding into
        // place be caught and dragged back. It also cancels whatever was under that
        // finger, so on the panel itself it would eat the tap that was meant for a
        // conversation in the list. The scrim has nothing under it to eat.
        val scrimDrag = Modifier.draggable(
            state = dragState,
            orientation = Orientation.Horizontal,
            startDragImmediately = swipe.progress.isRunning,
            onDragStopped = { velocity -> swipe.settle(velocity) },
        )

        val panelDrag = Modifier.draggable(
            state = dragState,
            orientation = Orientation.Horizontal,
            onDragStopped = { velocity -> swipe.settle(velocity) },
        )

        Box(
            Modifier
                .fillMaxSize()
                // Drawn, not backgrounded: the alpha is read in the draw phase, so a
                // drag darkens the page without recomposing anything behind it.
                .drawBehind { drawRect(Color.Black, alpha = scrimAlpha * swipe.progress.value) }
                // The gesture people already expect from every drawer on the phone.
                .pointerInput(swipe) { detectTapGestures { swipe.close() } }
                .then(scrimDrag)
        )

        Box(
            modifier
                .align(
                    if (swipe.side == PanelSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd
                )
                .width(swipe.panelWidth)
                .fillMaxHeight()
                .graphicsLayer {
                    translationX = -swipe.towardsOpen * (1f - swipe.progress.value) * swipe.widthPx
                }
                .then(panelDrag)
        ) {
            content()
        }
    }
}

/**
 * How wide the band is that starts the gesture.
 *
 * Wider than a drawer handle needs to be, because the outer part of it is not ours:
 * see the note on [panelEdgeGrab] about the system back gesture. Forty leaves a usable
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
