package dev.anodex.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the phone writes into a conversation's title when it saves.
 *
 * Saving sends the *whole* conversation back, so anything the phone does not carry
 * through is silently replaced on the computer. That has now caught this file twice:
 * `createdAt` was being stamped to "now" on a conversation started days earlier, and
 * the title was being rewritten from the first user turn.
 *
 * The title case is the more visible of the two. The desktop summarises a finished
 * turn into a real title, asynchronously — so opening one of those on the phone and
 * sending a single message renamed it, on the computer, to the first sixty
 * characters of the first thing the user ever typed.
 */
class ConversationTitleTest {

    private fun user(text: String) = ChatMessage("1", ChatMessage.Role.USER, text)

    private val turns = listOf(user("Fix the jitter when dragging a body past the sun"))

    @Test
    fun `the computer's own title is kept`() {
        assertEquals(
            "Fix the orbit panel jitter",
            titleToSave("Fix the orbit panel jitter", turns),
        )
    }

    @Test
    fun `a conversation with no title yet gets one from the first turn`() {
        // Normal for the minute or two before the desktop summarises it. This beats
        // a row in the list reading "Untitled".
        assertEquals(
            "Fix the jitter when dragging a body past the sun",
            titleToSave(null, turns),
        )
    }

    @Test
    fun `a blank stored title is treated as no title`() {
        // The desktop stores "" before it has summarised. Saving that straight back
        // would leave the conversation permanently blank in both apps.
        assertEquals(turns.first().text, titleToSave("", turns))
        assertEquals(turns.first().text, titleToSave("   ", turns))
    }

    @Test
    fun `the assistant's reply is never the title`() {
        // Titles come from what the user asked, not what the model said.
        val conversation = listOf(
            ChatMessage("a", ChatMessage.Role.ASSISTANT, "Found it, in useDragBody."),
            user("Why does it jitter?"),
        )

        assertEquals("Why does it jitter?", titleToSave(null, conversation))
    }

    @Test
    fun `a long first line is cut rather than wrapped`() {
        val long = "a".repeat(200)

        val title = titleToSave(null, listOf(user(long)))

        assertEquals(MAX_TITLE_LENGTH + 1, title.length) // the ellipsis
        assertEquals(true, title.endsWith("…"))
    }

    @Test
    fun `only the first line is used`() {
        val pasted = "Fix the jitter\n\nSteps:\n1. drag a body\n2. cross the sun"

        assertEquals("Fix the jitter", titleToSave(null, listOf(user(pasted))))
    }

    @Test
    fun `leading blank lines are skipped rather than becoming the title`() {
        // Pasted text often starts with a newline. Taking it literally produced
        // "New chat" for a message that plainly had one.
        assertEquals("Fix the jitter", titleToSave(null, listOf(user("\n\n  Fix the jitter\nmore"))))
    }

    @Test
    fun `an empty conversation falls back rather than crashing`() {
        assertEquals("New chat", titleToSave(null, emptyList()))
        assertEquals("New chat", titleToSave(null, listOf(user("   "))))
    }
}
