package dev.anodex.mobile.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a reply costs in data, after the turn.
 *
 * Measured on the emulator: one two-hundred-word reply came to 370KB received and 97KB
 * sent. After the tokens, every save re-read the whole conversation list, and every
 * save uploaded the whole transcript again to add two turns to it.
 */
class PhoneDataTest {

    private fun turn(id: String, role: ChatMessage.Role = ChatMessage.Role.USER) = ChatMessage(id, role, "text $id")

    @Test
    fun `a save sends only the turns the computer does not have`() {
        val turns = listOf(turn("a"), turn("b"), turn("c"), turn("c:reply", ChatMessage.Role.ASSISTANT))

        assertEquals(listOf("c", "c:reply"), turnsToSave(turns, setOf("a", "b")).map { it.id })
    }

    @Test
    fun `a conversation never saved sends everything, since the computer may have none of it`() {
        val turns = listOf(turn("a"), turn("a:reply", ChatMessage.Role.ASSISTANT))

        assertEquals(turns, turnsToSave(turns, emptySet()))
    }

    @Test
    fun `a rename with nothing new still sends a turn to merge into`() {
        val turns = listOf(turn("a"), turn("b"))

        assertEquals(listOf("b"), turnsToSave(turns, setOf("a", "b")).map { it.id })
    }

    @Test
    fun `the thinking comes with a finished turn, inside its envelope or not`() {
        val wrapped = Json.parseToJsonElement("""{"ok":true,"value":{"content":"Yes.","thinking":"Hmm."}}""")
        val bare = Json.parseToJsonElement("""{"content":"Yes.","thinking":"Hmm."}""")

        assertEquals("Hmm.", thinkingFromResult(wrapped))
        assertEquals("Hmm.", thinkingFromResult(bare))
        assertNull(thinkingFromResult(Json.parseToJsonElement("""{"ok":true,"value":{"content":"Yes."}}""")))
    }

    @Test
    fun `saved thinking reads as text, or null for none`() {
        assertEquals("Hmm.", thinkingTextOf(JsonPrimitive("Hmm.")))
        assertNull(thinkingTextOf(JsonNull))
        assertNull(thinkingTextOf(null))
    }

    @Test
    fun `a changed row replaces its old self, and one no longer there leaves the list`() {
        fun row(id: String, updated: Long) = ConversationSummary(id, id, 0, updated, 2)
        val list = listOf(row("a", 30), row("b", 20), row("c", 10))

        val updated = withChangedRows(list, asked = setOf("c", "b"), changed = listOf(row("c", 40)))

        assertEquals(listOf("c", "a"), updated.map { it.id })
        assertEquals(40L, updated.first().updatedAtEpochMs)
    }

    @Test
    fun `a new conversation from the computer joins the list`() {
        fun row(id: String, updated: Long) = ConversationSummary(id, id, 0, updated, 2)

        val updated = withChangedRows(listOf(row("a", 30)), asked = setOf("new"), changed = listOf(row("new", 50)))

        assertEquals(listOf("new", "a"), updated.map { it.id })
    }

    @Test
    fun `a sync keeps the thinking and tool rows of a reply this phone watched finish`() {
        // Seen on the emulator: the thinking of a reply vanished the moment it ended,
        // replaced by the computer's copy, which carries none.
        val watched = ChatMessage(
            "q:reply",
            ChatMessage.Role.ASSISTANT,
            "Stripes.",
            tools = listOf(ToolActivity("t", "web_search", "Searched", null, ToolActivity.Status.DONE)),
            thinking = "Why stripes?",
        )
        val fromComputer = ChatMessage("q:reply", ChatMessage.Role.ASSISTANT, "Stripes.")

        assertEquals(watched, keepWhatOnlyThisPhoneHas(watched, fromComputer))
    }

    @Test
    fun `a sync takes the computer's words for a turn that changed there, and keeps the rest`() {
        val had = ChatMessage("r", ChatMessage.Role.ASSISTANT, "Half a rep", thinking = "Hm.")
        val fromComputer = ChatMessage("r", ChatMessage.Role.ASSISTANT, "Half a reply, finished.", hasThinking = true)

        val synced = keepWhatOnlyThisPhoneHas(had, fromComputer)

        assertEquals("Half a reply, finished.", synced.text)
        assertEquals("Hm.", synced.thinking)
        assertFalse(synced.hasThinking)
        assertEquals(fromComputer, keepWhatOnlyThisPhoneHas(null, fromComputer))
    }

    @Test
    fun `a conversation read in full is known to be whole, and a tail is not`() {
        fun open(json: String, limit: Int) =
            parseOpenedConversation(Json.parseToJsonElement(json).jsonObject, limit) {
                ChatMessage(it["id"].toString(), ChatMessage.Role.USER, "")
            }

        assertTrue(open("""{"id":"c","messages":[{"id":"a"},{"id":"b"}],"partial":false}""", 8).complete)
        assertFalse(open("""{"id":"c","messages":[{"id":"a"},{"id":"b"}],"partial":false}""", 2).complete)
        assertFalse(open("""{"id":"c","messages":[{"id":"a"}],"partial":true}""", 8).complete)
    }
}
