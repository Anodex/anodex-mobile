package dev.anodex.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch
import kotlinx.coroutines.delay

/**
 * What just happened, and the few seconds in which it can be undone.
 *
 * This is what lets archiving ask nothing before it happens. A confirmation dialog
 * puts the question in front of every tap, including the several hundred that were
 * not mistakes, and people learn to dismiss it without reading — at which point it
 * has stopped protecting anything and is only costing a tap. An undo asks after the
 * fact, of the one person in a hundred who needs it, and is read because by then
 * they are looking for it.
 *
 * The rule that makes that trade honest is that the action must be genuinely
 * reversible. Archiving is: the computer keeps the record and `conversations:restore`
 * puts it back. Anything that is not stays with [ConfirmDialog].
 */
@Composable
fun UndoBar(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null when there is nothing to undo — a failure has only news to deliver. */
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    // Keyed on the text so a second archive restarts the clock rather than
    // inheriting whatever was left of the first one's.
    LaunchedEffect(text) {
        delay(VISIBLE_MS)
        onDismiss()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.x4, vertical = Spacing.x5)
            .border(1.dp, colors.border, Radii.lg)
            .background(colors.bgElevated, Radii.lg)
            .padding(start = Spacing.x4, end = Spacing.x2, top = Spacing.x2, bottom = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        Text(
            text = text,
            style = type.meta,
            color = colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (actionLabel != null) {
            Text(
                text = actionLabel,
                style = type.label,
                color = colors.accent,
                modifier = Modifier
                    .heightIn(min = Touch.minTarget)
                    .clip(Radii.md)
                    .clickable(onClick = onAction)
                    .padding(horizontal = Spacing.x3, vertical = Spacing.x3),
            )
        }
    }
}

/**
 * Six seconds.
 *
 * Long enough to read a sentence, notice it names the wrong conversation, and reach
 * the button. Short enough that it is gone before it becomes furniture.
 */
private const val VISIBLE_MS = 6_000L
