package dev.anodex.mobile.chat

import dev.anodex.mobile.ui.screens.memoryKindLabel
import dev.anodex.mobile.ui.screens.recalledSummary
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last two things the computer sends that this phone used to throw away.
 *
 * Found by the probe that found the citations: comparing every field of
 * `ChatResult` against what the phone actually reads. Of eighteen it read six.
 * `memoryUsed` and `transcriptRecallUsed` were two of the twelve, and both are
 * features the desktop has had for months — which memories were retrieved into a
 * turn, and which past conversations were pulled in.
 *
 * Worth carrying for the same reason as a web source: a reply shaped by a stored
 * fact and one written without it look identical, and only one of them can be
 * checked.
 */
class RecalledContextTest {

    private fun result(body: String) = Json.parseToJsonElement(body)

    @Test
    fun `memories are read off a wrapped answer`() {
        val entries = memoryUsedFromResult(
            result(
                """
                {"ok": true, "value": {"memoryUsed": [
                  {"id": "m1", "kind": "identity", "text": "Their name is Merlin."},
                  {"id": "m2", "kind": "preference", "text": "Prefers pull requests."}
                ]}}
                """.trimIndent(),
            ),
        )
        assertEquals(2, entries.size)
        assertEquals("identity", entries[0].kind)
        assertEquals("Their name is Merlin.", entries[0].text)
    }

    @Test
    fun `and off a bare one`() {
        // Both shapes have been seen on this channel. Reading only one returns
        // nothing, which is indistinguishable from a turn that recalled nothing.
        val entries = memoryUsedFromResult(
            result("""{"memoryUsed": [{"id": "m1", "kind": "gotcha", "text": "The port is 7331."}]}"""),
        )
        assertEquals(1, entries.size)
    }

    @Test
    fun `a memory with no text is dropped rather than drawn empty`() {
        val entries = memoryUsedFromResult(
            result("""{"memoryUsed": [{"id": "m1", "kind": "preference", "text": "  "}]}"""),
        )
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `a recalled conversation keeps its excerpts`() {
        val chats = recalledFromResult(
            result(
                """
                {"ok": true, "value": {"transcriptRecallUsed": [{
                  "conversationId": "c1", "title": "Deciding the port", "updatedAt": 1700000000000,
                  "excerpts": [{"role": "user", "text": "which port did we pick"},
                               {"role": "assistant", "text": "7331"}]
                }]}}
                """.trimIndent(),
            ),
        )
        assertEquals(1, chats.size)
        assertEquals("Deciding the port", chats[0].title)
        assertEquals(2, chats[0].excerpts.size)
        assertEquals(1700000000000L, chats[0].updatedAtEpochMs)
    }

    @Test
    fun `a conversation with nothing readable in it is not mentioned`() {
        // A title with nothing behind it is worse than silence: it claims the
        // answer drew on something and gives no way to check what.
        val chats = recalledFromResult(
            result("""{"transcriptRecallUsed": [{"conversationId": "c1", "title": "Empty", "excerpts": []}]}"""),
        )
        assertTrue(chats.isEmpty())
    }

    @Test
    fun `an untitled conversation still says something`() {
        val chats = recalledFromResult(
            result(
                """{"transcriptRecallUsed": [{"conversationId": "c1",
                   "excerpts": [{"role": "user", "text": "hello"}]}]}""",
            ),
        )
        assertEquals("A past conversation", chats[0].title)
    }

    @Test
    fun `the summary counts rather than lists`() {
        assertEquals("Recalled a memory", recalledSummary(1, 0))
        assertEquals("Recalled 2 memories", recalledSummary(2, 0))
        assertEquals("Recalled a past chat", recalledSummary(0, 1))
        assertEquals("Recalled 2 memories and 3 past chats", recalledSummary(2, 3))
    }

    @Test
    fun `an unfamiliar kind is shown rather than hidden`() {
        // A kind added on the computer and not known here. Shown as it arrived,
        // because an unfamiliar label is information and a blank is not.
        assertEquals("Identity", memoryKindLabel("identity"))
        assertEquals("Open task", memoryKindLabel("open_task"))
        assertEquals("Something_new", memoryKindLabel("something_new"))
    }

    @Test
    fun `a sync does not blank what the phone already has`() {
        // The same lesson as the web sources, one layer in: a conversation saved
        // before these were recorded comes back without them, and taking the
        // computer's empty list on faith would blank a reply somebody is reading.
        val had = ChatMessage(
            id = "m1",
            role = ChatMessage.Role.ASSISTANT,
            text = "as this phone has it",
            memoryUsed = listOf(RecalledMemory("m1", "identity", "Their name is Merlin.")),
        )
        val computer = had.copy(text = "edited at the computer", memoryUsed = emptyList())
        assertEquals(1, keepWhatOnlyThisPhoneHas(had, computer).memoryUsed.size)
    }
}
