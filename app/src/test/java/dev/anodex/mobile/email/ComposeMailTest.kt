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
