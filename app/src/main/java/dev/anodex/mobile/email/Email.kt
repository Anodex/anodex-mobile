package dev.anodex.mobile.email

import dev.anodex.mobile.transport.AnodexSocket
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One conversation in the mailbox, as the inbox list shows it. */
data class EmailThread(
    val id: String,
    val accountId: String,
    val subject: String,
    val from: String,
    val snippet: String,
    val updatedAtEpochMs: Long,
    val unread: Boolean,
    val starred: Boolean,
    val messageCount: Int,
    val attachmentCount: Int,
)

/** One message inside a thread. */
data class EmailNote(
    val id: String,
    val from: String,
    val subject: String,
    val body: String,
    val dateEpochMs: Long,
    val attachmentCount: Int,
)

/**
 * Reading the desktop's mailbox.
 *
 * Read-only, deliberately. Sending mail from a phone that is driving somebody's
 * computer is a different kind of action from reading it — it leaves the machine,
 * it cannot be taken back, and it deserves the desktop's compose surface and its
 * approval step rather than a text field bolted onto a list. What the phone is for
 * here is the thing you actually want away from your desk: seeing what has arrived.
 *
 * Unlike `conversations:list`, the email channels return the desktop's `Result`
 * wrapper rather than a bare value, so every call here unwraps `{ ok, value }`. The
 * shapes are read from `protocol/anodex-protocol.json`; writing them from memory is
 * how the chat request was wrong for a whole evening.
 */
class Email(private val socket: AnodexSocket) {

    /** The most recent threads in the inbox. Empty when email is not set up at all. */
    suspend fun threads(limit: Int = 50): List<EmailThread> {
        val request = buildJsonObject { put("limit", JsonPrimitive(limit)) }
        val value = socket.invoke(CHANNEL_THREADS, listOf(request)).unwrap() as? JsonArray
            ?: return emptyList()

        return value.filterIsInstance<JsonObject>()
            .mapNotNull { it.asThread() }
            .sortedByDescending { it.updatedAtEpochMs }
    }

    suspend fun messages(threadId: String, accountId: String?): List<EmailNote> {
        val args = listOf(
            JsonPrimitive(threadId),
            accountId?.let { JsonPrimitive(it) } ?: JsonNull,
        )
        val value = socket.invoke(CHANNEL_MESSAGES, args).unwrap() as? JsonArray
            ?: return emptyList()

        return value.filterIsInstance<JsonObject>()
            .mapNotNull { it.asNote() }
            .sortedBy { it.dateEpochMs }
    }

    /**
     * How many threads are unread, for the tab badge.
     *
     * Returns zero rather than throwing when email is not configured: a badge is not
     * worth an error state, and "no unread mail" is the truthful answer for a user
     * who has never connected an account.
     */
    suspend fun unreadCount(): Int =
        (socket.invoke(CHANNEL_UNREAD, listOf(JsonNull)).unwrap() as? JsonPrimitive)
            ?.contentOrNull()?.toDoubleOrNull()?.toInt() ?: 0

    /** Whether the desktop has email set up at all, so the tab can say so plainly. */
    suspend fun isConfigured(): Boolean {
        val status = socket.invoke(CHANNEL_STATUS).unwrap() as? JsonObject ?: return false
        return status["enabled"]?.jsonPrimitive?.contentOrNull() == "true" &&
            (status["accounts"] as? JsonArray)?.isNotEmpty() == true
    }

    private fun JsonObject.asThread(): EmailThread? {
        val id = this["id"]?.jsonPrimitive?.contentOrNull() ?: return null
        return EmailThread(
            id = id,
            accountId = this["accountId"]?.jsonPrimitive?.contentOrNull().orEmpty(),
            subject = this["subject"]?.jsonPrimitive?.contentOrNull()
                ?.takeIf { it.isNotBlank() } ?: "(no subject)",
            from = this["from"]?.jsonPrimitive?.contentOrNull().orEmpty(),
            snippet = this["snippet"]?.jsonPrimitive?.contentOrNull().orEmpty(),
            updatedAtEpochMs = this["updatedAt"].asEpochMs(),
            unread = this["unread"]?.jsonPrimitive?.contentOrNull() == "true",
            starred = this["starred"]?.jsonPrimitive?.contentOrNull() == "true",
            messageCount = this["messageCount"].asInt(),
            attachmentCount = this["attachmentCount"].asInt(),
        )
    }

    private fun JsonObject.asNote(): EmailNote? {
        val id = this["id"]?.jsonPrimitive?.contentOrNull() ?: return null
        return EmailNote(
            id = id,
            from = this["from"]?.jsonPrimitive?.contentOrNull().orEmpty(),
            subject = this["subject"]?.jsonPrimitive?.contentOrNull().orEmpty(),
            // The plain-text body, never `bodyHtml`. Rendering sender-controlled HTML
            // is a whole security surface — remote images, tracking pixels, layout
            // that fights the app — and none of it is worth it to read a message.
            body = this["body"]?.jsonPrimitive?.contentOrNull().orEmpty(),
            dateEpochMs = this["date"].asEpochMs(),
            attachmentCount = (this["attachments"] as? JsonArray)?.size ?: 0,
        )
    }

    private companion object {
        const val CHANNEL_THREADS = "email:list-threads"
        const val CHANNEL_MESSAGES = "email:get-thread-messages"
        const val CHANNEL_UNREAD = "email:get-unread-thread-count"
        const val CHANNEL_STATUS = "email:get-status"
    }
}

/**
 * The value out of the desktop's `Result` wrapper, or null if the call failed.
 *
 * A failure here is nearly always "email is not set up", which is a normal state
 * rather than an error worth interrupting anyone about — so it degrades to an empty
 * list and the screen says so in words.
 */
internal fun JsonElement?.unwrap(): JsonElement? {
    val result = this as? JsonObject ?: return null
    if (result["ok"]?.jsonPrimitive?.contentOrNull() != "true") return null
    return result["value"]
}

private fun JsonElement?.asEpochMs(): Long =
    (this as? JsonPrimitive)?.contentOrNull()?.toDoubleOrNull()?.toLong() ?: 0L

private fun JsonElement?.asInt(): Int =
    (this as? JsonPrimitive)?.contentOrNull()?.toDoubleOrNull()?.toInt() ?: 0

/** `content` on a JSON null is the string "null", which is never what a caller wants. */
private fun JsonPrimitive.contentOrNull(): String? = if (this is JsonNull) null else content
