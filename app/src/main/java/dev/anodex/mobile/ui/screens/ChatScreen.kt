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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import dev.anodex.mobile.chat.ChatMessage
import dev.anodex.mobile.chat.toolSummary
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.components.AnodexMark
import dev.anodex.mobile.ui.components.FacetField
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
    /** "STUDIO-PC is awake and listening", under the greeting on an empty chat. */
    hostLine: String? = null,
    /**
     * Who is answering. "Anodex" when no character is selected — the default voice
     * speaks as itself, which is what the desktop shows too.
     */
    personaName: String = "Anodex",
    /** Ask the same question again. Null where there is no socket to ask down. */
    onRetryMessage: ((String) -> Unit)? = null,
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
                // Not a void. Two flat planes in the mark's own violet and cyan, then
                // the mark, then a greeting that names the *computer* — because that
                // is the thing you came back to, and it is the one fact none of the
                // other assistants can put on their home screen.
                FacetField(Modifier.matchParentSize())

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.x4),
                    modifier = Modifier.padding(Spacing.x6),
                ) {
                    AnodexMark(size = 54.dp)
                    Text(
                        text = greeting(),
                        style = type.title,
                        color = colors.text,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = hostLine ?: "Ask your computer something.",
                        style = type.meta,
                        color = colors.textFaint,
                        textAlign = TextAlign.Center,
                    )
                }
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
                items(messages, key = { it.id }) { message ->
                    MessageRow(
                        message = message,
                        onOpenFile = onOpenFile,
                        personaName = personaName,
                        onRetry = onRetryMessage,
                        // Nothing to copy or retry while the answer is still
                        // arriving, and a retry mid-turn would be refused anyway.
                        actionsEnabled = !sending,
                    )
                }
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
private fun MessageRow(
    message: ChatMessage,
    onOpenFile: ((String) -> Unit)? = null,
    personaName: String = "Anodex",
    onRetry: ((String) -> Unit)? = null,
    actionsEnabled: Boolean = true,
) {
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
                Text(message.text, style = type.chatBody, color = colors.text)
            }
        } else {
            // Assistant turns are unbubbled and full width, as on the desktop: the
            // reply is the page, not a card sitting on it.
            Column(Modifier.fillMaxWidth()) {
                // Who is answering, above the answer. The personality is a real
                // setting that changes the voice, and a voice that changes with
                // nothing on screen to say so reads as the model being erratic.
                Text(
                    text = personaName,
                    style = type.label,
                    color = colors.textMuted,
                    modifier = Modifier.padding(bottom = Spacing.x1),
                )

                // Collapsed to one line once there is more than one, because a turn
                // that ran twenty tools buries the reply that was the point. Tapping
                // gives every row back — the summary is a door, not a redaction.
                val summary = remember(message.tools) { toolSummary(message.tools) }
                var toolsExpanded by rememberSaveable(message.id) { mutableStateOf(false) }

                if (summary != null && !toolsExpanded) {
                    ActivityLine(summary, expanded = false) { toolsExpanded = true }
                } else {
                    if (summary != null) {
                        ActivityLine(summary, expanded = true) { toolsExpanded = false }
                    }
                    for (tool in message.tools) {
                        ToolRow(tool, onOpenFile = onOpenFile)
                    }
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

                if (!message.streaming && message.text.isNotEmpty()) {
                    MessageActions(
                        text = message.text,
                        enabled = actionsEnabled,
                        onRetry = onRetry?.let { retry -> { retry(message.id) } },
                    )
                }
            }
        }
    }
}

/**
 * Copy, and ask again.
 *
 * Copy is not a convenience here the way it is on a desktop. Compose `Text` is not
 * selectable, so without this there is no way at all to get a reply off the phone —
 * not the code in it, not a command, not one line of it.
 *
 * Quiet until wanted: no labels, no filled buttons, and nothing under a message
 * still being written.
 */
@Composable
private fun MessageActions(
    text: String,
    enabled: Boolean,
    onRetry: (() -> Unit)?,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    val clipboard = LocalClipboardManager.current

    // Reverts on its own. A tick that stays forever stops meaning "just now" and
    // starts meaning "this message is special", which is not a thing.
    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_600)
            copied = false
        }
    }

    Row(
        modifier = Modifier.padding(top = Spacing.x1),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionButton(
            label = if (copied) "Copied" else "Copy",
            tint = if (copied) colors.success else colors.textFaint,
            enabled = enabled,
        ) {
            clipboard.setText(AnnotatedString(text))
            copied = true
        }

        if (onRetry != null) {
            ActionButton(label = "Retry", tint = colors.textFaint, enabled = enabled, onClick = onRetry)
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = AnodexTheme.type.meta,
        color = if (enabled) tint else AnodexTheme.colors.textFaint.copy(alpha = 0.4f),
        modifier = Modifier
            .clip(Radii.md)
            .clickable(enabled = enabled, onClick = onClick)
            // Padded rather than sized: these sit under every reply, and a pair of
            // 48dp targets between each one would push the conversation apart.
            .padding(horizontal = Spacing.x2, vertical = Spacing.x2),
    )
}

/**
 * "Good afternoon" — by the clock, and nothing else.
 *
 * No name. Every other assistant greets you by yours, and it is the one thing this
 * app cannot know: there is no Anodex account, and the desktop never asked. Guessing
 * from the Android profile would be a stranger using your first name.
 */
private fun greeting(): String = when (LocalTime.now().hour) {
    in 0..4 -> "Still up"
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

/**
 * What the turn did, folded into a line.
 *
 * Deliberately quiet — muted text, no card, no icon. It sits between a question and
 * its answer, and anything louder competes with both.
 */
@Composable
private fun ActivityLine(summary: String, expanded: Boolean, onToggle: () -> Unit) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clickable(onClick = onToggle)
            .padding(vertical = Spacing.x1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        Text(summary, style = type.meta, color = colors.textMuted)
        Text(if (expanded) "\u2304" else "\u203a", style = type.meta, color = colors.textFaint)
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
        style = AnodexTheme.type.chatBody,
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
                        // "Queue", not "Interrupt". Mid-turn you are usually writing the
                        // next instruction rather than trying to stop it, and the stop
                        // button is right there for when you are.
                        text = if (sending) "Queue a message\u2026" else "Ask Anodex\u2026",
                        style = type.chatBody,
                        color = colors.textFaint,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    enabled = !sending,
                    textStyle = type.chatBody.copy(color = colors.text),
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
