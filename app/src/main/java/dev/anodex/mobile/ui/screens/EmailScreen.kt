package dev.anodex.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anodex.mobile.AnodexViewModel
import dev.anodex.mobile.connection.ConnectionState
import dev.anodex.mobile.email.EmailNote
import dev.anodex.mobile.email.MailFlag
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.text.style.TextAlign
import dev.anodex.mobile.email.EmailThread
import androidx.compose.ui.platform.LocalContext
import dev.anodex.mobile.ui.components.ConfirmDialog
import dev.anodex.mobile.ui.components.PrimaryButton
import dev.anodex.mobile.ui.components.SecondaryButton
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.components.EmptyState
import dev.anodex.mobile.ui.components.EmptyTone
import dev.anodex.mobile.ui.components.InlineProblem
import dev.anodex.mobile.ui.components.ListSkeleton
import dev.anodex.mobile.ui.components.ScreenScaffold
import dev.anodex.mobile.ui.components.SearchField
import dev.anodex.mobile.ui.components.fadingEdges
import dev.anodex.mobile.ui.components.listPadding
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import androidx.compose.foundation.layout.wrapContentSize
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch
import java.util.concurrent.TimeUnit

/**
 * The Email tab: the inbox, and one thread when you open it.
 *
 * Read-only. Sending from here would mean composing a message on a phone that then
 * leaves someone else's machine under their name, with none of the desktop's
 * approval step — and the thing actually worth having away from the desk is seeing
 * what arrived, not answering it in a text field.
 */
@Composable
fun EmailPane(viewModel: AnodexViewModel, modifier: Modifier = Modifier) {
    val threads by viewModel.emailThreads.collectAsStateWithLifecycle()
    val loading by viewModel.emailLoading.collectAsStateWithLifecycle()
    val configured by viewModel.emailConfigured.collectAsStateWithLifecycle()
    val emailError by viewModel.emailError.collectAsStateWithLifecycle()
    val openThread by viewModel.openThread.collectAsStateWithLifecycle()
    val threadLoading by viewModel.threadLoading.collectAsStateWithLifecycle()

    // Fetched when the tab is opened rather than on connect: a user who never opens
    // Email should not be making the desktop hit their mail provider.
    //
    // Keyed on the connection as well as on arriving here, because opening this tab
    // during a reconnect finds no socket to ask and would otherwise sit on an empty
    // inbox until the user thought to leave the tab and come back.
    val connected by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(connected is ConnectionState.Connected) {
        if (connected is ConnectionState.Connected) viewModel.refreshEmail()
    }

    val draft by viewModel.mailDraft.collectAsStateWithLifecycle()
    val drafting by viewModel.mailDrafting.collectAsStateWithLifecycle()
    val drafted by viewModel.mailDrafted.collectAsStateWithLifecycle()
    val mailSending by viewModel.mailSending.collectAsStateWithLifecycle()
    val mailError by viewModel.mailError.collectAsStateWithLifecycle()
    val shownImages by viewModel.shownImages.collectAsStateWithLifecycle()
    val mailQuery by viewModel.mailQuery.collectAsStateWithLifecycle()
    val mailResults by viewModel.mailResults.collectAsStateWithLifecycle()
    val mailSearching by viewModel.mailSearching.collectAsStateWithLifecycle()

    // Above the reader, so Back out of a half-written reply lands on the message it
    // answers rather than on the inbox.
    draft?.let { open ->
        BackHandler { viewModel.closeMail() }
        ComposeMailScreen(
            draft = open,
            onClose = viewModel::closeMail,
            onSend = viewModel::sendMail,
            onAskAnodex = viewModel::draftMail,
            drafting = drafting,
            drafted = drafted,
            sending = mailSending,
            error = mailError,
            modifier = modifier,
        )
        return
    }

    // Shown over whatever is beneath, because a link tapped in a message is a
    // question that has to be answered before anything else happens.
    val pendingLink by viewModel.pendingLink.collectAsStateWithLifecycle()
    val linkContext = LocalContext.current
    pendingLink?.let { url ->
        ConfirmDialog(
            title = "Open this link?",
            // The whole URL, not the text that was tapped. In mail those are
            // different strings more often than anywhere else, and the gap between
            // them is the entire trick.
            body = url,
            confirmLabel = "Open in browser",
            onConfirm = {
                viewModel.dismissLink()
                linkContext.startActivity(viewModel.linkIntent(url))
            },
            onDismiss = viewModel::dismissLink,
        )
    }

    if (openThread != null) {
        BackHandler { viewModel.closeEmailThread() }
        ThreadReader(
            notes = openThread.orEmpty(),
            loading = threadLoading,
            onClose = viewModel::closeEmailThread,
            onReply = { note, all ->
                viewModel.startMail(replyDraft(note, all))
            },
            onForward = { note -> viewModel.startMail(forwardDraft(note)) },
            shownImages = shownImages,
            onShowImages = viewModel::showRemoteImages,
            onLink = viewModel::openLink,
            onFlag = { action -> viewModel.flagOpenThread(action) },
            onTrash = viewModel::trashOpenThread,
            modifier = modifier,
        )
        return
    }

    InboxList(
        threads = mailResults ?: threads,
        searching = mailSearching,
        // Null means the inbox is showing, which is a different sentence from a
        // search that matched nothing.
        isResults = mailResults != null,
        query = mailQuery,
        onQueryChange = viewModel::searchMail,
        loading = loading,
        configured = configured,
        error = emailError,
        onOpen = viewModel::openEmailThread,
        onCompose = { viewModel.startMail(MailDraft()) },
        onSetUnread = viewModel::setThreadUnread,
        onSetStarred = viewModel::setThreadStarred,
        modifier = modifier,
    )
}

@Composable
private fun InboxList(
    threads: List<EmailThread>,
    loading: Boolean,
    configured: Boolean?,
    /** Why the mailbox could not be read. Outranks every other empty state below. */
    error: String? = null,
    onOpen: (EmailThread) -> Unit,
    modifier: Modifier = Modifier,
    nowEpochMs: Long = System.currentTimeMillis(),
    /** Start a new message. Null leaves the inbox read-only, as it was. */
    onCompose: (() -> Unit)? = null,
    /** Mark a thread read or unread from its dot. */
    onSetUnread: ((EmailThread, Boolean) -> Unit)? = null,
    /** Star a thread from the list. */
    onSetStarred: ((EmailThread, Boolean) -> Unit)? = null,
    /** What is being searched for, and how to change it. Null hides the field. */
    query: String = "",
    onQueryChange: ((String) -> Unit)? = null,
    searching: Boolean = false,
    /** True when the list is search results rather than the inbox. */
    isResults: Boolean = false,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    ScreenScaffold(
        title = if (isResults) "Search" else "Inbox",
        modifier = modifier,
        beneath = if (onQueryChange == null) null else {
            {
                SearchField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = "Search mail",
                    modifier = Modifier.padding(top = Spacing.x2),
                )
            }
        },
        // In the header rather than as a button floating over the list. The inbox
        // is read far more often than it is written to, and a control covering the
        // newest row sits on top of what people opened this for.
        trailing = onCompose?.let { { SecondaryButton(label = "Write", onClick = it) } },
    ) { topInset ->
        val emptyModifier = Modifier.padding(top = topInset)

        when {
            // First, because every state under this one is a statement about the
            // mailbox, and none of them can be made when the mailbox was not reached.
            // This used to fall through to "No email account is connected on your
            // computer" — the most confident wrong sentence in the app.
            error != null && threads.isEmpty() -> EmptyState(
                headline = "Could not read your mail",
                detail = error,
                tone = EmptyTone.PROBLEM,
                icon = AnodexIcon.MAIL,
                modifier = emptyModifier,
            )

            // A search that matched nothing is not an empty mailbox, and it is
            // certainly not a missing account -- which is what the branches below
            // would otherwise say to somebody who mistyped a word. First, so it
            // wins over all of them.
            isResults && threads.isEmpty() && !searching -> EmptyState(
                headline = "Nothing matched",
                detail = "No message in this mailbox contains “$query”.",
                icon = AnodexIcon.SEARCH,
                modifier = emptyModifier,
            )

            (loading || searching) && threads.isEmpty() -> ListSkeleton(
                rows = 5,
                lines = 2,
                caption = if (searching) "Searching…" else "Reading your mail…",
                modifier = Modifier.padding(listPadding(topInset)),
            )

            // Not yet asked — the socket was down when this tab opened. Saying
            // "nothing in the inbox" here would be a claim the app has no basis for,
            // and the user would believe it.
            configured == null -> EmptyState(
                headline = "Waiting for your computer…",
                detail = "The mailbox has not been reached yet, so there is nothing to say about it.",
                tone = EmptyTone.WAITING,
                icon = AnodexIcon.MAIL,
                modifier = emptyModifier,
            )

            // Told apart on purpose: an inbox with nothing in it and an inbox that
            // does not exist look identical in a list and need opposite words.
            configured == false -> EmptyState(
                headline = "No mail account connected",
                detail = "Connect one on your computer and it will show up here.",
                icon = AnodexIcon.MAIL,
                modifier = emptyModifier,
            )

            threads.isEmpty() -> EmptyState(
                headline = "Nothing in the inbox",
                detail = "Read at the computer, never stored on the phone.",
                icon = AnodexIcon.MAIL,
                modifier = emptyModifier,
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .fadingEdges(topInset, 0.dp),
                contentPadding = listPadding(topInset, horizontal = Spacing.x4),
                verticalArrangement = Arrangement.spacedBy(Spacing.x2),
            ) {
                // A mailbox that failed to refresh while it still had threads to show
                // could say nothing at all before: the error state required an empty
                // list. Stale mail presented as current is the version of this bug
                // that actually costs somebody something.
                if (error != null) {
                    item(key = "read-failed") { InlineProblem(error) }
                }

                items(threads, key = { it.id }) { thread ->
                    ThreadRow(
                        thread = thread,
                        nowEpochMs = nowEpochMs,
                        onClick = { onOpen(thread) },
                        onSetUnread = onSetUnread,
                        onSetStarred = onSetStarred,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadRow(
    thread: EmailThread,
    nowEpochMs: Long,
    onClick: () -> Unit,
    /** Toggle read state. Null leaves the dot as an indicator only. */
    onSetUnread: ((EmailThread, Boolean) -> Unit)? = null,
    /** Toggle the star. Null hides it. */
    onSetStarred: ((EmailThread, Boolean) -> Unit)? = null,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    // A card per message rather than rows separated by rules.
    //
    // The list used to be text on one flat plane, which reads as a document.
    // Every mail client on a phone draws a card, and the reason is the thumb: a
    // card says where one message ends and the next begins without a hairline
    // that disappears at arm's length, and it gives the tap a shape.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.lg)
            .background(if (thread.unread) colors.bgSurface2 else colors.bgSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.x3, vertical = Spacing.x3),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        SenderAvatar(from = thread.from)

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
            ) {
                Text(
                    text = senderName(thread.from),
                    style = type.body.copy(
                        fontWeight = if (thread.unread) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = relativeTime(thread.updatedAtEpochMs, nowEpochMs),
                    style = type.meta,
                    color = if (thread.unread) colors.text else colors.textFaint,
                    maxLines = 1,
                )

                // The dot moved to the right, beside the time.
                //
                // On the left it was the first thing in the row and competed with
                // the avatar for the same job. Beside the time it sits with the
                // other thing that changes per message and leaves the left edge to
                // say who it is from. Still the control for read state, and still
                // with a finger's worth of target around six pixels of dot.
                Box(
                    modifier = Modifier
                        .size(Touch.minTarget / 2)
                        .clip(CircleShape)
                        .let { base ->
                            if (onSetUnread == null) base
                            else base.clickable { onSetUnread(thread, !thread.unread) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (thread.unread) colors.accent else Color.Transparent)
                    )
                }
            }

            Text(
                text = thread.subject,
                style = type.body.copy(
                    fontWeight = if (thread.unread) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
            ) {
                Text(
                    text = thread.snippet,
                    style = type.meta,
                    color = colors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (thread.attachmentCount > 0) {
                    AnodexIcon(AnodexIcon.PAPERCLIP, size = 14.dp, tint = colors.textFaint)
                }

                // Starring from the list, which is where somebody decides a
                // message matters -- after reading the subject and before opening
                // it. Filled and tinted when set, outline and faint when not, so
                // the state is legible without reading a label.
                if (onSetStarred != null) {
                    Text(
                        text = if (thread.starred) "★" else "☆",
                        style = type.body,
                        color = if (thread.starred) colors.warn else colors.textFaint,
                        modifier = Modifier
                            .size(Touch.minTarget / 2)
                            .wrapContentSize(Alignment.Center)
                            .clip(CircleShape)
                            .clickable { onSetStarred(thread, !thread.starred) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadReader(
    notes: List<EmailNote>,
    loading: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Answer it. The boolean is reply-all. */
    onReply: ((EmailNote, Boolean) -> Unit)? = null,
    onForward: ((EmailNote) -> Unit)? = null,
    /** Remote images the reader has asked for, by message id then by original URL. */
    shownImages: Map<String, Map<String, String>> = emptyMap(),
    onShowImages: ((EmailNote, List<String>) -> Unit)? = null,
    onLink: ((String) -> Unit)? = null,
    /** Mark, star or archive this thread. Null hides the row. */
    onFlag: ((MailFlag) -> Unit)? = null,
    /** Move it to the computer's trash. Null hides the bin. */
    onTrash: (() -> Unit)? = null,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    // Armed per thread, so opening a different message does not inherit a
    // half-pressed delete from the last one.
    var confirmingTrash by remember(notes.firstOrNull()?.threadId) { mutableStateOf(false) }

    ScreenScaffold(
        // The chrome says what you can do; the subject has moved into the message
        // itself, below, where every other mail client puts it. A subject in a
        // 20-character title bar is a subject nobody can read, and it was being
        // truncated on half the mail in this inbox.
        title = if (notes.size > 1) "${notes.size} messages" else "Message",
        modifier = modifier,
        leading = { SecondaryButton(label = "Back", onClick = onClose) },
        trailing = if (onFlag == null) null else {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                    // Archive and mark-unread, as icons, where the thumb reaches on
                    // the way out of a message. No bin: mail is archived here and on
                    // the computer, never deleted, and a bin beside an archive box
                    // would be two buttons that look alike and differ in whether
                    // anything can be undone.
                    HeaderAction(AnodexIcon.ARCHIVE, "Archive") { onFlag(MailFlag.ARCHIVE) }
                    HeaderAction(AnodexIcon.MAIL, "Mark unread") { onFlag(MailFlag.UNREAD) }
                    // Delete last, furthest from the two that are undone by
                    // pressing them again. It asks once: the message is
                    // recoverable, but only from the trash on the computer, and
                    // "where did that go" is a worse afternoon than one more tap.
                    if (onTrash != null) {
                        HeaderAction(
                            icon = AnodexIcon.TRASH,
                            label = if (confirmingTrash) "Tap again to delete" else "Delete",
                            tint = if (confirmingTrash) colors.dangerInk else colors.textMuted,
                        ) {
                            if (confirmingTrash) onTrash() else confirmingTrash = true
                        }
                    }
                }
            }
        },
    ) { topInset ->
        if (loading && notes.isEmpty()) {
            EmptyState(
                headline = "Opening…",
                tone = EmptyTone.WAITING,
                icon = AnodexIcon.MAIL,
                modifier = Modifier.padding(top = topInset),
            )
            return@ScreenScaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .fadingEdges(topInset, 0.dp)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = Spacing.x4,
                    end = Spacing.x4,
                    top = topInset + Spacing.x2,
                    bottom = Spacing.x6,
                ),
            verticalArrangement = Arrangement.spacedBy(Spacing.x5),
        ) {
            // The subject, full width and wrapping, above the first message.
            // This is what a mail client leads with and what the header bar could
            // not hold: three lines of it here beats twenty characters up there.
            notes.firstOrNull()?.let { first ->
                Text(
                    text = first.subject.ifBlank { "No subject" },
                    style = type.heading,
                    color = colors.text,
                )
            }

            for (note in notes) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                    // Who it is from, as a person: circle, name, when. The same
                    // three things the inbox row shows, so opening a message does
                    // not change what it is identified by.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
                    ) {
                        SenderAvatar(from = note.from, size = 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = senderName(note.from),
                                style = type.bodyEmphasis,
                                color = colors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // Who else received it. "to me" is the ordinary case and
                            // worth saying, because the case it distinguishes -- a
                            // message that went to nine other people -- changes how
                            // somebody answers it.
                            Text(
                                text = recipientLine(note.to, note.cc),
                                style = type.meta,
                                color = colors.textFaint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = relativeTime(note.dateEpochMs, System.currentTimeMillis()),
                            style = type.meta,
                            color = colors.textFaint,
                        )
                    }
                    if (note.attachmentCount > 0) {
                        // Named rather than offered. Downloading someone's attachment
                        // onto a phone through a socket into their PC is a separate
                        // decision from reading the message it came with.
                        Text(
                            text = "${note.attachmentCount} attachment" +
                                if (note.attachmentCount == 1) "" else "s",
                            style = type.meta,
                            color = colors.textFaint,
                        )
                    }
                    // The message as written, when the desktop sent one. `body` is
                    // the plain-text fallback and is what a message with no HTML
                    // part has always been.
                    val html = note.bodyHtml
                    if (html != null) {
                        val held = remoteImageCount(html)
                        val shown = shownImages[note.id].orEmpty()

                        MailBody(
                            html = html,
                            images = shown,
                            onLink = onLink,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Offered, never automatic. Fetching a remote image is how a
                        // sender learns the message was opened, when it was, and
                        // roughly from where -- so it is the reader's call, and the
                        // count is shown because "3 images" and "60 images" are
                        // different decisions.
                        if (held > 0 && shown.isEmpty() && onShowImages != null) {
                            SecondaryButton(
                                label = if (held == 1) "Show 1 image" else "Show $held images",
                                onClick = { onShowImages(note, remoteImageUrls(html)) },
                            )
                            Text(
                                text = "Loading them tells the sender you opened this.",
                                style = type.meta,
                                color = colors.textFaint,
                            )
                        }
                    } else {
                        Text(text = note.body, style = type.body, color = colors.textMuted)
                    }
                }
            }

            // Under the last message rather than floating over it. The actions
            // belong to the thread you have just finished reading, and a button
            // hovering above the text is one more thing covering the words.
            val last = notes.lastOrNull()
            if (last != null && (onReply != null || onForward != null)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
                ) {
                    if (onReply != null) {
                        PrimaryButton(
                            label = "Reply",
                            onClick = { onReply(last, false) },
                            modifier = Modifier.weight(1f),
                        )
                        // Only where it means something. On a message with one
                        // recipient, reply-all and reply are the same act under two
                        // names, and offering both invites the wrong one.
                        if (last.to.size + last.cc.size > 1) {
                            SecondaryButton(
                                label = "Reply all",
                                onClick = { onReply(last, true) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (onForward != null) {
                        SecondaryButton(
                            label = "Forward",
                            onClick = { onForward(last) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // Tidying, on its own row and quieter than answering.
                //
                // Every one of these is undone by another one of them. The
                // desktop's `EmailFlagAction` is read/unread, star/unstar and
                // archive/unarchive, and its own comment says deleting mail is
                // deliberately absent -- which is the property that makes these
                // safe to put a finger's width apart on a phone.
                if (onFlag != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.x1),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
                    ) {
                        for (action in listOf(MailFlag.STAR, MailFlag.ARCHIVE, MailFlag.UNREAD)) {
                            Text(
                                text = when (action) {
                                    MailFlag.STAR -> "Star"
                                    MailFlag.ARCHIVE -> "Archive"
                                    else -> "Mark unread"
                                },
                                style = type.label,
                                color = colors.textMuted,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = Touch.minTarget)
                                    .wrapContentHeight(Alignment.CenterVertically)
                                    .clip(Radii.md)
                                    .clickable { onFlag(action) }
                                    .padding(vertical = Spacing.x2),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A reply, with the threading headers and the right people on it.
 *
 * Reply-all keeps everyone the message was addressed to, minus the sender, who is
 * already in To. Leaving them in puts somebody in both fields and some clients
 * then deliver it twice.
 */
private fun replyDraft(note: EmailNote, all: Boolean): MailDraft =
    MailDraft(
        to = note.from,
        cc = if (all) {
            (note.to + note.cc).filterNot { it.contains(addressOf(note.from), ignoreCase = true) }
                .joinToString(", ")
        } else {
            ""
        },
        subject = replySubject(note.subject),
        inReplyTo = note,
        // The message's own thread, read off the message. This briefly used the
        // message id instead, which is a different identifier of the same shape --
        // the far end would have filed every reply as a new conversation and
        // nothing on this phone would have looked wrong.
        threadId = note.threadId,
        kind = if (all) "Reply all" else "Reply",
    )

private fun forwardDraft(note: EmailNote): MailDraft =
    MailDraft(
        subject = forwardSubject(note.subject),
        body = forwardBody(note),
        // No `inReplyTo`: a forward starts a new conversation with somebody who was
        // not in the old one, and threading it onto the original would file it under
        // a subject they have never seen.
        kind = "Forward",
    )

/** The bare address out of `Ada Lovelace <ada@example.com>`. */
private fun addressOf(from: String): String =
    from.substringAfter('<').substringBefore('>').ifBlank { from }.trim()



/**
 * An icon in the header bar, with a finger's worth of target around it.
 *
 * The actions a mail client keeps in the chrome: reachable on the way out of a
 * message, and small enough that two of them do not become the loudest thing on
 * the screen.
 */
@Composable
private fun HeaderAction(
    icon: AnodexIcon,
    label: String,
    tint: Color = AnodexTheme.colors.textMuted,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(Touch.minTarget)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnodexIcon(icon, size = 20.dp, tint = tint, contentDescription = label)
    }
}

/**
 * "to me", or who else was on it.
 *
 * The distinction worth drawing is between a message addressed to one person and
 * one addressed to a room, because it changes whether a reply should go to
 * everybody. Counted rather than listed: nine addresses do not fit on a phone
 * row and nobody reads them there anyway.
 */
internal fun recipientLine(to: List<String>, cc: List<String>): String {
    val others = to.size + cc.size
    return when {
        others <= 1 -> "to me"
        else -> "to me and ${others - 1} ${if (others == 2) "other" else "others"}"
    }
}

/**
 * "Ada Lovelace" out of `Ada Lovelace <ada@example.com>`.
 *
 * The display name is what a person recognises; the address is 40 characters of
 * noise that pushes everything else off a phone-width row. The address survives
 * when there is no name, because then it is all there is.
 */
internal fun senderName(from: String): String {
    val angle = from.indexOf('<')
    val name = if (angle > 0) from.substring(0, angle).trim().trim('"') else ""
    return name.ifBlank { from.substringAfter('<').substringBefore('>').ifBlank { from } }
}

/** Coarse on purpose: "2h" is what the reader wants, not a timestamp to the second. */
internal fun relativeTime(epochMs: Long, nowEpochMs: Long): String {
    if (epochMs <= 0) return ""
    val elapsed = (nowEpochMs - epochMs).coerceAtLeast(0)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
    val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
    val days = TimeUnit.MILLISECONDS.toDays(elapsed)

    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        hours < 24 -> "${hours}h"
        days < 7 -> "${days}d"
        else -> "${days / 7}w"
    }
}

@Preview(name = "Inbox", showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun PreviewInbox() {
    val now = 1_700_000_000_000L
    AnodexTheme(darkTheme = true) {
        InboxList(
            threads = listOf(
                EmailThread(
                    id = "1",
                    accountId = "a",
                    subject = "Re: collision LOD pass",
                    from = "Ada Lovelace <ada@example.com>",
                    snippet = "That looks right to me — ship it once the tests are green.",
                    updatedAtEpochMs = now - 20 * 60 * 1000,
                    unread = true,
                    starred = false,
                    messageCount = 4,
                    attachmentCount = 0,
                ),
                EmailThread(
                    id = "2",
                    accountId = "a",
                    subject = "Invoice 4471",
                    from = "billing@example.com",
                    snippet = "Your monthly statement is ready.",
                    updatedAtEpochMs = now - 3L * 24 * 60 * 60 * 1000,
                    unread = false,
                    starred = false,
                    messageCount = 1,
                    attachmentCount = 1,
                ),
            ),
            loading = false,
            configured = true,
            onOpen = {},
            nowEpochMs = now,
        )
    }
}

@Preview(name = "Inbox - not asked yet", showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun PreviewInboxUnknown() {
    // The state that used to read "Nothing in the inbox" without having looked.
    AnodexTheme(darkTheme = true) {
        InboxList(threads = emptyList(), loading = false, configured = null, onOpen = {})
    }
}

@Preview(name = "Inbox - no account", showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun PreviewInboxUnconfigured() {
    AnodexTheme(darkTheme = true) {
        InboxList(threads = emptyList(), loading = false, configured = false, onOpen = {})
    }
}
