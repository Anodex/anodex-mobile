package dev.anodex.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anodex.mobile.AnodexViewModel
import dev.anodex.mobile.connection.ConnectionState
import dev.anodex.mobile.email.EmailNote
import dev.anodex.mobile.email.EmailThread
import dev.anodex.mobile.ui.components.SecondaryButton
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

    if (openThread != null) {
        BackHandler { viewModel.closeEmailThread() }
        ThreadReader(
            notes = openThread.orEmpty(),
            loading = threadLoading,
            onClose = viewModel::closeEmailThread,
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
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(modifier.fillMaxSize().background(colors.bgApp)) {
        Text(
            text = "Inbox",
            style = type.heading,
            color = colors.text,
            modifier = Modifier.padding(horizontal = Spacing.x4, vertical = Spacing.x3),
        )

        when {
            // First, because every state under this one is a statement about the
            // mailbox, and none of them can be made when the mailbox was not reached.
            // This used to fall through to "No email account is connected on your
            // computer" — the most confident wrong sentence in the app.
            error != null && threads.isEmpty() -> Notice(error)

            loading && threads.isEmpty() -> Notice("Reading your mail…")

            // Not yet asked — the socket was down when this tab opened. Saying
            // "nothing in the inbox" here would be a claim the app has no basis for,
            // and the user would believe it.
            configured == null -> Notice("Waiting for your computer…")

            // Told apart on purpose: an inbox with nothing in it and an inbox that
            // does not exist look identical in a list and need opposite words.
            configured == false -> Notice(
                "No email account is connected on your computer. Connect one there and " +
                    "it will show up here."
            )

            threads.isEmpty() -> Notice("Nothing in the inbox.")

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = Spacing.x4,
                    vertical = Spacing.x2,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.x2),
            ) {
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
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(modifier.fillMaxSize().background(colors.bgApp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.x3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
        ) {
            SecondaryButton(label = "Back", onClick = onClose)
            Text(
                text = notes.firstOrNull()?.subject.orEmpty(),
                style = type.bodyEmphasis,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        if (loading && notes.isEmpty()) {
            Notice("Opening…")
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.x4, vertical = Spacing.x2),
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
                    Text(text = note.body, style = type.body, color = colors.textMuted)
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = type.body,
            color = colors.textFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(Spacing.x6),
        )
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
