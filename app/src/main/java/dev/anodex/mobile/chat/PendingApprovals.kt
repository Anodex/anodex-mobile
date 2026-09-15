package dev.anodex.mobile.chat

/**
 * Approvals the computer is waiting on, whichever conversation is open.
 *
 * A chat only showed an approval that arrived while that chat was on screen. Opened
 * afterwards — from the "Anodex needs an answer" notification, which is the whole
 * point of the notification — it showed the conversation and no card, and the turn
 * sat waiting until it was declined after five minutes. Seen on the emulator during a
 * two-job stress test.
 *
 * Kept here, for the whole connection, and handed to a chat as it opens.
 */
class PendingApprovals {
    private val byId = LinkedHashMap<String, ToolApproval>()

    /** A `tools:confirm-request` arrived. */
    fun onRequest(approval: ToolApproval) {
        byId[approval.id] = approval
    }

    /**
     * A `tools:confirm-cancelled` arrived: answered somewhere, aborted, or expired.
     * A cancellation that names no prompt clears them all, as the chat's card does.
     */
    fun onCancelled(id: String?) {
        if (id == null) byId.clear() else byId.remove(id)
    }

    /** This phone answered it. */
    fun answered(id: String) {
        byId.remove(id)
    }

    /** A new connection: the computer tells it again about whatever is still waiting. */
    fun clear() {
        byId.clear()
    }

    /** The newest approval waiting in [conversationId], if any. */
    fun forConversation(conversationId: String): ToolApproval? =
        byId.values.lastOrNull { it.conversationId == conversationId }

    companion object {
        /** The same channels a chat listens on. */
        const val CHANNEL_REQUEST = "tools:confirm-request"
        const val CHANNEL_CANCELLED = "tools:confirm-cancelled"

        /** What is waiting, asked once a new connection is listening. Desktop 0.9.12 and later. */
        const val CHANNEL_WAITING = "tools:pending-confirmations"
    }
}

/**
 * The approvals in `tools:pending-confirmations`' answer. The handler returns the list
 * itself, but an `{ ok, value }` envelope is read too, and anything else is none.
 */
internal fun waitingApprovals(answer: kotlinx.serialization.json.JsonElement?): List<ToolApproval> {
    val value = (answer as? kotlinx.serialization.json.JsonObject)?.get("value") ?: answer
    return (value as? kotlinx.serialization.json.JsonArray)?.mapNotNull(::parseToolApproval).orEmpty()
}

/**
 * Whether what this phone was holding of a conversation is further on than the
 * computer's copy of it.
 *
 * The computer writes a phone's question as the turn starts and the reply when it
 * finishes, so between the two it holds less than the phone does. Opening it from
 * the computer then would drop the reply the phone was showing.
 */
internal fun holdsMoreThanComputer(computerIds: List<String>, heldIds: List<String>): Boolean =
    heldIds.size > computerIds.size && heldIds.take(computerIds.size) == computerIds
