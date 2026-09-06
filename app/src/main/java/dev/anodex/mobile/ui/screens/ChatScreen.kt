package dev.anodex.mobile.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.chat.ChatMessage
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.components.MarkdownText
import dev.anodex.mobile.ui.components.ToolRow
import dev.anodex.mobile.ui.theme.LocalReducedMotion
import dev.anodex.mobile.chat.ToolApproval
import dev.anodex.mobile.ui.components.ToolApprovalCard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.anodex.mobile.connection.ModelStatus
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch

/**
 * The conversation.
 *
 * Proportions are ported from the desktop's `MessageBubble.module.css` rather than
 * re-derived: a user turn is a right-aligned bubble capped at 78% of the width with
 * a sharper trailing corner pointing back at the sender, and an assistant turn is
 * flat and full width. That asymmetry is what carries the role without an avatar or
 * a label on either side.
 */
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    sending: Boolean,
    error: String?,
    onSend: (String) -> Unit,
    onStop: () -> Unit = {},
    modifier: Modifier = Modifier,
    approval: ToolApproval? = null,
    approvalSecondsRemaining: Int = 0,
    onApprove: () -> Unit = {},
    onDeny: () -> Unit = {},
    /**
     * The computer's loaded model, for the context meter above the composer.
     *
     * Null when nothing is loaded or the socket is down, and the meter simply is
     * not drawn — an empty bar would read as "no context used" rather than "not
     * known", which is the opposite of the truth.
     */
    model: ModelStatus? = null,
    /** Opens a file a tool touched. Null in previews and where there is no socket. */
    onOpenFile: ((String) -> Unit)? = null,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier = modifier.fillMaxSize().background(colors.bgApp).imePadding()) {
        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Ask your computer something.",
                    style = type.body,
                    color = colors.textFaint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(Spacing.x6),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = Spacing.x4),
                verticalArrangement = Arrangement.spacedBy(Spacing.x3),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    vertical = Spacing.x4
                ),
            ) {
                items(messages, key = { it.id }) { message -> MessageRow(message, onOpenFile) }
            }
        }

        // Above the composer, because it is the thing to answer before anything else
        // is worth typing - the run is stopped until it is.
        if (approval != null) {
            ToolApprovalCard(
                approval = approval,
                secondsRemaining = approvalSecondsRemaining,
                onApprove = onApprove,
                onDeny = onDeny,
                modifier = Modifier.padding(horizontal = Spacing.x4, vertical = Spacing.x2),
            )
        }

        if (error != null) {
            Text(
                text = error,
                style = type.meta,
                color = colors.danger,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.x4)
                    .clip(Radii.md)
                    .background(colors.dangerSoft)
                    .padding(Spacing.x3),
            )
        }

        Composer(
            draft = draft,
            sending = sending,
            onDraftChange = { draft = it },
            onSend = {
                onSend(draft)
                draft = ""
            },
            onStop = onStop,
            model = model,
        )
    }
}

@Composable
private fun MessageRow(message: ChatMessage, onOpenFile: ((String) -> Unit)? = null) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    val isUser = message.role == ChatMessage.Role.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (isUser) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.78f)
                    .clip(Radii.lg)
                    .background(colors.bgSurface2)
                    .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
            ) {
                Text(message.text, style = type.body, color = colors.text)
            }
        } else {
            // Assistant turns are unbubbled and full width, as on the desktop: the
            // reply is the page, not a card sitting on it.
            Column(Modifier.fillMaxWidth()) {
                // Tools first, in the order they ran — the reply is the conclusion,
                // and the work that produced it reads better above it than after.
                for (tool in message.tools) {
                    ToolRow(tool, onOpenFile = onOpenFile)
                }

                if (message.text.isNotEmpty()) {
                    // Rendered, not printed. Anodex is usually answering a question
                    // about code, so a reply is mostly fenced blocks and inline
                    // code — as plain text that is backticks and asterisks, with
                    // shell commands run together into a paragraph.
                    MarkdownText(message.text)
                } else if (message.streaming && message.tools.isEmpty()) {
                    // Nothing has arrived yet and nothing is being reported. Without
                    // this the screen is simply blank, which reads as the app having
                    // frozen rather than the model having started.
                    ThinkingLine()
                }
            }
        }
    }
}

/**
 * The gap between sending and the first token.
 *
 * A shimmer rather than a spinner: it is the same "something is happening, nothing
 * is wrong" signal the desktop uses for live activity, and it stops the moment a
 * token lands.
 */
@Composable
private fun ThinkingLine() {
    val colors = AnodexTheme.colors
    val reducedMotion = LocalReducedMotion.current

    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thinkingAlpha",
    )

    Text(
        text = "Thinking…",
        style = AnodexTheme.type.body,
        color = colors.textFaint.copy(alpha = if (reducedMotion) 1f else alpha),
    )
}

@Composable
private fun Composer(
    draft: String,
    sending: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    model: ModelStatus? = null,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bgSurface)
            .padding(horizontal = Spacing.x3, vertical = Spacing.x2),
        verticalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        // Directly above the box you type into, because that is where the decision
        // is made. A context window filling up is the reason a long conversation
        // starts forgetting its own beginning, and the desktop shows it in the
        // header where somebody about to type is not looking.
        val fraction = model?.contextFraction
        if (fraction != null) {
            ContextStrip(model, fraction)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(Radii.lg)
                    .background(colors.bgInput)
                    .padding(horizontal = Spacing.x3, vertical = Spacing.x3),
            ) {
                if (draft.isEmpty()) {
                    Text(
                        text = if (sending) "Interrupt\u2026" else "Ask Anodex\u2026",
                        style = type.body,
                        color = colors.textFaint,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    enabled = !sending,
                    textStyle = type.body.copy(color = colors.text),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // While a turn is running this becomes Stop. A generation on the phone is
            // a generation on the computer, and one that has gone wrong can burn a
            // long time before it ends on its own.
            //
            // Round and always present, rather than a word-labelled button that
            // appears and disappears: the old one reflowed the whole composer on the
            // first keystroke, which moved the text you were typing.
            SendButton(
                sending = sending,
                enabled = sending || draft.isNotBlank(),
                onClick = if (sending) onStop else onSend,
            )
        }
    }
}

/** Context used, as a number and a hairline bar. */
@Composable
private fun ContextStrip(model: ModelStatus, fraction: Float) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    // Amber past the point where the desktop starts summarising history away, so the
    // bar changes character before the model appears to forget something rather than
    // after.
    val tight = fraction > 0.85f

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        Text("Context", style = type.meta, color = colors.textFaint)

        Box(
            Modifier
                .weight(1f)
                .height(2.dp)
                .clip(Radii.pill)
                .background(colors.border),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(2.dp)
                    .clip(Radii.pill)
                    .background(if (tight) colors.warn else colors.accent),
            )
        }

        Text(
            text = "${compactTokens(model.contextUsedTokens)} / " +
                compactTokens(model.contextTotalTokens),
            style = type.meta,
            color = if (tight) colors.warn else colors.textFaint,
            maxLines = 1,
        )
    }
}

/**
 * The one round control in the app.
 *
 * A circle rather than the app's usual 6dp radius, and deliberately: it is the only
 * control that commits work to another machine, and it should not look like the
 * buttons that merely navigate.
 */
@Composable
private fun SendButton(sending: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = AnodexTheme.colors

    val background = when {
        sending -> colors.dangerSoft
        enabled -> colors.accent
        // Present but plainly inert, rather than absent. A control that vanishes
        // when the field is empty takes the layout with it.
        else -> colors.bgSurface2
    }
    val foreground = when {
        sending -> colors.danger
        enabled -> colors.textOnAccent
        else -> colors.textFaint
    }

    Box(
        modifier = Modifier
            .size(Touch.minTarget)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = if (sending) "Stop" else "Send" },
        contentAlignment = Alignment.Center,
    ) {
        // The desktop's own two glyphs, not lookalikes drawn here. Send is a paper
        // plane in `Icon.tsx`; this drew an arrow instead, with a comment claiming it
        // was the desktop's — which it never was, and nothing catches a wrong comment
        // next to a wrong drawing.
        AnodexIcon(
            icon = if (sending) AnodexIcon.STOP else AnodexIcon.SEND,
            size = if (sending) 15.dp else 18.dp,
            tint = foreground,
            contentDescription = null,
        )
    }
}

/** Thousands abbreviated: the exact figure changes several times a second mid-turn. */
private fun compactTokens(tokens: Int): String =
    if (tokens < 1_000) tokens.toString() else "${"%.1f".format(tokens / 1000f)}K"


@Preview(name = "Chat - dark", showBackground = true, heightDp = 700)
@Composable
private fun PreviewChat() {
    AnodexTheme(darkTheme = true) {
        ChatScreen(
            messages = listOf(
                ChatMessage("1", ChatMessage.Role.USER, "What's failing in the scheduler tests?"),
                ChatMessage(
                    "2",
                    ChatMessage.Role.ASSISTANT,
                    "Two of them. Both come from the monthly recurrence case rolling over a " +
                        "31st into a month that has no 31st.",
                ),
            ),
            sending = false,
            error = null,
            onSend = {},
        )
    }
}

@Preview(name = "Chat - light", showBackground = true, heightDp = 700)
@Composable
private fun PreviewChatLight() {
    AnodexTheme(darkTheme = false) {
        ChatScreen(
            messages = listOf(
                ChatMessage("1", ChatMessage.Role.USER, "Run the tests."),
                ChatMessage("2", ChatMessage.Role.ASSISTANT, "", streaming = true),
            ),
            sending = true,
            error = null,
            onSend = {},
        )
    }
}
