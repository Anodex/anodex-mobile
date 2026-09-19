package dev.anodex.mobile.email

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the undo strip says, which is the only record of what just happened.
 *
 * The strip is on screen for six seconds and then the mail is gone from the
 * list with nothing left to explain it, so the sentence has to carry the whole
 * decision: what happened, and to what.
 */
class MovedMailTest {

    private fun thread(subject: String) = EmailThread(
        id = subject,
        accountId = "a1",
        subject = subject,
        from = "Ada <ada@example.com>",
        snippet = "",
        updatedAtEpochMs = 0,
        unread = false,
        starred = false,
        attachmentCount = 0,
        messageCount = 1,
    )

    @Test
    fun `one message is named`() {
        // The name is the point. "Archived." tells somebody what happened;
        // naming it tells them whether it was the one they meant, which is the
        // question Undo actually answers.
        assertEquals(
            "Archived “Quarterly report”.",
            movedMailText("Archived", listOf(thread("Quarterly report"))),
        )
    }

    @Test
    fun `several are counted`() {
        assertEquals(
            "Deleted 3 messages.",
            movedMailText("Deleted", listOf(thread("a"), thread("b"), thread("c"))),
        )
    }

    @Test
    fun `two is still counted, not listed`() {
        // The boundary worth naming: two subjects would fit, and still are not
        // shown. Anybody who selected two knows which two, and a strip that
        // sometimes lists and sometimes counts is one more thing to read.
        assertEquals("Archived 2 messages.", movedMailText("Archived", listOf(thread("a"), thread("b"))))
    }

    @Test
    fun `the verb is not assumed`() {
        // Archived and Deleted are not interchangeable to somebody deciding
        // whether to reach for Undo, so neither is baked in here.
        assertEquals("Deleted “Lunch”.", movedMailText("Deleted", listOf(thread("Lunch"))))
    }

    @Test
    fun `nothing moved says only the verb`() {
        // Unreachable through the interface, because the strip is not drawn for
        // an empty list. Pinned anyway so the function cannot produce
        // "Archived 0 messages." if a future caller forgets to check.
        assertEquals("Archived", movedMailText("Archived", emptyList()))
    }
}
