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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anodex.mobile.AnodexViewModel
import dev.anodex.mobile.connection.ConnectionState
import dev.anodex.mobile.email.EmailNote
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
import dev.anodex.mobile.ui.components.fadingEdges
import dev.anodex.mobile.ui.components.listPadding
import dev.anodex.mobile.ui.theme.AnodexTheme
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
            modifier = modifier,
        )
        return
    }

    InboxList(
        threads = threads,
        loading = loading,
        configured = configured,
        error = emailError,
        onOpen = viewModel::openEmailThread,
        onCompose = { viewModel.startMail(MailDraft()) },
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
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    ScreenScaffold(
        title = "Inbox",
        modifier = modifier,
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

            loading && threads.isEmpty() -> ListSkeleton(
                rows = 5,
                lines = 2,
                caption = "Reading your mail…",
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
                    ThreadRow(thread, nowEpochMs) { onOpen(thread) }
                }
            }
        }
    }
}

@Composable
private fun ThreadRow(thread: EmailThread, nowEpochMs: Long, onClick: () -> Unit) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Touch.minTarget)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.x2),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        // A dot rather than bolding the whole row. Unread is one bit of information
        // and it should cost one glyph, not a second typographic weight competing
        // with the subject for attention.
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(if (thread.unread) colors.accent else colors.bgApp)
        )

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
            ) {
                Text(
                    text = senderName(thread.from),
                    style = type.label.copy(
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
                    color = colors.textFaint,
                    maxLines = 1,
                )
            }

            Text(
                text = thread.subject,
                style = type.body,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = thread.snippet,
                style = type.meta,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    ScreenScaffold(
        // The subject is the title. It was already the only thing in the old header
        // besides the way out, and a reader whose title is the thing being read is
        // the same shape the file reader now uses.
        title = notes.firstOrNull()?.subject.orEmpty().ifBlank { "No subject" },
        modifier = modifier,
        // How many, not who from. Every message below already names its own
        // sender, and on a one-message thread a subtitle naming the sender would
        // be the same name twice, an inch apart.
        subtitle = if (notes.size > 1) "${notes.size} messages" else null,
        leading = { SecondaryButton(label = "Back", onClick = onClose) },
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
            for (note in notes) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                    Text(
                        text = senderName(note.from),
                        style = type.bodyEmphasis,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
