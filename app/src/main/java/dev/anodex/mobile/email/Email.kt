package dev.anodex.mobile.email

import dev.anodex.mobile.transport.AnodexSocket
import dev.anodex.mobile.transport.unwrap
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
    /** The thread this message belongs to, so a reply joins it instead of starting one. */
    val threadId: String?,
    val from: String,
    val subject: String,
    val body: String,
    /**
     * The sanitized HTML the desktop built, or null when the message had none.
     *
     * Already safe by the time it arrives: scripts removed, inline `cid:` images
     * turned into data URIs, and every remote URL moved to `data-remote-src` so
     * nothing is fetched until the reader says so. Rendering it is what makes a
     * message look like a message; [body] is the fallback and what the model reads.
     */
    val bodyHtml: String?,
    /** Everyone it was addressed to, for a reply-all that means all. */
    val to: List<String>,
    val cc: List<String>,
    val dateEpochMs: Long,
    val attachmentCount: Int,
)

/**
 * Reading the desktop's mailbox.
 *
 * Read and write. It was read-only, on the reasoning that sending mail leaves the
 * machine and cannot be taken back, so it deserved the desktop's compose surface
 * rather than a text field bolted onto a list. The first half of that is true and
 * is why sending asks first; the second turned out to mean "you must be at your
 * computer to answer an email", which is the opposite of what the phone is for.
 *
 * `email:send` and `email:create-draft` were open to a paired phone the whole
 * time. Nothing was refused; nothing was called.
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
            threadId = this["threadId"]?.jsonPrimitive?.contentOrNull()?.takeIf { it.isNotBlank() },
            from = this["from"]?.jsonPrimitive?.contentOrNull().orEmpty(),
            subject = this["subject"]?.jsonPrimitive?.contentOrNull().orEmpty(),
            // Plain text, which is what the model reads and what this phone falls
            // back to. The old note here said `bodyHtml` was "a whole security
            // surface -- remote images, tracking pixels, layout that fights the
            // app". The first two are handled before it reaches the wire:
            // `main/email/htmlBody.ts` strips scripts, inlines `cid:` images as
            // data URIs, and parks every remote URL on `data-remote-src` so it
            // loads only when the reader asks. Refusing the sanitized output meant
            // a newsletter arrived as two screens of tracking links, which is the
            // sender's plain-text part and worse than the HTML by every measure.
            body = this["body"]?.jsonPrimitive?.contentOrNull().orEmpty(),
            bodyHtml = this["bodyHtml"]?.jsonPrimitive?.contentOrNull()?.takeIf { it.isNotBlank() },
            to = (this["to"] as? JsonArray).addresses(),
            cc = (this["cc"] as? JsonArray).addresses(),
            dateEpochMs = this["date"].asEpochMs(),
            attachmentCount = (this["attachments"] as? JsonArray)?.size ?: 0,
        )
    }

    /**
     * Send one.
     *
     * [accountId] is left off so the desktop uses its primary account, which is
     * the same choice the desktop's own compose makes. Answering with success or
     * a message rather than throwing, because "it did not send" is something the
     * screen has to say out loud -- a compose that closes on failure loses what
     * was typed, and this is the one action here that cannot be retried from
     * memory.
     */
    suspend fun send(
        to: List<String>,
        subject: String,
        body: String,
        cc: List<String> = emptyList(),
        threadId: String? = null,
    ): Boolean {
        val request = buildJsonObject {
            put("to", JsonArray(to.map(::JsonPrimitive)))
            if (cc.isNotEmpty()) put("cc", JsonArray(cc.map(::JsonPrimitive)))
            put("subject", JsonPrimitive(subject))
            put("body", JsonPrimitive(body))
            // The provider's thread, so a reply joins the conversation rather than
            // starting a new one.
            //
            // `inReplyTo` is deliberately not sent. The desktop's field wants the
            // RFC `Message-ID` header -- its own comment says "set by reply_email,
            // not by hand" -- and `EmailMessage` does not carry one: `id` is the
            // provider's own identifier, a Gmail API id or an IMAP uid depending
            // on the account. Passing that would put a header on the wire claiming
            // to reference a message id that does not exist, which is worse than
            // omitting the header: a malformed reference breaks threading in
            // clients that would otherwise fall back to the subject.
            threadId?.let { put("threadId", JsonPrimitive(it)) }
        }

        // `email:send` answers `ok: true` with a void value, so the wrapper is the
        // whole answer -- `unwrap()` returning a JSON null is success here, and
        // testing the value rather than the envelope would read every send as a
        // failure.
        val answer = socket.invoke(CHANNEL_SEND, listOf(request)) as? JsonObject ?: return false
        return answer["ok"]?.jsonPrimitive?.contentOrNull() == "true"
    }

    /** Remote images for a message the reader has asked to see in full. */
    suspend fun loadRemoteImages(urls: List<String>): Map<String, String> {
        if (urls.isEmpty()) return emptyMap()
        val args = listOf(JsonArray(urls.map(::JsonPrimitive)))
        val answer = socket.invoke(CHANNEL_IMAGES, args).unwrap() as? JsonObject ?: return emptyMap()
        return answer.mapNotNull { (url, value) ->
            (value as? JsonPrimitive)?.contentOrNull()?.let { url to it }
        }.toMap()
    }

    private fun JsonArray?.addresses(): List<String> =
        this.orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull() }

    private companion object {
        const val CHANNEL_SEND = "email:send"
        const val CHANNEL_IMAGES = "email:load-remote-images"
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

private fun JsonElement?.asEpochMs(): Long =
    (this as? JsonPrimitive)?.contentOrNull()?.toDoubleOrNull()?.toLong() ?: 0L

private fun JsonElement?.asInt(): Int =
    (this as? JsonPrimitive)?.contentOrNull()?.toDoubleOrNull()?.toInt() ?: 0

/** `content` on a JSON null is the string "null", which is never what a caller wants. */
private fun JsonPrimitive.contentOrNull(): String? = if (this is JsonNull) null else content
