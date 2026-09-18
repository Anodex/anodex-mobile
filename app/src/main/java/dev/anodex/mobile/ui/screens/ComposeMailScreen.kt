package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.email.EmailNote
import dev.anodex.mobile.ui.components.AnodexCard
import dev.anodex.mobile.ui.components.AnodexTextField
import dev.anodex.mobile.ui.components.InlineProblem
import dev.anodex.mobile.ui.components.PrimaryButton
import dev.anodex.mobile.ui.components.ScreenScaffold
import dev.anodex.mobile.ui.components.SecondaryButton
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Spacing

/**
 * What is being written, and what it is an answer to.
 *
 * A reply keeps the threading headers -- without them the message arrives in the
 * recipient's client as an unrelated mail, which is the difference between a
 * conversation and a pile.
 */
data class MailDraft(
    val to: String = "",
    val cc: String = "",
    val subject: String = "",
    val body: String = "",
    /** The message being answered, when this is a reply or a forward. */
    val inReplyTo: EmailNote? = null,
    val threadId: String? = null,
    /** "Reply", "Reply all", "Forward" or "New message", for the title. */
    val kind: String = "New message",
)

/**
 * Writing a message from the phone.
 *
 * This did not exist, and the reason recorded for that was that sending mail
 * "deserves the desktop's compose surface and its approval step rather than a text
 * field bolted onto a list". The first half was a real argument and is honoured
 * here -- sending asks before it goes, because it cannot be taken back. The second
 * half meant you had to be sitting at your computer to answer an email, which is
 * the one thing a phone is unambiguously better at.
 *
 * Anodex can write it. That is the same capability the desktop has through a tool
 * the model calls mid-conversation, reached here by a button, and what comes back
 * lands in the body field as a draft rather than being sent -- the person who
 * presses Send is the person, every time.
 */
@Composable
fun ComposeMailScreen(
    draft: MailDraft,
    onClose: () -> Unit,
    onSend: (MailDraft) -> Unit,
    modifier: Modifier = Modifier,
    /** Ask Anodex for a body. Null hides it, for a computer that cannot answer. */
    onAskAnodex: ((MailDraft, String) -> Unit)? = null,
    /** True while the model is writing. */
    drafting: Boolean = false,
    /** What the model wrote, once it has. Replaces the body when it arrives. */
    drafted: String? = null,
    sending: Boolean = false,
    error: String? = null,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    var to by rememberSaveable(draft.to) { mutableStateOf(draft.to) }
    var cc by rememberSaveable(draft.cc) { mutableStateOf(draft.cc) }
    var subject by rememberSaveable(draft.subject) { mutableStateOf(draft.subject) }
    var body by rememberSaveable(draft.body) { mutableStateOf(draft.body) }

    // What to tell Anodex, kept apart from the body it writes. Merging the two
    // would mean the instruction is what gets sent if the draft never arrives.
    var instruction by rememberSaveable { mutableStateOf("") }
    var asking by rememberSaveable { mutableStateOf(false) }

    // Arrives once, and only over a body the person has not since edited -- a
    // draft that lands on top of typing is the kind of loss nobody forgives.
    var lastDrafted by rememberSaveable { mutableStateOf<String?>(null) }
    if (drafted != null && drafted != lastDrafted) {
        lastDrafted = drafted
        body = drafted
        asking = false
        instruction = ""
    }

    var confirming by rememberSaveable { mutableStateOf(false) }

    val ready = to.isNotBlank() && body.isNotBlank()

    ScreenScaffold(
        title = draft.kind,
        subtitle = draft.inReplyTo?.let { "to ${senderName(it.from)}" },
        modifier = modifier,
        leading = { SecondaryButton(label = "Cancel", onClick = onClose) },
    ) { topInset ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = Spacing.x4,
                    end = Spacing.x4,
                    top = topInset + Spacing.x2,
                    bottom = Spacing.x6,
                ),
            verticalArrangement = Arrangement.spacedBy(Spacing.x3),
        ) {
            if (error != null) InlineProblem(text = error)

            AnodexTextField(
                value = to,
                onValueChange = { to = it },
                placeholder = "To",
                modifier = Modifier.fillMaxWidth(),
            )

            // Only when there is something in it. A reply-all carries the original
            // recipients and they matter; an empty Cc on a new message is a field
            // to scroll past on a screen that is mostly fields.
            if (cc.isNotBlank() || draft.cc.isNotBlank()) {
                AnodexTextField(
                    value = cc,
                    onValueChange = { cc = it },
                    placeholder = "Cc",
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            AnodexTextField(
                value = subject,
                onValueChange = { subject = it },
                placeholder = "Subject",
                modifier = Modifier.fillMaxWidth(),
            )

            AnodexTextField(
                value = body,
                onValueChange = { body = it },
                placeholder = "Write your message",
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
            )

            if (onAskAnodex != null) {
                AnodexCard {
                    if (!asking) {
                        SecondaryButton(
                            label = if (body.isBlank()) "Have Anodex write it" else "Have Anodex rewrite it",
                            onClick = { if (!drafting) asking = true },
                        )
                    } else {
                        AnodexTextField(
                            value = instruction,
                            onValueChange = { instruction = it },
                            placeholder = if (draft.inReplyTo != null) {
                                "How to answer -- \"say yes, and ask when\""
                            } else {
                                "What to say"
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                            PrimaryButton(
                                label = if (drafting) "Writing…" else "Write it",
                                onClick = {
                                    if (drafting) return@PrimaryButton
                                    onAskAnodex(
                                        MailDraft(
                                            to = to,
                                            cc = cc,
                                            subject = subject,
                                            body = body,
                                            inReplyTo = draft.inReplyTo,
                                            threadId = draft.threadId,
                                            kind = draft.kind,
                                        ),
                                        instruction,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            SecondaryButton(
                                label = "Never mind",
                                onClick = { asking = false },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    Text(
                        text = "It writes a draft. You send it.",
                        style = type.meta,
                        color = colors.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Two taps, like Forget on a memory, and for a stronger reason: a
            // memory can be written again and a sent message cannot be recalled.
            PrimaryButton(
                label = when {
                    sending -> "Sending…"
                    confirming -> "Tap again to send"
                    else -> "Send"
                },
                onClick = {
                    if (!ready || sending) return@PrimaryButton
                    if (confirming) onSend(compose(to, cc, subject, body, draft)) else confirming = true
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // Said rather than shown by a greyed button. A button that looks
            // disabled tells you it will not work; this tells you what to do about
            // it, which on a form with three fields is the whole question.
            if (!ready) {
                Text(
                    text = if (to.isBlank()) {
                        "Needs someone to send it to."
                    } else {
                        "Needs something to say."
                    },
                    style = type.meta,
                    color = colors.textFaint,
                )
            }

            Text(
                text = "Sent from your computer, using your account there.",
                style = type.meta,
                color = colors.textFaint,
            )
        }
    }
}

private fun compose(
    to: String,
    cc: String,
    subject: String,
    body: String,
    draft: MailDraft,
): MailDraft = draft.copy(to = to, cc = cc, subject = subject, body = body)

/**
 * Addresses as typed, split on the separators people actually use.
 *
 * Commas and semicolons both, because a phone keyboard offers the second as
 * readily as the first and a semicolon-separated list sent as one address fails at
 * the far end with a message nobody sees.
 */
internal fun addressList(raw: String): List<String> =
    raw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

/** "Re: Lunch" without stacking a second "Re:" on a subject that has one. */
internal fun replySubject(subject: String): String =
    if (subject.trimStart().startsWith("Re:", ignoreCase = true)) subject else "Re: $subject"

/** The same for a forward, which is the other prefix mail clients agree on. */
internal fun forwardSubject(subject: String): String =
    if (subject.trimStart().startsWith("Fwd:", ignoreCase = true)) subject else "Fwd: $subject"

/**
 * The message being forwarded, quoted underneath an empty line to write above.
 *
 * Plain text rather than the HTML, because this is going into a text field
 * somebody is about to type in.
 */
internal fun forwardBody(note: EmailNote): String = buildString {
    appendLine()
    appendLine()
    appendLine("---------- Forwarded message ----------")
    appendLine("From: ${note.from}")
    appendLine("Subject: ${note.subject}")
    appendLine()
    append(note.body)
}

@Preview(name = "Compose - dark", showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun PreviewCompose() {
    AnodexTheme(darkTheme = true) {
        ComposeMailScreen(
            draft = MailDraft(
                to = "ada@example.com",
                subject = "Re: Thursday",
                kind = "Reply",
            ),
            onClose = {},
            onSend = {},
            onAskAnodex = { _, _ -> },
        )
    }
}
