package dev.anodex.mobile.chat

import dev.anodex.mobile.notify.Notifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a "reply ready" notification shows, and which notification it replaces. */
class ReplyPreviewTest {

    @Test
    fun `a short reply is shown as it is, without markdown`() {
        assertEquals("Mars, Jupiter, and Saturn.", replyPreview("**Mars**, Jupiter, and Saturn."))
    }

    @Test
    fun `code fences and blank lines are left out`() {
        val reply = "Here is the fix:\n\n```kotlin\nval x = 1\n```\n\nThat should do it."
        assertEquals("Here is the fix: val x = 1 That should do it.", replyPreview(reply))
    }

    @Test
    fun `a long reply is cut on a word and says so`() {
        val preview = replyPreview("word ".repeat(100))

        assertTrue(preview.endsWith("…"))
        assertTrue(preview.length <= REPLY_PREVIEW_CHARS + 1)
        assertTrue(!preview.dropLast(1).endsWith(" "))
    }

    @Test
    fun `one notification per conversation, clear of the approval and run ids`() {
        val a = Notifications.replyNotificationId("conversation-a")
        assertEquals(a, Notifications.replyNotificationId("conversation-a"))
        assertNotEquals(a, Notifications.replyNotificationId("conversation-b"))
        assertTrue(a >= 10_000)
    }
}
