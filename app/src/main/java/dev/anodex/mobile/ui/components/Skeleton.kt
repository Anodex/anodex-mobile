package dev.anodex.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing

/**
 * The shape of the answer, drawn while the answer is on its way.
 *
 * Every list screen used to spend the wait on a blank page with one centred sentence
 * on it, and then jump — in a single frame — to a full list. Two bad things at once:
 * the screen looks broken while it waits, and the arrival is a jolt rather than a
 * change. Laying out the shape of what is coming fixes both. The rows land where
 * they were already promised.
 *
 * **Nothing here moves.** A shimmer is the usual way to draw one of these, and it is
 * exactly what `Motion.kt` forbids: an ambient loop, running for as long as the wait
 * lasts, which on a slow computer is a long time to have something sliding across the
 * screen. The rule in this app is that character-motion is for rare event-driven
 * moments, so these are flat blocks and the waiting is said in words — [caption],
 * which is also what satisfies the other house rule, that a waiting state is never
 * communicated by motion alone.
 *
 * Being static has a second benefit worth naming: it is honest about not knowing. A
 * shimmer implies progress. A resting outline implies a request that has been made
 * and not yet answered, which is the truth.
 */
@Composable
fun ListSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 4,
    lines: Int = 2,
    caption: String? = null,
) {
    val colors = AnodexTheme.colors

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        if (caption != null) {
            Text(
                text = caption,
                style = AnodexTheme.type.meta,
                color = colors.textFaint,
                modifier = Modifier.padding(horizontal = Spacing.x1),
            )
        }

        for (row in 0 until rows) {
            AnodexCard(verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                for (line in 0 until lines) {
                    SkeletonBar(
                        widthFraction = barWidth(row, line, lines),
                        // The first line of a row is its title and the rest is detail,
                        // which is how every card in this app is actually built.
                        height = if (line == 0) 14.dp else 10.dp,
                    )
                }
            }
        }
    }
}

/** One resting block where a line of text will be. */
@Composable
fun SkeletonBar(
    widthFraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
) {
    Box(
        modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(Radii.sm)
            // A step up from the card it sits on rather than a grey of its own, so it
            // stays a surface relationship and holds in both themes: the light palette
            // is warm, and any neutral placeholder would read as a stain on it.
            .background(AnodexTheme.colors.bgElevated)
    )
}

/**
 * How wide each resting line is.
 *
 * Ragged on purpose, and deterministic on purpose. Equal-length bars read as a table
 * rather than as prose, and random ones would redraw at a different width on every
 * recomposition — which is motion, arriving through the back door, in the one
 * composable that has just finished explaining why it does not move.
 */
private fun barWidth(row: Int, line: Int, lines: Int): Float {
    if (line == 0) {
        // Titles: long, but not all the same long.
        return listOf(0.72f, 0.55f, 0.84f, 0.63f)[row % 4]
    }
    // Detail lines taper, and the last one in a row is the shortest — that is what a
    // wrapped paragraph does.
    val taper = listOf(0.95f, 0.88f, 0.70f)[line % 3]
    return if (line == lines - 1) taper * 0.6f else taper
}
