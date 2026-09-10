package dev.anodex.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch

/**
 * The panel almost everything in this app sits on.
 *
 * There were eight of these, written out longhand, in seven files — and they had
 * drifted. Most used [Radii.xl]; the host screen used [Radii.lg]. Most clipped before
 * they filled; one filled with a shape and never clipped at all, which is invisible
 * until something inside it is tappable and the press wash squares off the corners.
 * A card is the most repeated shape in the app and it is worth exactly one definition.
 *
 * Two dimensions, because those are the two the app actually uses:
 *
 * - **[fill]** — whether the card is a surface or just an outline. Filled means "this
 *   exists": a conversation, a task, a run. Unfilled means "this is offered": the
 *   scheduler's suggestions are drawn this way so that an offer and a live task can
 *   never be mistaken for each other.
 * - **[edge]** — an optional border colour, which in this app always *means* something
 *   rather than decorating. An agent run waiting on an approval wears an accent edge;
 *   a card that wants no attention wears none. A border on every card is a border that
 *   says nothing.
 */
@Composable
fun AnodexCard(
    modifier: Modifier = Modifier,
    fill: Boolean = true,
    edge: Color? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    enabled: Boolean = true,
    padding: PaddingValues = PaddingValues(Spacing.x4),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(Spacing.x2),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AnodexTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            // Clip first, always. Everything after it — the fill, the border, and in
            // particular the press wash a `clickable` draws — is a child of the
            // rounded layer and gets the corners for free.
            .clip(Radii.xl)
            .then(if (fill) Modifier.background(colors.bgSurface) else Modifier)
            .then(if (edge != null) Modifier.border(1.dp, edge, Radii.xl) else Modifier)
            .then(
                if (onClick != null) {
                    Modifier
                        // A card you can open is a control, and a control has a floor.
                        .heightIn(min = Touch.minTarget)
                        .clickable(enabled = enabled, onClickLabel = onClickLabel, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(padding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/**
 * A hairline. The only rule the app draws, and it draws a lot of them.
 *
 * Written out as a one-dp `Box` with a background in nine places. `Divider` from
 * Material is not used for the usual reason: it comes with its own inset behaviour
 * and its own colour role, and neither is the one this app wants.
 */
@Composable
fun Hairline(
    modifier: Modifier = Modifier,
    color: Color = AnodexTheme.colors.border,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color)
    )
}
