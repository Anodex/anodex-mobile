package dev.anodex.mobile.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading `chat:working`, the desktop saying a quiet turn is still alive.
 *
 * Reproduced on the test phone: a question sent while an agent run held the model
 * showed "Thinking…" for five minutes, then gave up. The event is what lets the
 * phone say "waiting for your computer" instead, and keep waiting.
 */
class WorkingPhaseTest {

    private fun event(json: String) = Json.parseToJsonElement(json)

    @Test
    fun `a turn queued behind the model reads as waiting`() {
        assertEquals(
            WorkingPhase.WAITING_FOR_MODEL,
            workingPhase(event("""{"conversationId":"c1","messageId":"m1","phase":"waiting-for-model","since":1}"""), "c1"),
        )
    }

    @Test
    fun `a turn under way reads as working, and so does a phase this build does not know`() {
        assertEquals(WorkingPhase.WORKING, workingPhase(event("""{"conversationId":"c1","phase":"working"}"""), "c1"))
        assertEquals(WorkingPhase.WORKING, workingPhase(event("""{"conversationId":"c1","phase":"compacting"}"""), "c1"))
    }

    @Test
    fun `another conversation's heartbeat is not this one's`() {
        assertNull(workingPhase(event("""{"conversationId":"c2","phase":"waiting-for-model"}"""), "c1"))
    }

    @Test
    fun `an unreadable event is ignored`() {
        assertNull(workingPhase(JsonNull, "c1"))
        assertNull(workingPhase(null, "c1"))
    }
}
