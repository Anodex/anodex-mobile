package dev.anodex.mobile.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.chat.ToolActivity
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.LocalReducedMotion
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.workspace.filePathIn

/**
 * One tool call, collapsed to a line.
 *
 * Straight from the design sample, and the reasoning there is right: on a phone the
 * fact that a file was read matters, its 318 lines do not. The desktop shows a full
 * card with the output; this shows that it happened, what it touched, and whether it
 * worked.
 *
 * Without these the phone showed *nothing at all* during a turn that used tools — a
 * blank screen for however long the work took, which reads as the app having frozen
 * rather than the model doing exactly what was asked.
 *
 * Three columns, in the sample's order: the tool's name, what it acted on, and the
 * result. The middle one is the tool's `title` — `Read src/sim/OrbitPanel.tsx` — and
 * not its `detail`, which is the *result* preview (`318 lines`). Showing detail in
 * the middle, as this first did, meant the file being worked on never appeared
 * anywhere: the row said a file had been read and would not say which.
 */
@Composable
fun ToolRow(
    activity: ToolActivity,
    modifier: Modifier = Modifier,
    /** Opens the file this call touched. Null where there is nowhere to open it. */
    onOpenFile: ((String) -> Unit)? = null,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    val path = if (onOpenFile == null) null else filePathIn(activity.title)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (path != null) {
                    // Only rows that lead somewhere are tappable. A row that responds
                    // to a tap by doing nothing teaches the user to stop tapping.
                    Modifier
                        .heightIn(min = 40.dp)
                        .clickable { onOpenFile?.invoke(path) }
                } else {
                    Modifier
                }
            )
            .padding(vertical = Spacing.x1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        StatusMark(activity.status)

        Text(
            text = activity.name,
            style = type.mono,
            color = colors.textMuted,
            maxLines = 1,
        )

        Text(
            text = activity.title,
            style = type.meta,
            // A path that can be opened is marked as such by colour alone. An icon
            // per row would put a column of chevrons down a transcript that is
            // mostly prose.
            color = if (path != null) colors.accentInk else colors.textFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        // The result, last and smallest: "318 lines" is the least important thing on
        // the row and the first thing that should be dropped when space runs out.
        val detail = activity.detail
        if (detail != null) {
            Text(text = detail, style = type.meta, color = colors.textFaint, maxLines = 1)
        }
    }
}

/**
 * A dot rather than a tick or a spinner.
 *
 * A spinner per row would put several competing animations on one screen; the
 * running dot pulses instead, and only while something is actually running —
 * which is the house rule about motion conveying state rather than decorating it.
 */
@Composable
private fun StatusMark(status: ToolActivity.Status) {
    val colors = AnodexTheme.colors
    val reducedMotion = LocalReducedMotion.current

    val colour = when (status) {
        ToolActivity.Status.RUNNING -> colors.accentInk
        ToolActivity.Status.DONE -> colors.successInk
        ToolActivity.Status.FAILED -> colors.dangerInk
    }

    val pulse = rememberInfiniteTransition(label = "toolPulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
        label = "toolPulseAlpha",
    )

    val running = status == ToolActivity.Status.RUNNING && !reducedMotion

    Box(
        Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(if (running) colour.copy(alpha = alpha) else colour),
    )
}

@Preview(name = "Tool rows", showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun PreviewToolRows() {
    AnodexTheme(darkTheme = true) {
        androidx.compose.foundation.layout.Column(Modifier.padding(Spacing.x4)) {
            ToolRow(
                ToolActivity("1", "search_code", "Search \"orbit drag\"", "7 hits", ToolActivity.Status.DONE),
                onOpenFile = {},
            )
            ToolRow(
                ToolActivity(
                    "2",
                    "read_file",
                    "Read src/sim/OrbitPanel.tsx",
                    "318 lines",
                    ToolActivity.Status.DONE,
                ),
                onOpenFile = {},
            )
            ToolRow(
                ToolActivity(
                    "3",
                    "patch_file",
                    "Edit src/sim/useDragBody.ts",
                    null,
                    ToolActivity.Status.RUNNING,
                ),
                onOpenFile = {},
            )
        }
    }
}
