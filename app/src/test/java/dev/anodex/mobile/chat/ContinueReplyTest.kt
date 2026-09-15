package dev.anodex.mobile.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reply that stopped partway can be continued, keeping what it did, rather than only
 * asked again. Seen when two website builds overflowed the model's memory: half a site
 * was written, and the only way on was to type "continue".
 */
class ContinueReplyTest {

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `a turn result that stopped for a reason other than the user ended early`() {
        assertTrue(endedEarlyFromResult(json("""{"ok":true,"value":{"content":"x","stopped":true,"stopReason":"context-limit"}}""")))
        assertTrue(endedEarlyFromResult(json("""{"content":"x","stopped":true,"stopReason":"provider-error"}""")))

        assertFalse(endedEarlyFromResult(json("""{"content":"x","stopped":true,"stopReason":"user"}""")))
        assertFalse(endedEarlyFromResult(json("""{"content":"x","stopped":false}""")))
        assertFalse(endedEarlyFromResult(json("""{"content":"x","stopped":true}""")))
        assertFalse(endedEarlyFromResult(null))
    }

    private fun reply(text: String, endedEarly: Boolean = true, streaming: Boolean = false) = ChatMessage(
        id = "a1",
        role = ChatMessage.Role.ASSISTANT,
        text = text,
        streaming = streaming,
        endedEarly = endedEarly,
    )

    @Test
    fun `only the newest reply that stopped partway having done something can continue`() {
        assertTrue(canContinue(reply("Wrote the stylesheet."), isNewest = true))
        val toolsOnly = reply("").copy(tools = listOf(ToolActivity("t", "write_file", "Write a.css", null, ToolActivity.Status.DONE)))
        assertTrue(canContinue(toolsOnly, isNewest = true))

        assertFalse(canContinue(reply("Wrote it."), isNewest = false))
        assertFalse(canContinue(reply("Done.", endedEarly = false), isNewest = true))
        assertFalse(canContinue(reply(""), isNewest = true))
        assertFalse(canContinue(reply("Wri", streaming = true), isNewest = true))
        assertFalse(canContinue(reply("hi").copy(role = ChatMessage.Role.USER), isNewest = true))
    }

    @Test
    fun `catching up with the computer does not forget that a reply stopped partway`() {
        val here = reply("Wrote the stylesheet.")
        assertTrue(keepWhatOnlyThisPhoneHas(here, here.copy(endedEarly = false)).endedEarly)
        assertTrue(keepWhatOnlyThisPhoneHas(here, here.copy(text = "Wrote the stylesheet. More", endedEarly = false)).endedEarly)
    }
}
