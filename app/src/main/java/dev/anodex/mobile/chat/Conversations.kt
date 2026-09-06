package dev.anodex.mobile.chat

import dev.anodex.mobile.transport.AnodexSocket
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One conversation as the desktop stores it. Summary only — messages load on open. */
data class ConversationSummary(
    val id: String,
    val title: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val messageCount: Int,
    /**
     * The title as the computer actually stores it — null when it has none yet.
     *
     * Kept alongside [title], which is the display value and falls back to
     * "Untitled". Saving that fallback back to the computer would turn a
     * conversation the desktop had not titled *yet* into one permanently called
     * Untitled, so the two cannot be the same field.
     */
    val storedTitle: String? = null,
    /**
     * The workspace this conversation belongs to, or null for plain chat.
     *
     * Carried so the list can be grouped by it. Which project a conversation runs
     * against decides whether a turn edits real files, so a flat list sorted only by
     * time puts "rewrite the save format" next to "weekend reading" with nothing to
     * tell them apart.
     */
    val projectId: String? = null,
)

/**
 * Reading the desktop's conversation store.
 *
 * The phone holds no conversations of its own (handoff §2, §10.1). Everything here
 * is a live read of what is on the computer, which is why opening a conversation is
 * a network call rather than a lookup — and why the list is empty rather than stale
 * when the desktop is unreachable.
 *
 * Field names come from `protocol/anodex-protocol.json`. Parsing is lenient about
 * everything the phone does not use: a `Conversation` carries plans, goals, context
 * ledgers and visual previews, none of which a phone renders, and failing to parse
 * one because of a field it ignores would be absurd.
 */
class Conversations(private val socket: AnodexSocket) {

    suspend fun list(): List<ConversationSummary> {
        val result = socket.invoke(CHANNEL_LIST) as? JsonArray ?: return emptyList()
        return result.mapNotNull { it.asSummary() }
            .sortedByDescending { it.updatedAtEpochMs }
    }

    /**
     * Load one conversation's turns.
     *
     * Re-lists rather than fetching by id, because the desktop exposes no read-one
     * channel. Wasteful in principle; in practice a conversation list is small and
     * this keeps the phone from needing a channel that does not exist.
     */
    suspend fun messagesOf(conversationId: String): List<ChatMessage> {
        val result = socket.invoke(CHANNEL_LIST) as? JsonArray ?: return emptyList()

        val conversation = result
            .filterIsInstance<JsonObject>()
            .firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull() == conversationId }
            ?: return emptyList()

        return (conversation["messages"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.mapNotNull { it.asMessage() }
            .orEmpty()
    }

    private fun kotlinx.serialization.json.JsonElement.asSummary(): ConversationSummary? {
        val fields = this as? JsonObject ?: return null
        if (fields["archived"]?.jsonPrimitive?.contentOrNull() == "true") return null

        val id = fields["id"]?.jsonPrimitive?.contentOrNull() ?: return null
        val messages = (fields["messages"] as? JsonArray)?.size ?: 0
        // An untitled conversation is one the desktop has not summarised yet, which
        // is normal for a turn or two rather than an error.
        val storedTitle = fields["title"]?.jsonPrimitive?.contentOrNull()
            ?.takeIf { it.isNotBlank() }

        return ConversationSummary(
            id = id,
            // An untitled conversation is one the desktop has not summarised yet,
            // which is normal for a turn or two rather than an error.
            title = storedTitle ?: "Untitled",
            storedTitle = storedTitle,
            createdAtEpochMs = fields["createdAt"]?.jsonPrimitive?.contentOrNull()
                ?.toDoubleOrNull()?.toLong() ?: 0L,
            updatedAtEpochMs = fields["updatedAt"]?.jsonPrimitive?.contentOrNull()
                ?.toDoubleOrNull()?.toLong() ?: 0L,
            messageCount = messages,
            projectId = fields["projectId"]?.jsonPrimitive?.contentOrNull(),
        )
    }

    private fun JsonObject.asMessage(): ChatMessage? {
        val id = this["id"]?.jsonPrimitive?.contentOrNull() ?: return null
        val role = this["role"]?.jsonPrimitive?.contentOrNull() ?: return null
        val content = this["content"]?.jsonPrimitive?.contentOrNull() ?: ""

        // System turns are the prompt scaffolding, not conversation. Showing them
        // would put the machinery in front of the thing the user came to read.
        if (role == "system") return null

        return ChatMessage(
            id = id,
            role = if (role == "user") ChatMessage.Role.USER else ChatMessage.Role.ASSISTANT,
            text = content,
        )
    }

    private companion object {
        const val CHANNEL_LIST = "conversations:list"
    }
}

/** `content` on a JSON null is the string "null", which is never what a caller wants. */
private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content
