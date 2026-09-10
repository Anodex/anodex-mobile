package dev.anodex.mobile.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.chat.DiffLine
import dev.anodex.mobile.chat.FileDiff
import dev.anodex.mobile.chat.ToolApproval
import dev.anodex.mobile.chat.diffLines
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.LocalReducedMotion
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing

/**
 * The card that asks whether a tool may run.
 *
 * Ported from the desktop's `ToolConfirmCard`, including the 2px pulsing left edge
 * — a run is *stopped* until this is answered, and the card has to read as
 * something waiting rather than something reporting.
 *
 * Three deliberate choices:
 *
 * **Deny is the wider, calmer target.** A mis-tap on a phone should fall on the
 * reversible side. Approving runs a command on the user's computer; denying costs
 * a retry.
 *
 * **A countdown, and it says what happens at zero.** The prompt auto-denies after
 * five minutes so a phone that loses signal cannot wedge a generation forever. A
 * timer with no explanation reads as pressure; one that says "declined" reads as
 * a safety net.
 *
 * **It admits what it is not showing.** A request carrying a diff or an email
 * draft is summarised here, and the card says the detail is on the computer
 * rather than implying the summary is the whole story.
 */
@Composable
fun ToolApprovalCard(
    approval: ToolApproval,
    secondsRemaining: Int,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    val accent = when (approval.risk) {
        ToolApproval.Risk.DESTRUCTIVE -> colors.danger
        ToolApproval.Risk.SENSITIVE -> colors.warn
        ToolApproval.Risk.SAFE -> colors.accent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.lg)
            .background(colors.bgSurface),
    ) {
        PulsingEdge(accent)

        Column(
            modifier = Modifier.padding(Spacing.x4),
            verticalArrangement = Arrangement.spacedBy(Spacing.x3),
        ) {
            Text(
                text = if (approval.turnGate) "Start this turn?" else "Allow this?",
                style = type.meta,
                color = colors.textFaint,
            )

            Text(approval.title, style = type.bodyEmphasis, color = colors.text)

            approval.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = detail,
                    style = type.mono,
                    color = colors.textMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(Radii.sm)
                        .background(colors.bgSurface2)
                        .padding(Spacing.x3),
                )
            }

            // The change itself, when this is a file write. Approving an edit you
            // have not seen is the one thing this card should never ask for, and it
            // asked for it every time until now.
            approval.diff?.let { diff -> DiffView(diff) }

            if (approval.hasUnshownDetail) {
                Text(
                    text = "There's more to this than fits here — the full change is on your " +
                        "computer. Approve only if you already know what it does.",
                    style = type.meta,
                    color = colors.warn,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Deny first and wider: a mis-tap should land on the reversible side.
                Box(Modifier.weight(1.4f)) {
                    SecondaryButton(label = "Deny", onClick = onDeny, modifier = Modifier.fillMaxWidth())
                }
                Box(Modifier.weight(1f)) {
                    PrimaryButton(label = "Allow", onClick = onApprove, modifier = Modifier.fillMaxWidth())
                }
            }

            Text(
                text = if (secondsRemaining > 0) {
                    "Declined automatically in ${formatCountdown(secondsRemaining)} · " +
                        "or answer on the computer"
                } else {
                    "Waiting…"
                },
                style = type.meta,
                color = colors.textFaint,
            )
        }
    }
}

/**
 * The 2px edge that marks a card as waiting rather than reporting.
 *
 * Ported from the desktop. This is one of the few places an ambient loop is
 * right: the state it represents genuinely persists, and stopping the pulse would
 * make a blocked run look settled.
 */
@Composable
private fun PulsingEdge(color: Color) {
    val reducedMotion = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "approvalEdge")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "approvalEdgeAlpha",
    )

    Box(
        Modifier
            .width(2.dp)
            .fillMaxHeight()
            .background(if (reducedMotion) color else color.copy(alpha = alpha)),
    )
}

private fun formatCountdown(seconds: Int): String {
    val minutes = seconds / 60
    val rest = seconds % 60
    return if (minutes > 0) "%d:%02d".format(minutes, rest) else "${rest}s"
}

/**
 * The edit, as lines.
 *
 * Bounded on screen as well as in the algorithm: past a certain length nobody is
 * reading a diff on a phone, they are scrolling past it to reach the buttons — and
 * a card that pushes Deny off the bottom is a worse outcome than a truncated diff.
 * What is cut is said out loud.
 */
@Composable
private fun DiffView(diff: FileDiff) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    // Keyed on the file, not recomputed on every recomposition: this is quadratic
    // and the card redraws once a second while its countdown ticks.
    val lines = remember(diff.path, diff.before, diff.after) { diffLines(diff.before, diff.after) }
    val shown = lines.take(MAX_LINES_ON_SCREEN)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.sm)
            .background(colors.bgSurface2)
            .padding(Spacing.x2),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = diff.path,
            style = type.meta,
            color = colors.textFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = Spacing.x1),
        )

        for (line in shown) {
            Text(
                text = when (line.kind) {
                    DiffLine.Kind.ADDED -> "+ ${line.text}"
                    DiffLine.Kind.REMOVED -> "− ${line.text}"
                    DiffLine.Kind.KEPT -> "  ${line.text}"
                },
                style = type.mono,
                color = when (line.kind) {
                    DiffLine.Kind.ADDED -> colors.accentGreen
                    DiffLine.Kind.REMOVED -> colors.danger
                    DiffLine.Kind.KEPT -> colors.textFaint
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (lines.size > shown.size) {
            Text(
                text = "${lines.size - shown.size} more lines — read it at the computer.",
                style = type.meta,
                color = colors.warn,
                modifier = Modifier.padding(top = Spacing.x1),
            )
        }
    }
}

/**
 * Forty lines.
 *
 * Enough to see what a normal edit does, and short enough that Deny stays on
 * screen. A card whose buttons are below the fold is a card that gets approved by
 * whoever was scrolling.
 */
private const val MAX_LINES_ON_SCREEN = 40

@Preview(name = "Approval - sensitive", showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun PreviewApproval() {
    AnodexTheme(darkTheme = true) {
        Box(Modifier.padding(Spacing.x4)) {
            ToolApprovalCard(
                approval = ToolApproval(
                    id = "1",
                    conversationId = "c",
                    toolName = "run_command",
                    title = "Run npm test in Anodex4",
                    detail = "npm test -- src/main/remote",
                    risk = ToolApproval.Risk.SENSITIVE,
                    turnGate = false,
                    hasUnshownDetail = false,
                ),
                secondsRemaining = 214,
                onApprove = {},
                onDeny = {},
            )
        }
    }
}

@Preview(name = "Approval - destructive with a diff", showBackground = true)
@Composable
private fun PreviewDestructive() {
    AnodexTheme(darkTheme = false) {
        Box(Modifier.padding(Spacing.x4)) {
            ToolApprovalCard(
                approval = ToolApproval(
                    id = "2",
                    conversationId = "c",
                    toolName = "edit_file",
                    title = "Rewrite RemoteBridge.ts",
                    detail = null,
                    risk = ToolApproval.Risk.DESTRUCTIVE,
                    turnGate = true,
                    hasUnshownDetail = true,
                ),
                secondsRemaining = 41,
                onApprove = {},
                onDeny = {},
            )
        }
    }
}
