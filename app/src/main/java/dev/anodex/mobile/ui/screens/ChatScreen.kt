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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
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
import dev.anodex.mobile.chat.UploadState
import dev.anodex.mobile.chat.toolSummary
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.components.AnodexMark
import dev.anodex.mobile.ui.components.FacetField
import dev.anodex.mobile.ui.components.MarkdownText
import dev.anodex.mobile.ui.components.PersonalityAvatar
import dev.anodex.mobile.ui.components.ToolRow
import dev.anodex.mobile.ui.theme.LocalReducedMotion
import dev.anodex.mobile.chat.ToolApproval
import dev.anodex.mobile.ui.components.ToolApprovalCard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    /** Opens a file a tool touched. Null in previews and where there is no socket. */
    onOpenFile: ((String) -> Unit)? = null,
    /** "STUDIO-PC is awake and listening", under the greeting on an empty chat. */
    hostLine: String? = null,
    /** Ask the same question again. Null where there is no socket to ask down. */
    onRetryMessage: ((String) -> Unit)? = null,
    /** What is attached to the message being written, and how it is getting on. */
    pendingAttachments: List<UploadState> = emptyList(),
    /** Null where attaching is not possible — previews, and no socket. */
    onAttach: (() -> Unit)? = null,
    onRemoveAttachment: (UploadState) -> Unit = {},
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    /**
     * A message written while the last one was still being answered.
     *
     * Mid-turn is exactly when the next instruction gets written — you already know
     * what you want next while the answer to the last thing is still arriving. So
     * what is typed during a turn is neither refused nor silently held: it waits, it
     * says so, and it goes out on its own the moment the turn ends.
     *
     * The draft *is* the queue rather than a second piece of state, which is why
     * there is no way to end up with a queued message and a draft disagreeing about
     * what is on the point of being sent.
     */
    LaunchedEffect(sending) {
        if (!sending && draft.isNotBlank()) {
            onSend(draft)
            draft = ""
        }
    }

    /**
     * Whether the view is close enough to the end to keep following the stream.
     *
     * Read from live geometry rather than remembered, because the transcript shrinks
     * whenever the composer grows a line — a change that moves the bottom without
     * ever firing a scroll event.
     */
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset + STICK_SLOP_PX
        }
    }

    // Follows the stream only while the reader is already at the end. Yanking the
    // view down while somebody is reading further up is the rudest thing a chat
    // screen can do, and it is what an unconditional scroll does.
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty() && atBottom) listState.animateScrollToItem(messages.lastIndex)
    }

    /**
     * The newest thing asked, shown when it has scrolled off the top.
     *
     * The *newest*, not the nearest one above — the same rule the desktop follows.
     * Anchoring to whichever request happens to be overhead would leave an old
     * prompt pinned after a follow-up was sent lower down, which is the opposite of
     * "what am I waiting on".
     */
    val currentRequest = remember(messages) {
        messages.lastOrNull { it.role == ChatMessage.Role.USER }
    }

    /**
     * Held through `rememberUpdatedState`, which is the whole fix.
     *
     * `remember { derivedStateOf { ... } }` with no keys captures the values around it
     * once and never sees another — so this was comparing the *first* question's id
     * against the *first* message list for the rest of the conversation. It worked on
     * a short exchange, where the first question is still the newest one, and stopped
     * the moment a second turn arrived. Which is exactly when a pinned question starts
     * being worth having.
     */
    val pinnedId by rememberUpdatedState(currentRequest?.id)
    val requestPinned by remember {
        derivedStateOf {
            val id = pinnedId ?: return@derivedStateOf false
            // Simply "not on screen". Comparing keys rather than indices, because an
            // index is a statement about a list that grows underneath this check,
            // while a key is a statement about the message.
            listState.layoutInfo.visibleItemsInfo.none { it.key == id }
        }
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
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.x4),
                    verticalArrangement = Arrangement.spacedBy(Spacing.x3),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        vertical = Spacing.x4
                    ),
                ) {
                    itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                        // A seam between exchanges, not between messages: it opens
                        // where a new question starts, so one turn reads as one thing.
                        // Faded at both ends so it separates rather than ruling a line
                        // across the conversation.
                        if (index > 0 && message.role == ChatMessage.Role.USER) TurnSeam()

                        MessageRow(
                            message = message,
                            onOpenFile = onOpenFile,
                            onRetry = onRetryMessage,
                            // Nothing to copy or retry while the answer is still
                            // arriving, and a retry mid-turn would be refused anyway.
                            actionsEnabled = !sending,
                        )
                    }
                }

                if (requestPinned && currentRequest != null) {
                    CurrentRequestBar(
                        text = currentRequest.text,
                        onTap = {
                            scope.launch {
                                val index = messages.indexOfFirst { it.id == currentRequest.id }
                                if (index >= 0) listState.animateScrollToItem(index)
                            }
                        },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }

                if (!atBottom) {
                    JumpToBottom(
                        onClick = { scope.launch { listState.animateScrollToItem(messages.lastIndex) } },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.x3),
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
            onClearDraft = { draft = "" },
            onStop = onStop,
            attachments = pendingAttachments,
            onAttach = onAttach,
            onRemoveAttachment = onRemoveAttachment,
        )
    }
}

@Composable
private fun MessageRow(
    message: ChatMessage,
    onOpenFile: ((String) -> Unit)? = null,
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
                    // Hugs the words. `fillMaxWidth(0.78f)` gave "ok" a box the width
                    // of a paragraph, so every short reply looked like a long one and
                    // the column of identical rectangles said nothing about the
                    // conversation's shape.
                    .widthIn(max = 300.dp)
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
                // Who wrote *this* reply, from the message itself. Absent on
                // history loaded back from the computer, which records no author —
                // and a blank line is the honest rendering of "not known", where a
                // name taken from the current selection would be a confident lie.
                message.persona?.let { persona ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
                        modifier = Modifier.padding(bottom = Spacing.x1),
                    ) {
                        PersonalityAvatar(
                            id = persona.id,
                            name = persona.name,
                            tint = persona.tint,
                            size = 20.dp,
                        )
                        Text(persona.name, style = type.label, color = colors.textMuted)
                    }
                }

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
 * A message written while a turn was still running.
 *
 * It goes out on its own the moment the answer lands. Shown rather than silently
 * held, because a message that vanished from the composer and has not appeared in
 * the transcript is one somebody will type again.
 */
@Composable
private fun QueuedNotice(onClear: () -> Unit) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.x2)
            .clip(Radii.md)
            .background(colors.accentSoft)
            .padding(start = Spacing.x3, top = Spacing.x1, bottom = Spacing.x1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        Text(
            text = "Sends when this turn ends",
            style = type.meta,
            color = colors.accent,
            modifier = Modifier.weight(1f),
        )

        Box(
            modifier = Modifier
                .size(Touch.minTarget)
                .clip(CircleShape)
                .clickable(onClick = onClear),
            contentAlignment = Alignment.Center,
        ) {
            Text("\u2715", style = type.meta, color = colors.accent)
        }
    }
}

/**
 * One file on its way up, or already there.
 *
 * Shown from the moment it is picked rather than when it finishes, because a file
 * that is quietly uploading with nothing on screen looks like a tap that missed.
 * Removable at any point — including mid-send, where the upload is abandoned and
 * the computer told to forget the bytes.
 */
@Composable
private fun AttachmentChip(state: UploadState, onRemove: () -> Unit) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    val file = when (state) {
        is UploadState.Sending -> state.file
        is UploadState.Done -> state.file
        is UploadState.Failed -> state.file
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.x2)
            .clip(Radii.md)
            .background(colors.bgSurface2)
            .padding(horizontal = Spacing.x3, vertical = Spacing.x2),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            AnodexIcon(AnodexIcon.PAPERCLIP, size = 16.dp, tint = colors.textFaint)

            Column(Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = type.label,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when (state) {
                        is UploadState.Sending -> "Sending\u2026"
                        is UploadState.Done -> "On your computer"
                        is UploadState.Failed -> state.message
                    },
                    style = type.meta,
                    color = if (state is UploadState.Failed) colors.danger else colors.textFaint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box(
                modifier = Modifier
                    .size(Touch.minTarget)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Text("\u2715", style = type.body, color = colors.textFaint)
            }
        }

        if (state is UploadState.Sending) {
            Box(
                Modifier
                    .padding(top = Spacing.x1)
                    .fillMaxWidth()
                    .heightIn(min = 2.dp, max = 2.dp)
                    .clip(Radii.pill)
                    .background(colors.bgElevated)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(state.fraction.coerceIn(0f, 1f))
                        .heightIn(min = 2.dp, max = 2.dp)
                        .clip(Radii.pill)
                        .background(colors.accent)
                )
            }
        }
    }
}

/**
 * The join between one exchange and the next.
 *
 * The transcript was correct and completely flat — a long conversation read as one
 * undifferentiated column with no way to see where you had asked something new.
 * This is the whole of the fix: a hairline, faded at both ends, drawn only where a
 * question begins.
 */
@Composable
private fun TurnSeam() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.x2)
            .heightIn(min = 1.dp, max = 1.dp)
            .background(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.18f to AnodexTheme.colors.border,
                    0.82f to AnodexTheme.colors.border,
                    1f to Color.Transparent,
                )
            )
    )
}

/**
 * What you asked, held at the top once it has scrolled away.
 *
 * A long answer easily runs past a screen, and by the time it is worth judging, the
 * question is gone. One line, tappable to go back to it in full.
 */
@Composable
private fun CurrentRequestBar(
    text: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.x3, vertical = Spacing.x2)
            .clip(Radii.lg)
            .background(colors.bgElevated)
            .clickable(onClick = onTap)
            .padding(horizontal = Spacing.x3, vertical = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Current request", style = type.badge, color = colors.textFaint)
            Text(
                text = text,
                style = type.label,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Back to the newest turn, when the reader has scrolled away from it. */
@Composable
private fun JumpToBottom(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = modifier
            .clip(Radii.pill)
            .background(colors.bgElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        Text("\u2193", style = type.body, color = colors.text)
        Text("Latest", style = type.label, color = colors.text)
    }
}

/**
 * How far from the exact end still counts as "at the end".
 *
 * Without a tolerance, a stream that grows the last item by a pixel between frames
 * reads as the reader having scrolled up, and the screen stops following its own
 * output mid-sentence.
 */
private const val STICK_SLOP_PX = 24

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
    onClearDraft: () -> Unit = {},
    attachments: List<UploadState> = emptyList(),
    onAttach: (() -> Unit)? = null,
    onRemoveAttachment: (UploadState) -> Unit = {},
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
        // Said while it is true, not after. A message that is going to send
        // itself should say so before it does, and be removable right up to the
        // moment it fires: one you thought better of must not become
        // unstoppable just because the turn in front of it is slow.
        if (sending && draft.isNotBlank()) QueuedNotice(onClear = onClearDraft)

        // Above the field, because an attachment belongs to the message being
        // written rather than to the act of typing it.
        for (attachment in attachments) {
            AttachmentChip(attachment, onRemove = { onRemoveAttachment(attachment) })
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            if (onAttach != null) {
                // One control, not two. A `+` and a paperclip side by side is two
                // buttons for one job, and neither says which.
                Box(
                    modifier = Modifier
                        .size(Touch.minTarget)
                        .clip(CircleShape)
                        .clickable(onClick = onAttach),
                    contentAlignment = Alignment.Center,
                ) {
                    AnodexIcon(AnodexIcon.PAPERCLIP, size = 20.dp, tint = colors.textMuted)
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(Radii.lg)
                    .background(colors.bgInput)
                    .padding(horizontal = Spacing.x3, vertical = Spacing.x3),
            ) {
                if (draft.isEmpty()) {
                    Text(
                        // Mid-turn you are usually writing the next instruction rather
                        // than trying to stop it, so the field stays live. It no longer
                        // says "queue": nothing sends this by itself, and the old
                        // wording promised exactly that while the field was disabled
                        // and would not take a keystroke at all.
                        text = if (sending) "Write your next message\u2026" else "Ask Anodex\u2026",
                        style = type.chatBody,
                        color = colors.textFaint,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
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
