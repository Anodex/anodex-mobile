package dev.anodex.mobile.chat

import dev.anodex.mobile.workspace.ChangedFile
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `markdown in a pasted prompt does not reach the title`() {
        // Seen in the drawer as "Yes. Here is the **single combined master pr…" — a
        // prompt pasted from another assistant, bold marks and all.
        val pasted = "Yes. Here is the **single combined master prompt**, with `refs`"

        assertEquals(
            "Yes. Here is the single combined master prompt, with refs",
            titleToSave(null, listOf(user(pasted))),
        )
    }

    @Test
    fun `a heading or bullet prefix is dropped, and a line of only marks is skipped`() {
        assertEquals("Plan the release", titleToSave(null, listOf(user("## Plan the release"))))
        assertEquals("Plan the release", titleToSave(null, listOf(user("- Plan the release"))))
        assertEquals("Plan the release", titleToSave(null, listOf(user("****\nPlan the release"))))
    }

    @Test
    fun `asterisks that are not emphasis are left alone`() {
        assertEquals("2 * 3 * 4 is 24", titleToSave(null, listOf(user("2 * 3 * 4 is 24"))))
    }

    @Test
    fun `the title the computer generates is read from a bare string or an envelope`() {
        val bare = JsonPrimitive("Write Nebula Prompt")
        val wrapped = buildJsonObject { put("ok", true); put("value", "Write Nebula Prompt") }

        assertEquals("Write Nebula Prompt", titleFromReply(bare))
        assertEquals("Write Nebula Prompt", titleFromReply(wrapped))
    }

    @Test
    fun `no generated title leaves the first-line title in place`() {
        // `chat:title` answers null with no model loaded, or when the model's output
        // was unusable. That must not become a title of "null".
        assertNull(titleFromReply(JsonNull))
        assertNull(titleFromReply(null))
        assertNull(titleFromReply(JsonPrimitive("   ")))
    }

    @Test
    fun `the title request carries the files the reply changed`() {
        val question = user("Fix the jitter")
        val reply = ChatMessage(
            "1:reply",
            ChatMessage.Role.ASSISTANT,
            "Done.",
            changedFiles = listOf(ChangedFile("src/orbit.ts", "modified", 10, 12, false)),
        )

        val request = titleRequest(question, reply)

        assertEquals("Fix the jitter", request["userPrompt"]?.jsonPrimitive?.content)
        assertEquals("Done.", request["assistantReply"]?.jsonPrimitive?.content)
        assertEquals("src/orbit.ts", request["editedFiles"]?.jsonArray?.single()?.jsonPrimitive?.content)
    }
}
