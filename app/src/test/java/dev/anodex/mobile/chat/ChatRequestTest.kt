package dev.anodex.mobile.chat

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of a turn on the wire, and the one field whose absence means something.
 *
 * A plain chat started on the phone answered as though it were inside a project: asked
 * what colour red is, it offered to explain "how red renders in your Nebula2 scene".
 * The conversation had no project, was saved with none, and was filed under Chats
 * correctly — only the generation ran somewhere else.
 *
 * The cause was this request omitting `projectId` when there wasn't one. The desktop
 * reads an *absent* key as "use whatever project is open at the computer" and a *null*
 * one as "no project", so leaving it out did not mean what leaving it out looks like it
 * means. The desktop's own renderer always sends the field, which is why the fallback
 * had never fired there and the hazard was invisible from the computer.
 */
class ChatRequestTest {

    private fun request(projectId: String?) = chatRequest(
        conversationId = "c_1",
        messageId = "m_1",
        prompt = "what color is red?",
        history = buildJsonArray {},
        projectId = projectId,
    )

    @Test
    fun `a plain chat says no project rather than saying nothing`() {
        val sent = request(null)

        // Present and null. Absent would be read as "the active one" — the defect.
        assertTrue("projectId" in sent)
        assertEquals(JsonNull, sent["projectId"])
    }

    @Test
    fun `a project chat names its project`() {
        assertEquals("\"p_nebula2\"", request("p_nebula2")["projectId"]?.toString())
    }

    @Test
    fun `the required fields are the ones the desktop asks for`() {
        // `content` was rejected outright once; the contract says `prompt` and
        // `history`, and both are required whether or not there is anything in them.
        val sent = request(null)

        assertTrue(listOf("conversationId", "messageId", "prompt", "history").all { it in sent })
        assertFalse("content" in sent)
    }

    @Test
    fun `nothing attached sends no userFiles key at all`() {
        // An empty array and a missing key are the same to the desktop here, and the
        // missing key is the smaller frame.
        assertFalse("userFiles" in request(null))
    }

    @Test
    fun `an attachment travels as a path on the computer, not as bytes`() {
        val sent = chatRequest(
            conversationId = "c_1",
            messageId = "m_1",
            prompt = "what is this",
            history = buildJsonArray {},
            projectId = null,
            attachments = listOf(
                UploadedFile(
                    path = "C:/tmp/cat.png",
                    name = "cat.png",
                    sizeBytes = 1_024,
                    isImage = true,
                ),
            ),
        )

        assertEquals(
            "[{\"path\":\"C:/tmp/cat.png\",\"name\":\"cat.png\"}]",
            sent["userFiles"]?.toString(),
        )
    }
}
