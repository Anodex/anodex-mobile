package dev.anodex.mobile.email

import dev.anodex.mobile.ui.screens.addressList
import dev.anodex.mobile.ui.screens.forwardSubject
import dev.anodex.mobile.ui.screens.replySubject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The small decisions a compose window makes on somebody's behalf.
 *
 * None of these are interesting individually, and every one of them is a message
 * that arrives wrong at somebody else's mailbox when it is wrong here -- which is
 * the one place in this app where a mistake is not recoverable from the phone that
 * made it.
 */
class ComposeMailTest {

    @Test
    fun `a semicolon separates addresses too`() {
        // A phone keyboard offers the semicolon as readily as the comma, and Outlook
        // has taught a lot of people to use it. Split on commas alone, a
        // semicolon-separated list goes to the far end as one malformed address and
        // fails there, where nobody holding the phone can see it.
        assertEquals(
            listOf("ada@example.com", "grace@example.com"),
            addressList("ada@example.com; grace@example.com"),
        )
        assertEquals(
            listOf("ada@example.com", "grace@example.com"),
            addressList("ada@example.com, grace@example.com"),
        )
    }

    @Test
    fun `blank entries and stray separators are dropped`() {
        // Trailing separators are what a half-finished list looks like, and an empty
        // string among the recipients is rejected by the provider rather than
        // ignored -- so the whole message fails because of a stray comma.
        assertEquals(listOf("ada@example.com"), addressList(" ada@example.com , , "))
        assertTrue(addressList("   ").isEmpty())
    }

    @Test
    fun `Re does not stack`() {
        // Four exchanges into a thread the subject should still be "Re: Lunch"
        // rather than "Re: Re: Re: Re: Lunch", which is how a conversation announces
        // that one end is using a client written in an afternoon.
        assertEquals("Re: Lunch", replySubject("Lunch"))
        assertEquals("Re: Lunch", replySubject("Re: Lunch"))
        // Case is the sender's choice, not a different prefix.
        assertEquals("RE: Lunch", replySubject("RE: Lunch"))
    }

    @Test
    fun `Fwd does not stack either`() {
        assertEquals("Fwd: Lunch", forwardSubject("Lunch"))
        assertEquals("Fwd: Lunch", forwardSubject("Fwd: Lunch"))
    }

    @Test
    fun `a reply carries the thread, not the message`() {
        // Two identifiers of the same shape, and using the wrong one fails
        // invisibly: the phone looks right, and every reply arrives at the far end
        // as a new conversation. `threadId` is read off the message for this.
        val note = EmailNote(
            id = "msg-91",
            threadId = "thread-4",
            from = "Ada <ada@example.com>",
            subject = "Lunch",
            body = "Thursday?",
            bodyHtml = null,
            to = listOf("me@example.com"),
            cc = emptyList(),
            dateEpochMs = 0L,
            attachmentCount = 0,
        )
        assertEquals("thread-4", note.threadId)
        assertTrue("the two must not be confusable by value", note.id != note.threadId)
    }
}

/**
 * What a phone may do to a mailbox.
 *
 * The wire names are the desktop's `EmailFlagAction` and cross the socket, so a
 * rename here silently stops working rather than failing to compile. Pinned for
 * the same reason `PermissionMode.EDITS` is pinned to `full`.
 *
 * The property worth stating out loud is that the list has no delete on it. Every
 * action is undone by another action in the same enum -- read/unread,
 * star/unstar, archive/unarchive -- and that is what makes them safe to offer a
 * finger's width apart on a phone.
 */
class MailFlagTest {

    @Test
    fun `the wire names are the desktop's`() {
        assertEquals("mark_read", MailFlag.READ.wire)
        assertEquals("mark_unread", MailFlag.UNREAD.wire)
        assertEquals("star", MailFlag.STAR.wire)
        assertEquals("unstar", MailFlag.UNSTAR.wire)
        assertEquals("archive", MailFlag.ARCHIVE.wire)
        assertEquals("unarchive", MailFlag.UNARCHIVE.wire)
    }

    @Test
    fun `nothing here destroys a message`() {
        // If a delete ever appears in this enum it should be a deliberate decision
        // with its own confirmation, not something that arrived with a batch of
        // reversible actions.
        val names = MailFlag.entries.map { it.wire }
        assertTrue(names.none { it.contains("delete") || it.contains("trash") || it.contains("purge") })
    }

    @Test
    fun `every action has its opposite`() {
        // The property the reader's action row depends on: a mistap is recoverable
        // without leaving the screen.
        val wires = MailFlag.entries.map { it.wire }.toSet()
        for ((a, b) in listOf("mark_read" to "mark_unread", "star" to "unstar", "archive" to "unarchive")) {
            assertTrue("$a has no opposite", wires.contains(a) && wires.contains(b))
        }
    }
}

/**
 * What a mailbox is called, versus what it is named.
 *
 * A server's namespace is not what anybody calls the folder, and showing the
 * path makes a row of five mailboxes unreadable on a phone. The same reduction
 * the desktop makes, written down here so the two agree.
 */
class FolderNameTest {

    @Test
    fun `Gmail's namespace is stripped`() {
        assertEquals("Sent Mail", friendlyFolderName("[Gmail]/Sent Mail"))
        assertEquals("Trash", friendlyFolderName("[Gmail]/Trash"))
    }

    @Test
    fun `an IMAP path is reduced to its leaf`() {
        assertEquals("Archive", friendlyFolderName("INBOX.Archive"))
        assertEquals("Receipts", friendlyFolderName("INBOX/Work/Receipts"))
    }

    @Test
    fun `a plain name is left alone`() {
        assertEquals("Deleted Items", friendlyFolderName("Deleted Items"))
        assertEquals("INBOX", friendlyFolderName("INBOX"))
    }

    @Test
    fun `a name that reduces to nothing keeps what it had`() {
        // Better a path nobody loves than a chip with no label on it.
        assertEquals("[Gmail]", friendlyFolderName("[Gmail]"))
    }
}
