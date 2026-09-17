package dev.anodex.mobile.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Brand
import dev.anodex.mobile.ui.theme.LocalReducedMotion
import dev.anodex.mobile.ui.theme.Motion
import dev.anodex.mobile.ui.theme.Radii

/**
 * The app's switch.
 *
 * Not Material's, for the reason [PrimaryButton] is not Material's: the one in
 * Settings was `androidx.compose.material3.Switch` with its colours overridden,
 * which meant Material's track proportions, Material's thumb, Material's spring
 * — and a flat accent fill where the desktop's switch has carried the mark for
 * some time. It was the last borrowed control on the phone, and the flattest
 * thing on the screen it lives on.
 *
 * Drawn to the desktop's construction at the desktop's size, because a switch is
 * small enough that 46x26 reads the same on both and the touch target is the row
 * around it, not the switch itself:
 *
 *  - off is a recessed pill — the elevated surface, a strong border, and a
 *    shadow pressed into the top edge, so it reads as a slot the knob sits in;
 *  - on is the mark's diagonal with the same lift and bevel every branded
 *    control wears, a soft ring holding it off the background, and no border;
 *  - the knob is cut by one hard 135-degree edge — the facet the mark is cut
 *    from — and casts a real shadow, so it sits on the track rather than in it.
 *
 * The knob overshoots slightly on its way across. That is the one piece of
 * characterful motion here and it is event-driven, which is what the house rule
 * in [Motion] reserves it for: a switch moves because a person moved it.
 */
@Composable
fun AnodexSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val colors = AnodexTheme.colors
    val reducedMotion = LocalReducedMotion.current

    val knobOffset by animateDpAsState(
        targetValue = if (checked) KNOB_TRAVEL else 0.dp,
        animationSpec =
            if (reducedMotion) {
                // The overshoot is the part that reads as motion, so it is the
                // part that goes. The switch still moves, because a switch that
                // does not move is not showing you anything.
                tween(durationMillis = Motion.FAST_MS, easing = Motion.standard)
            } else {
                spring(dampingRatio = 0.55f, stiffness = 900f)
            },
        label = "knob",
    )

    val trackBorder = if (checked) Color.Transparent else colors.borderStrong

    Box(
        modifier = modifier
            // The ring the desktop draws with a third shadow layer. Padding plus
            // a background on the outer box, so it grows outward from the track
            // rather than eating into it.
            .then(
                if (checked) {
                    Modifier.clip(Radii.pill).background(colors.accentSoft).padding(RING)
                } else {
                    Modifier.padding(RING)
                }
            )
    ) {
        Box(
            modifier = Modifier
                .size(width = TRACK_WIDTH, height = TRACK_HEIGHT)
                .clip(Radii.pill)
                .then(
                    if (checked) {
                        Modifier.background(Brand.gradient(colors)).background(Brand.sheen)
                    } else {
                        Modifier.background(colors.bgElevated).background(RECESS)
                    }
                )
                .border(1.dp, trackBorder, Radii.pill)
        ) {
            Box(
                modifier = Modifier
                    .padding(KNOB_INSET)
                    .offset(x = knobOffset)
                    .size(KNOB_SIZE)
                    .shadow(elevation = 2.dp, shape = CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(KNOB_FACET)
            )
        }
    }
}

private val TRACK_WIDTH = 46.dp
private val TRACK_HEIGHT = 26.dp
private val KNOB_SIZE = 20.dp
private val KNOB_INSET = 2.dp
private val KNOB_TRAVEL = TRACK_WIDTH - KNOB_SIZE - KNOB_INSET * 2
private val RING = 3.dp

/**
 * One plane, one edge: the facet the mark is cut from, the same hard 135-degree
 * cut as the desktop's knob and the send key's plane.
 */
private val KNOB_FACET = Brush.linearGradient(
    0.46f to Color.White,
    0.46f to Color(0xFFE7E9F5),
)

/** The shadow pressed into an off track's top edge, so it reads as a slot. */
private val RECESS = Brush.verticalGradient(
    0.00f to Color.Black.copy(alpha = 0.28f),
    0.22f to Color.Transparent,
)
