package dev.anodex.mobile.chat

import dev.anodex.mobile.connection.ModelStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A reopened approval counts down to the computer's deadline, not a fresh five minutes.
 */
class ApprovalDeadlineTest {

    private fun parse(json: String, receivedAt: Long) =
        ToolApproval.from(Json.parseToJsonElement(json).jsonObject)!!.copy(receivedAtMs = receivedAt)

    @Test
    fun `the time the computer said is left is what is counted down`() {
        // Asked for after reconnecting, two minutes into its five.
        val approval = parse("""{"id":"a1","conversationId":"c1","title":"Write a.txt","expiresInMs":180000}""", 1_000_000)

        assertEquals(180, approval.secondsLeft(1_000_000))
        // Reopened a minute later: two minutes left, not five.
        assertEquals(120, approval.secondsLeft(1_060_000))
        assertEquals(0, approval.secondsLeft(1_200_000))
        assertEquals(0, approval.secondsLeft(9_999_999))
    }

    @Test
    fun `a computer that does not say gets the full five minutes from arrival`() {
        val approval = parse("""{"id":"a1","conversationId":"c1","title":"Write a.txt"}""", 1_000_000)

        assertNull(approval.expiresInMs)
        assertEquals(300, approval.secondsLeft(1_000_000))
        assertEquals(240, approval.secondsLeft(1_060_000))
    }

    @Test
    fun `a turn says it is sharing the model only when it is`() {
        val alone = ModelStatus("Qwen", contextUsedTokens = null, contextTotalTokens = 65_536, activeReplies = 1)
        assertNull(alone.sharingNote)
        assertNull(alone.copy(activeReplies = 0).sharingNote)
        assertEquals("sharing the model with another job", alone.copy(activeReplies = 2).sharingNote)
        assertEquals("sharing the model with 2 other jobs", alone.copy(activeReplies = 3).sharingNote)
    }
}
