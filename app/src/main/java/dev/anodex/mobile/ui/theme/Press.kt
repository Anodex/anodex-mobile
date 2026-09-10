package dev.anodex.mobile.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * What a control does while a finger is on it.
 *
 * Every `Modifier.clickable` in this app used to wear Material's ripple, because
 * `MaterialTheme` puts one into `LocalIndication` and nothing here replaced it. That
 * is the single most visible borrowed thing left in the app: a ripple is a circle
 * that expands from the touch point in Material's own colour and on Material's own
 * timing, and it appeared on seventy-odd surfaces that are otherwise built from
 * Anodex tokens. Worse, it ignores a card's corner radius unless the caller happened
 * to clip first — so a rounded row would flash a square.
 *
 * The replacement is the flattest thing that still answers the finger: a wash of the
 * page's own text colour, the shape of whatever it is drawn into.
 *
 * **Instant in, gentle out.** The wash snaps to full the moment the press lands —
 * feedback that animates *in* reads as lag, and this is the one place in the app that
 * has to feel immediate. Letting go fades over [Motion.FAST_MS], because a press that
 * vanishes on the same frame as the lift is easy to miss on a screen you are also
 * moving your hand away from.
 *
 * This is state, not character, so it is deliberately exempt from the reduced-motion
 * rule in [Motion]: 120ms of alpha is how the control says it was pressed, and there
 * is a colour change underneath it either way.
 */
data class AnodexPress(private val tint: Color) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        PressNode(tint, interactionSource)

    private class PressNode(
        private val tint: Color,
        private val interactionSource: InteractionSource,
    ) : Modifier.Node(), DrawModifierNode {

        /**
         * How much of the wash is showing.
         *
         * Read in [draw], which puts it under snapshot observation: the draw phase
         * re-runs when it changes, so nothing here has to invalidate by hand.
         */
        private val strength = Animatable(0f)

        override fun onAttach() {
            coroutineScope.launch {
                // Counted rather than a boolean. A single finger can produce a Press
                // and then a Cancel that arrives after a second Press on a control
                // that was re-entered, and a boolean lets the stale Cancel switch off
                // a press that is still held.
                var held = 0
                var release: Job? = null

                interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> held++
                        is PressInteraction.Release -> held--
                        is PressInteraction.Cancel -> held--
                        else -> return@collect
                    }
                    held = held.coerceAtLeast(0)

                    // Cancelled either way: a fade still running when the next press
                    // lands would otherwise keep pulling the wash back towards zero
                    // underneath it.
                    release?.cancel()
                    release = null

                    if (held > 0) {
                        strength.snapTo(1f)
                    } else {
                        release = launch {
                            strength.animateTo(
                                targetValue = 0f,
                                animationSpec = tween(Motion.FAST_MS, easing = Motion.standard),
                            )
                        }
                    }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            val alpha = strength.value
            // The rectangle is the node's own bounds, which a `clip` earlier in the
            // chain has already rounded. Callers that draw a rounded surface must
            // clip before they make it clickable — the same order the buttons in
            // `Buttons.kt` have always used.
            if (alpha > 0f) drawRect(color = tint, alpha = alpha)
        }
    }
}
