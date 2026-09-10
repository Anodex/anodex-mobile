package dev.anodex.mobile.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.chat.ChatMessage
import dev.anodex.mobile.chat.ToolActivity
import dev.anodex.mobile.chat.ToolApproval
import dev.anodex.mobile.chat.UploadState
import dev.anodex.mobile.chat.toolSummary
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.components.AnodexMark
import dev.anodex.mobile.ui.components.AnodexSpinner
import dev.anodex.mobile.ui.components.SCRIM_ALPHA
import dev.anodex.mobile.ui.components.SCRIM_FADE
import dev.anodex.mobile.ui.components.SCRIM_HOLD
import dev.anodex.mobile.ui.components.fadingEdges
import dev.anodex.mobile.ui.components.AttachmentThumb
import dev.anodex.mobile.ui.components.FacetField
import dev.anodex.mobile.ui.components.ImageViewer
import dev.anodex.mobile.ui.components.MarkdownText
import dev.anodex.mobile.ui.components.PersonalityAvatar
import dev.anodex.mobile.ui.components.ToolApprovalCard
import dev.anodex.mobile.ui.components.ToolRow
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Elevation
import dev.anodex.mobile.ui.theme.LocalReducedMotion
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch
import dev.anodex.mobile.workspace.ChangedFile
import dev.anodex.mobile.workspace.describeDelta
import dev.anodex.mobile.workspace.shortPath
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    /**
     * Things worth asking, drawn from what is actually true on the computer now.
     *
     * Not a list of what Anodex can do — every assistant has one of those and
     * nobody reads it. These name the project that is open, the mail that is
     * unread, the task that ran: questions somebody would have asked anyway, which
     * is the only kind worth putting in front of them.
     */
    openers: List<String> = emptyList(),
    /**
     * How much floating chrome sits over the top of this screen.
     *
     * The header does not push the conversation down any more; it hangs over it, and
     * the transcript runs underneath and fades out into it. This is how far down the
     * fade reaches and how much room the list leaves so its first turn can still be
     * scrolled clear of the bar.
     */
    topInset: Dp = 0.dp,
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

    /**
     * Your own message always jumps to the bottom; a streaming reply only follows if
     * you were already there.
     *
     * The distinction was missing, and it is the whole of the bug: pressing send is a
     * deliberate act, so the thing you just wrote must be on screen afterwards — it
     * was landing behind the composer, out of sight, with a "Latest" button as the
     * only clue. Tokens arriving are *not* deliberate, and chasing those while
     * somebody reads further up is the rudest thing a chat screen can do.
     *
     * Keyed on the id rather than the count, because a count is equal again the
     * moment anything is replaced, and this has to fire once per new message.
     */
    val newestId = messages.lastOrNull()?.id
    LaunchedEffect(newestId) {
        if (messages.isEmpty()) return@LaunchedEffect
        // A turn appends the question and its pending answer together, so the newest
        // being an assistant placeholder still means "you just sent something".
        listState.showEndOf(messages.lastIndex)
    }

    LaunchedEffect(messages.lastOrNull()?.text) {
        // Snapped, not animated. This fires on every token — thirty times a second
        // on a fast reply — and an animation started that often spends its whole
        // life being cancelled and restarted by the next one. The result reads as
        // stutter rather than motion, and costs the most exactly when the screen is
        // busiest.
        //
        // Following text as it arrives should feel like the page staying still under
        // a growing message, which is what an instant scroll of a few pixels per
        // token actually looks like. The animated version is kept for the deliberate
        // jumps below, where there is a real distance to travel and a reason to show
        // it being travelled.
        if (messages.isNotEmpty() && atBottom) listState.showEndOf(messages.lastIndex, smooth = false)
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

    // How tall the composer and whatever is stacked above it turned out to be.
    //
    // Measured rather than assumed, because it is not a fixed height: the field grows
    // to six lines, an attachment chip appears above it, and a tool approval card can
    // sit on top of both. The transcript needs the real number to know where to stop
    // fading and how far to let its last turn scroll.
    var bottomInset by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    Box(modifier = modifier.fillMaxSize().background(colors.bgApp).imePadding()) {
        if (messages.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = topInset, bottom = bottomInset),
                contentAlignment = Alignment.Center,
            ) {
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

                    // Filled into the composer rather than sent. The wording is a
                    // starting point and the person tapping it usually has a version
                    // of their own in mind — sending outright takes that away, and
                    // the edit is the cheap half of asking.
                    for (opener in openers) {
                        Text(
                            text = opener,
                            style = type.body,
                            color = colors.textMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .heightIn(min = Touch.minTarget)
                                .clip(Radii.pill)
                                .background(colors.bgSurface)
                                .clickable { draft = opener }
                                .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
                        )
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        // The fade goes on the scrolling content and nothing else, so
                        // the pinned request and the jump-to-bottom button stay solid
                        // while the conversation dissolves behind the bars.
                        .fadingEdges(topInset, bottomInset)
                        .padding(horizontal = Spacing.x4),
                    verticalArrangement = Arrangement.spacedBy(Spacing.x3),
                    // Room to scroll clear of the bars. Without it the first turn can
                    // never be read in full — it stops under the header and stays there.
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        top = topInset + Spacing.x4,
                        bottom = bottomInset + Spacing.x4,
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
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = topInset),
                    )
                }

                if (!atBottom) {
                    JumpToBottom(
                        onClick = { scope.launch { listState.showEndOf(messages.lastIndex) } },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = bottomInset + Spacing.x3),
                    )
                }
            }
        }

        // Something for the composer to sit against.
        //
        // The conversation runs underneath and is meant to show through — that is the
        // effect — but a control floating on nothing but moving text is hard to find.
        // This holds most of the page's own colour at the very bottom and lets go of
        // it just past the composer, so the words still ghost through without
        // competing with the one control that sends them.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(bottomInset + SCRIM_FADE)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f - SCRIM_HOLD to colors.bgApp.copy(alpha = SCRIM_ALPHA),
                        1f to colors.bgApp,
                    )
                )
        )

        // Over the conversation rather than below it, and measuring itself so the
        // transcript knows how much of itself is behind it.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { bottomInset = with(density) { it.height.toDp() } },
        ) {
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

    /** The attachment being looked at full-screen, if any. */
    var viewing by remember(message.id) { mutableStateOf<String?>(null) }

    viewing?.let { uri ->
        ImageViewer(localUri = uri, onDismiss = { viewing = null })
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (isUser) {
            Column(horizontalAlignment = Alignment.End) {
                // What was sent with it, above the words. Stored on the message since
                // attachments landed and never drawn until now, so a picture went to
                // the computer and left no trace in the conversation it belonged to.
                for (file in message.attachments) {
                    if (file.isImage) {
                        // The picture on its own. A filename beside it is a caption
                        // nobody wrote — for a screenshot the image *is* the message,
                        // and its name is a camera's timestamp.
                        // Tappable only when there is something to open. An
                        // attachment loaded back from the computer has no local copy,
                        // and a tile that responds by doing nothing reads as broken
                        // rather than as unavailable.
                        val openable = file.localUri
                        AttachmentThumb(
                            localUri = file.localUri,
                            isImage = true,
                            size = 200.dp,
                            modifier = Modifier
                                .padding(bottom = Spacing.x2)
                                .then(
                                    if (openable != null) {
                                        // Clipped first so the press wash is the tile,
                                        // not a square behind its rounded corners.
                                        Modifier
                                            .clip(Radii.lg)
                                            .clickable { viewing = openable }
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    } else {
                        // A file that cannot be looked at is only its name, so that is
                        // what there is to show.
                        Row(
                            modifier = Modifier
                                .padding(bottom = Spacing.x2)
                                .widthIn(max = 300.dp)
                                .clip(Radii.lg)
                                .background(colors.bgSurface2)
                                .padding(Spacing.x3),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
                        ) {
                            AnodexIcon(
                                AnodexIcon.PAPERCLIP,
                                size = 16.dp,
                                tint = colors.textFaint,
                            )
                            Text(
                                text = file.name,
                                style = type.meta,
                                color = colors.textMuted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                if (message.text.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            // Hugs the words. `fillMaxWidth(0.78f)` gave "ok" a box the
                            // width of a paragraph, so every short reply looked like a
                            // long one and a column of identical rectangles said
                            // nothing about the conversation's shape.
                            .widthIn(max = 300.dp)
                            .clip(UserBubble)
                            .background(colors.bgSurface2)
                            // The edge the desktop has had all along. Without it the
                            // bubble is a slightly lighter patch of the page — at these
                            // near-black values #161616 on #0C0C0C is barely a shape,
                            // and the border is most of what makes it one.
                            .border(1.dp, colors.border, UserBubble)
                            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
                    ) {
                        Text(message.text, style = type.chatBody, color = colors.text)
                    }
                }
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

                // What it is doing *now*, while it is still doing it. The collapsed
                // summary is written for reading a turn back afterwards — it counts
                // what happened. Mid-turn that is the wrong question: the screen has
                // to say the computer is working and what on, or a long tool run
                // looks exactly like a stall.
                val running = message.tools.lastOrNull { it.status == ToolActivity.Status.RUNNING }
                if (message.streaming && running != null) {
                    RunningLine(running.title)
                }

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

                // What ended up different, after the words. The tool rows above say
                // what the turn attempted; this is the computer's own record of what
                // actually changed on disk — the half worth trusting a run on when
                // you are not in the room to look.
                if (message.changedFiles.isNotEmpty()) {
                    ChangedFiles(message.changedFiles, onOpenFile)
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
            AttachmentThumb(
                localUri = file.uri.toString(),
                isImage = file.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS,
                size = 40.dp,
            )

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
 * The comet arc next to the words, because a line of text that only fades says
 * "something is here" where a turning one says "something is happening" — the same
 * distinction the desktop draws, and the reason its spinner stretches as it goes
 * rather than rotating evenly.
 *
 * The shimmer stays underneath it. Together they are one signal at two speeds, and
 * either alone is weaker: the arc without the fade is a loading spinner like any
 * other, and the fade without the arc is a label that happens to breathe.
 *
 * Both stop the moment a token lands.
 */
/**
 * The tool running right now, named.
 *
 * The desktop shows work as it happens; the phone only had a count of what had
 * already finished, so a turn spending two minutes in one search looked identical
 * to a turn that had died. Saying "Reading parser.py" is the difference between
 * waiting and wondering.
 *
 * The title comes from the computer — the same string its own transcript shows —
 * so the two never word one action differently.
 */
@Composable
private fun RunningLine(title: String) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
        modifier = Modifier.padding(vertical = Spacing.x1),
    ) {
        AnodexSpinner(size = 13.dp, thickness = 1.5.dp, tint = colors.accent)
        Text(
            text = title,
            style = type.meta,
            color = colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Put the *end* of an item on screen, not its top.
 *
 * `animateScrollToItem` aligns an item's top edge with the viewport, which is right
 * for a list of short rows and wrong for a chat: a reply taller than the screen
 * landed with its first line showing and the rest below the fold, so following a
 * long answer meant scrolling by hand every few seconds.
 *
 * Two steps because the second is unknowable before the first: the item has to be
 * laid out before there is anything to measure.
 */
private suspend fun LazyListState.showEndOf(index: Int, smooth: Boolean = true) {
    if (index < 0) return

    if (smooth) animateScrollToItem(index) else scrollToItem(index)

    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val past = (item.offset + item.size) - layoutInfo.viewportEndOffset
    if (past <= 0) return

    if (smooth) animateScrollBy(past.toFloat()) else scrollBy(past.toFloat())
}

/**
 * The files a turn changed, named.
 *
 * Collapsed to a count until asked, for the same reason the tool rows are: a run
 * that touched twenty files would otherwise bury the answer it was asked for.
 *
 * Read-only from here. The computer knows how to put these back, and that is
 * deliberately not offered on a phone — undoing an afternoon of work wants a diff
 * in front of you, and a filename and a byte count is not that.
 */
@Composable
private fun ChangedFiles(files: List<ChangedFile>, onOpenFile: ((String) -> Unit)?) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    var expanded by rememberSaveable(files.size) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.x2)
            .clip(Radii.lg)
            .background(colors.bgSurface2)
            .padding(Spacing.x3),
        verticalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // A row of 14dp icon and 13sp label came out around twenty dp tall —
                // under half the floor everything else in this app clears, on a
                // control that is genuinely tapped.
                .heightIn(min = Touch.minTarget)
                .clip(Radii.md)
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            AnodexIcon(AnodexIcon.FOLDER, size = 14.dp, tint = colors.accentGreen)
            Text(
                text = if (files.size == 1) "Changed 1 file" else "Changed ${files.size} files",
                style = type.label,
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "Hide" else "Show",
                style = type.meta,
                color = colors.accent,
            )
        }

        if (!expanded) return@Column

        for (file in files) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Touch.minTarget)
                    .then(
                        if (onOpenFile != null) {
                            Modifier.clickable { onOpenFile(file.path) }
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
            ) {
                Text(
                    text = kindMark(file.kind),
                    style = type.mono,
                    color = kindColour(file.kind, colors),
                )
                Text(
                    text = shortPath(file.path),
                    style = type.meta,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Only when it moved. A rewrite that lands on the same length is
                // still a change, and "0 bytes" beside it reads as "nothing happened".
                describeDelta(file.sizeDelta)?.let { delta ->
                    Text(delta, style = type.meta, color = colors.textFaint)
                }
            }

            // The record is of the turn, not of the file as it stands now. Somebody
            // editing the same file afterwards makes it a description of a state that
            // no longer exists, and saying so is cheaper than being quietly wrong.
            if (file.conflicted) {
                Text(
                    text = "Edited again since this turn.",
                    style = type.meta,
                    color = colors.warn,
                )
            }
        }
    }
}

/** One character, because a list of files is read down the left edge. */
private fun kindMark(kind: String?): String = when (kind) {
    "added" -> "+"
    "deleted" -> "−"
    else -> "~"
}

@Composable
private fun kindColour(kind: String?, colors: dev.anodex.mobile.ui.theme.AnodexColors) =
    when (kind) {
        "added" -> colors.accentGreen
        "deleted" -> colors.danger
        else -> colors.textFaint
    }

/**
 * The shape of something you said.
 *
 * Rounded on three corners with the trailing one cut sharp — the corner nearest the
 * edge the message is aligned to. The desktop has drawn user turns this way since
 * before the phone existed and describes it in one line: the sharper corner points
 * back at the sender. It is the only thing distinguishing a bubble you wrote from a
 * panel, and the phone had quietly dropped it.
 *
 * Stepped up from the desktop's 8px to 14dp for the same reason `Typography.kt`
 * re-steps the body size: the desktop scale is read at arm's length on a large
 * screen, and carried over literally it looks pinched in the hand. The relationship
 * between the two radii is what matters, and that is preserved.
 *
 * Assistant turns get none of this, deliberately. The reply is the page rather than
 * a card sitting on it, and bubbling both sides would make a conversation look like
 * two people texting instead of somebody working.
 */
private val UserBubble = RoundedCornerShape(
    topStart = 14.dp,
    topEnd = 14.dp,
    bottomStart = 14.dp,
    bottomEnd = 4.dp,
)

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

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        AnodexSpinner(size = 14.dp, thickness = 1.5.dp, tint = colors.textFaint)

        Text(
            text = "Thinking…",
            style = AnodexTheme.type.chatBody,
            color = colors.textFaint.copy(alpha = if (reducedMotion) 1f else alpha),
        )
    }
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
            // Nothing behind it at all — no band, no line.
            //
            // It was a filled `bgSurface` rectangle, then briefly a hairline, and now
            // the conversation itself runs underneath and fades out into it. That
            // fade is the separation, and it is a better one than a rule: it says
            // there is more up there rather than drawing a line and stopping.
            //
            // The field still reads as a control because it has an edge that lifts to
            // the accent the moment there is something to send.
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

        // One surface, not three sitting beside each other. The attach control, the
        // field and Send used to be separate shapes in a row, which reads as a
        // toolbar that happens to contain somewhere to type — and the field itself
        // ended up the smallest thing in it.
        //
        // Wrapping them in a single pill puts the weight where the writing happens
        // and gives both controls a bigger target than they had loose.
        //
        // The edge is the Anodex part rather than a borrowed one: it lifts to the
        // accent the moment there is something to send. Elsewhere in this app a
        // border means state — the blocked agent run wears one — so it means state
        // here too, instead of being a line drawn for the look of it.
        val hasSomethingToSend = draft.isNotBlank() || attachments.isNotEmpty()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Lifted for the same reason the title pill is: it hangs over the
                // conversation now, and a flat field on a transparent ground is hard
                // to find when there is text moving behind it.
                .shadow(Elevation.md, COMPOSER_SHAPE)
                .clip(COMPOSER_SHAPE)
                .background(colors.bgInput)
                .border(
                    width = 1.dp,
                    color = if (hasSomethingToSend) colors.accent else colors.border,
                    shape = COMPOSER_SHAPE,
                )
                .padding(Spacing.x1),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
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
                    // Enough height that a one-line message still looks like somewhere
                    // to write rather than a slot to fill in.
                    .heightIn(min = Touch.minTarget)
                    .padding(
                        start = if (onAttach != null) 0.dp else Spacing.x3,
                        end = Spacing.x2,
                        top = Spacing.x3,
                        bottom = Spacing.x3,
                    ),
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
                    // Past this the field scrolls instead of growing. Without it a
                    // long message pushed the conversation off the top of the screen
                    // and kept going — a prompt of a few paragraphs left nothing on
                    // screen but the thing being typed.
                    maxLines = COMPOSER_MAX_LINES,
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
 * The composer's outline.
 *
 * Half the height of the collapsed row — the tallest thing in it plus the row's own
 * padding — so an empty composer is a perfect stadium and a full one is a rounded
 * rectangle with those same corners.
 *
 * It used to be [Radii.pill], which is 999dp and therefore *always* half the height
 * whatever the height is. On one line that is the intended stadium. On fifteen it is
 * a circle the width of the screen, and the text inside gets carved away by its own
 * border: a long prompt appeared with its first and last lines sliced off along a
 * curve. The radius has to stop growing at the point the row stops being one line.
 */
private val COMPOSER_SHAPE = RoundedCornerShape((Touch.minTarget + Spacing.x1 * 2) / 2)




/**
 * Six lines.
 *
 * Enough to see a paragraph while writing it, and short enough that the conversation
 * being written *into* is still on screen above the keyboard. Beyond this the field
 * scrolls and keeps the cursor in view, which is what a text field does everywhere
 * else on the phone.
 */
private const val COMPOSER_MAX_LINES = 6

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

/** What the thumbnail will try to decode. Mirrors what the computer accepts. */
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp")
