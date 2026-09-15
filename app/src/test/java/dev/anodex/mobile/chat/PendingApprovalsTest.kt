package dev.anodex.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An approval that arrived while its chat was not open is still there when it opens.
 *
 * Seen on the emulator: "Anodex needs an answer" opened a chat with no card, and the
 * turn waited until it was declined after five minutes.
 */
class PendingApprovalsTest {

    private fun approval(id: String, conversationId: String) = ToolApproval(
        id = id,
        conversationId = conversationId,
        toolName = "replace_lines",
        title = "Replace utils.js lines 85-89",
        detail = null,
        risk = ToolApproval.Risk.SENSITIVE,
        turnGate = false,
        hasUnshownDetail = false,
    )

    @Test
    fun `an approval is found by its conversation`() {
        val pending = PendingApprovals()
        pending.onRequest(approval("a1", "c1"))

        assertEquals("a1", pending.forConversation("c1")?.id)
        assertNull(pending.forConversation("c2"))
    }

    @Test
    fun `answered, cancelled or reconnected, it is gone`() {
        val pending = PendingApprovals()
        pending.onRequest(approval("a1", "c1"))
        pending.answered("a1")
        assertNull(pending.forConversation("c1"))

        pending.onRequest(approval("a2", "c1"))
        pending.onCancelled("a2")
        assertNull(pending.forConversation("c1"))

        pending.onRequest(approval("a3", "c1"))
        pending.clear()
        assertNull(pending.forConversation("c1"))
    }

    @Test
    fun `a cancellation naming no prompt clears them all, as the chat's card does`() {
        val pending = PendingApprovals()
        pending.onRequest(approval("a1", "c1"))
        pending.onRequest(approval("a2", "c2"))

        pending.onCancelled(null)

        assertNull(pending.forConversation("c1"))
        assertNull(pending.forConversation("c2"))
    }

    @Test
    fun `the newest waiting approval in a conversation is the one shown`() {
        val pending = PendingApprovals()
        pending.onRequest(approval("a1", "c1"))
        pending.onRequest(approval("a2", "c1"))

        assertEquals("a2", pending.forConversation("c1")?.id)
        pending.answered("a2")
        assertEquals("a1", pending.forConversation("c1")?.id)
    }

    @Test
    fun `the computer's list of waiting approvals is read, bare or in an envelope`() {
        val one = kotlinx.serialization.json.Json.parseToJsonElement(
            """[{"id":"a1","conversationId":"c1","toolName":"edit_file","title":"Edit a.js","risk":"sensitive"}]""",
        )
        assertEquals(listOf("a1"), waitingApprovals(one).map { it.id })

        val wrapped = kotlinx.serialization.json.buildJsonObject { put("value", one) }
        assertEquals(listOf("a1"), waitingApprovals(wrapped).map { it.id })

        // A computer older than the channel answers with an error, which reads as none.
        assertTrue(waitingApprovals(null).isEmpty())
        assertTrue(waitingApprovals(kotlinx.serialization.json.JsonPrimitive("nope")).isEmpty())
    }

    @Test
    fun `the phone's copy wins only when it is the computer's copy and more`() {
        // The computer wrote the question as the turn started; the phone holds the reply too.
        assertTrue(holdsMoreThanComputer(listOf("m1"), listOf("m1", "m1:reply")))
        assertFalse(holdsMoreThanComputer(listOf("m1", "m1:reply"), listOf("m1", "m1:reply")))
        assertFalse(holdsMoreThanComputer(listOf("m1", "m1:reply", "m2"), listOf("m1", "m1:reply")))
        // Cut back at the computer, so the two disagree: the computer's copy stands.
        assertFalse(holdsMoreThanComputer(listOf("m0"), listOf("m1", "m1:reply")))
    }
}
