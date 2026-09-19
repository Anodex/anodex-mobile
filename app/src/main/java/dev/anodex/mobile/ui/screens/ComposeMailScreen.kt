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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Touch
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.email.EmailNote
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.components.Hairline
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
    /** The address this goes out as. Empty hides the row rather than lying. */
    fromAddress: String = "",
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

    // Leaving asks too, once there is anything to lose.
    //
    // Send takes two taps because it cannot be undone. Cancel could not be undone
    // either and took one -- a mistap on a narrow header, or a back gesture from
    // the edge of the screen, and a written message was gone with nothing to
    // recover it from. The asymmetry was the bug: the same loss guarded on one
    // side of the screen and not the other.
    var leaving by rememberSaveable { mutableStateOf(false) }
    val written = to.isNotBlank() || subject.isNotBlank() || body.isNotBlank()

    // The back gesture is the same act as Cancel and gets the same question.
    BackHandler(enabled = written && !leaving) { leaving = true }

    // The computer requires all three. `validateDraftRequest` on the desktop
    // rejects an empty subject outright, so letting Send be pressed without one
    // would mean the phone offering an action the computer then refuses -- an
    // error message where a greyed button belonged.
    val ready = to.isNotBlank() && subject.isNotBlank() && body.isNotBlank()

    ScreenScaffold(
        title = draft.kind,
        // The address as the subtitle rather than a row of its own, which is
        // what Outlook does and is right on a screen this size: it is a
        // constant, not a field, and it was taking a seventh of the window to
        // say one unchanging thing. Who a reply is going to lives in the To
        // field a few pixels below, where it can be edited.
        subtitle = fromAddress.takeIf { it.isNotBlank() }
            ?: draft.inReplyTo?.let { "to ${senderName(it.from)}" },
        modifier = modifier,
        // A back arrow, not a bordered button labelled Cancel.
        //
        // Every other control in this bar became an icon, and one filled
        // rectangle among them read as the important thing on the screen --
        // which leaving is not. The confirmation moved to the line at the
        // bottom, beside the one Send uses, so both of the acts that cannot be
        // taken back are explained in the same place in the same voice.
        leading = {
            ComposerAction(
                icon = AnodexIcon.CHEVRON_LEFT,
                label = if (leaving) "Tap again to discard" else "Close",
                tint = if (leaving) colors.dangerInk else colors.textMuted,
            ) { if (!written || leaving) onClose() else leaving = true }
        },
        // Send sits in the chrome, where every mail client on a phone puts it,
        // rather than at the bottom of a scrolling column. The old one was a
        // full-width button below the message: it moved as the body grew, it
        // competed with "Have Anodex write it" for the same corner, and on a
        // long draft you had to scroll past your own writing to reach it.
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                if (onAskAnodex != null) {
                    ComposerAction(
                        icon = AnodexIcon.PENCIL,
                        label = if (body.isBlank()) "Have Anodex write it" else "Have Anodex rewrite it",
                        tint = if (asking) colors.accentInk else colors.textMuted,
                    ) { if (!drafting) asking = !asking }
                }
                ComposerAction(
                    icon = AnodexIcon.SEND,
                    label = when {
                        sending -> "Sending"
                        confirming -> "Tap again to send"
                        else -> "Send"
                    },
                    // Three states in one control, because it has three. Faint
                    // until the message could go at all, the mark's colour once
                    // it could, and the full accent while it is armed -- which
                    // is the tap that cannot be taken back.
                    tint = when {
                        !ready || sending -> colors.textFaint
                        confirming -> colors.accent
                        else -> colors.accentInk
                    },
                ) {
                    if (!ready || sending) return@ComposerAction
                    if (confirming) onSend(compose(to, cc, subject, body, draft))
                    else confirming = true
                }
            }
        },
    ) { topInset ->
        // Fills rather than scrolls.
        //
        // A compose window is not a document to be read through: the addressing
        // is three short lines and everything after it is one field that should
        // have the rest of the screen. Scrolling the whole column meant the body
        // had to be given an arbitrary minimum height, which pushed the line
        // saying what was missing off the bottom -- so the one sentence
        // explaining why Send does nothing was the one thing you had to go
        // looking for. The body scrolls inside itself when it outgrows the room.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = Spacing.x4,
                    end = Spacing.x4,
                    top = topInset + Spacing.x2,
                    bottom = Spacing.x4,
                ),
            verticalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            if (error != null) InlineProblem(text = error)

            // Rows separated by a hairline, not three bordered boxes stacked up.
            //
            // The boxes were the whole problem with this screen: four outlined
            // rectangles and a card, each drawing a frame around one line of
            // text, so the chrome outweighed the message and the body -- the only
            // part anybody is here to write -- got the same weight as Cc. A mail
            // client gives the addressing one quiet line each and hands the rest
            // of the screen to the writing.
            ComposerRow(label = "To") {
                ComposerField(
                    value = to,
                    onValueChange = { to = it },
                    placeholder = "Someone",
                )
            }

            // Only when there is something in it. A reply-all carries the original
            // recipients and they matter; an empty Cc on a new message is a field
            // to scroll past on a screen that is mostly fields.
            if (cc.isNotBlank() || draft.cc.isNotBlank()) {
                ComposerRow(label = "Cc") {
                    ComposerField(
                        value = cc,
                        onValueChange = { cc = it },
                        placeholder = "Nobody else",
                    )
                }
            }

            ComposerRow(label = "Subject") {
                ComposerField(
                    value = subject,
                    onValueChange = { subject = it },
                    placeholder = "What it is about",
                )
            }

            // The model offered inside the empty body, not only as an icon.
            //
            // Both Gmail and Outlook do this and the reason is discoverability:
            // "Draft with Copilot" and "Help me write" sit in the placeholder,
            // where somebody looking at an empty message is already looking.
            // An icon in the chrome is a thing you find once you know it is
            // there. The icon stays, because it is how you ask for a *rewrite*
            // once there is something to rewrite and no placeholder left.
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = Spacing.x2),
            ) {
                ComposerField(
                    value = body,
                    onValueChange = { body = it },
                    placeholder = "",
                    modifier = Modifier.fillMaxSize(),
                )
                if (body.isEmpty() && !asking) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (onAskAnodex == null) "Write your message" else "Write your message, or ",
                            style = type.body,
                            color = colors.textFaint,
                        )
                        if (onAskAnodex != null) {
                            Text(
                                text = "have Anodex write it",
                                style = type.body,
                                color = colors.accentInk,
                                modifier = Modifier
                                    .clip(Radii.sm)
                                    .clickable { if (!drafting) asking = true },
                            )
                        }
                    }
                }
            }

            // The model, when it has been asked for, as one line under the
            // message rather than a card beside it.
            //
            // It was a bordered card holding a button, a field and two more
            // buttons -- a quarter of the screen given to a thing that writes
            // one paragraph. The button that opens it now lives in the chrome
            // with Send, and this appears only once it has been pressed.
            if (onAskAnodex != null && asking) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                    ComposerField(
                        value = instruction,
                        onValueChange = { instruction = it },
                        placeholder = if (draft.inReplyTo != null) {
                            "How to answer \u2014 \"say yes, and ask when\""
                        } else {
                            "What it should say"
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
                    ) {
                        PrimaryButton(
                            label = if (drafting) "Writing\u2026" else "Write it",
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
                            modifier = Modifier.weight(1f),
                        )
                        SecondaryButton(
                            label = "Never mind",
                            onClick = { asking = false },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = "It writes a draft. You send it.",
                        style = type.meta,
                        color = colors.textFaint,
                    )
                }
            }

            // What is missing, in words, under the fields it is missing from.
            //
            // A greyed Send tells you it will not work; this tells you what to
            // do about it, which on a form with three fields is the whole
            // question. It stays said rather than shown because the control is
            // now an icon in the corner, and an icon can carry a state but not a
            // sentence.
            // One line, at the foot of the window, saying whichever of these is
            // true. Both of the taps that cannot be taken back are explained
            // here rather than one on a button and one in the middle of the
            // page, which is where the discard warning used to sit.
            when {
                leaving -> Text(
                    text = "Tap back again to throw this message away.",
                    style = type.meta,
                    color = colors.dangerInk,
                )

                !ready -> Text(
                    text = when {
                        to.isBlank() -> "Needs someone to send it to."
                        subject.isBlank() -> "Needs a subject."
                        else -> "Needs something to say."
                    },
                    style = type.meta,
                    color = colors.textFaint,
                )

                confirming && !sending -> Text(
                    text = "Tap send again and it goes.",
                    style = type.meta,
                    color = colors.accentInk,
                )

                sending -> Text(text = "Sending\u2026", style = type.meta, color = colors.textMuted)

                else -> Text(
                    text = "Sent by your computer, from $fromAddress.".takeIf {
                        fromAddress.isNotBlank()
                    } ?: "Sent by your computer.",
                    style = type.meta,
                    color = colors.textFaint,
                )
            }
        }
    }
}

/**
 * One addressing line: a quiet label, the field, a hairline under it.
 *
 * The label sits beside rather than above, because three stacked
 * label-over-field pairs is most of a phone screen spent on three short
 * strings, and the message is the thing that needed the room.
 */
@Composable
private fun ComposerRow(label: String, content: @Composable () -> Unit) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.x3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
        ) {
            Text(
                text = label,
                style = type.meta,
                color = colors.textFaint,
                modifier = Modifier.width(64.dp),
            )
            Box(Modifier.weight(1f)) { content() }
        }
        Hairline()
    }
}

/**
 * Text with no box around it.
 *
 * `AnodexTextField` draws a filled, outlined field, which is right on a settings
 * page and wrong five times in a row on a compose window. The separation here is
 * the hairline under each row, which is what a mail client uses and what leaves
 * the body looking like a page rather than another input.
 */
@Composable
private fun ComposerField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        textStyle = type.body.copy(color = colors.text),
        cursorBrush = SolidColor(colors.accent),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(text = placeholder, style = type.body, color = colors.textFaint)
            }
            inner()
        },
    )
}

/** An icon in the composer's chrome, with a finger's worth of target around it. */
@Composable
private fun ComposerAction(
    icon: AnodexIcon,
    label: String,
    tint: Color,
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
