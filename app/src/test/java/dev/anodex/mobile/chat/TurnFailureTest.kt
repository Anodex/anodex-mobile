package dev.anodex.mobile.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading `chat:send`'s answer when the computer could not produce a reply.
 *
 * Measured on a Galaxy S7: sent with no model loaded, the computer answered
 * `{ ok: false, error }` and the phone took that for a finished turn — the message sat
 * there with no reply and no reason.
 */
class TurnFailureTest {

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun `no model loaded is said plainly, and marked so the phone can offer to choose one`() {
        val reason = turnFailureOf(
            json(
                """{"ok":false,"error":{"code":"chat.generation-failed",""" +
                    """"message":"No model is loaded. Load one in Settings → AI & Models first."}}"""
            )
        )
        assertEquals("No model is loaded on the computer. Choose one, then try again.", reason!!.message)
        assertTrue(reason.noModel)
    }

    @Test
    fun `any other failure says what the computer said`() {
        val reason = turnFailureOf(
            json("""{"ok":false,"error":{"code":"chat.context-too-small","message":"The input is too large."}}""")
        )
        assertEquals(TurnFailure("The input is too large."), reason)
    }

    @Test
    fun `a failure with no message still says something`() {
        assertEquals(TurnFailure("Your computer couldn't answer that."), turnFailureOf(json("""{"ok":false}""")))
    }

    @Test
    fun `a finished turn is not a failure, wrapped or not`() {
        assertNull(turnFailureOf(json("""{"ok":true,"value":{"content":"Hi"}}""")))
        assertNull(turnFailureOf(json("""{"content":"Hi","stopped":false}""")))
        assertNull(turnFailureOf(null))
    }
}
