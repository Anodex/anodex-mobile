package dev.anodex.mobile.email

import dev.anodex.mobile.transport.AnodexSocket
import kotlin.time.Duration.Companion.seconds
import dev.anodex.mobile.transport.unwrap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What can be done to a thread without destroying it.
 *
 * The wire names are the desktop's `EmailFlagAction` and must not be renamed --
 * they cross the socket. Deleting is not on the list, on the desktop or here:
 * its own type says so, and that is the property that makes every one of these
 * safe to offer on a phone in somebody's pocket.
 */
enum class MailFlag(val wire: String) {
    READ("mark_read"),
    UNREAD("mark_unread"),
    STAR("star"),
    UNSTAR("unstar"),
    ARCHIVE("archive"),
    UNARCHIVE("unarchive"),
}

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

/**
 * A file that came with a message.
 *
 * The phone kept only a count of these, which was enough to say "2 attachments"
 * and not enough to do anything about them. The id and the message it belongs to
 * are what the computer needs to fetch the bytes; the size is what decides
 * whether somebody wants to over mobile data.
 */
data class EmailAttachment(
    val id: String,
    val messageId: String,
    val filename: String,
    val mimeType: String,
    val size: Long,
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
    /** The files themselves, so they can be opened rather than only counted. */
    val attachments: List<EmailAttachment> = emptyList(),
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

    /**
     * Search the mailbox.
     *
     * The same shape as [threads] on purpose: the computer answers a search with
     * thread summaries, so the list that draws an inbox draws results without
     * knowing which it is showing.
     *
     * Works on every account type. `email:search` is `listThreads` with a query
     * on the far side, and all three providers -- Gmail, Microsoft and plain
     * IMAP -- implement that one method. Nothing here is written against a
     * provider's own search syntax, which is what would have made this work on
     * one account and quietly return nothing on another.
     */
    suspend fun search(query: String, limit: Int = 50): List<EmailThread> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val request = buildJsonObject {
            put("query", JsonPrimitive(trimmed))
            put("limit", JsonPrimitive(limit))
        }
        val value = socket.invoke(CHANNEL_SEARCH, listOf(request)).unwrap() as? JsonArray
        return value.orEmpty().mapNotNull { (it as? JsonObject)?.asThread() }
    }

    /**
     * The mailboxes this account has.
     *
     * Named by the server, which is why they are shown by their leaf rather than
     * their path: Gmail's Sent is `[Gmail]/Sent Mail` and an IMAP server's is
     * often `INBOX.Sent`, and neither is what anybody calls it.
     */
    suspend fun mailboxes(): List<MailFolder> {
        val value = socket.invoke(CHANNEL_MAILBOXES, listOf(JsonNull)).unwrap() as? JsonArray
        return value.orEmpty().mapNotNull { row ->
            val o = row as? JsonObject ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull() ?: return@mapNotNull null
            MailFolder(
                name = name,
                label = friendlyFolderName(name),
                system = o["system"]?.jsonPrimitive?.contentOrNull() == "true",
            )
        }
    }

    /** The most recent threads in a mailbox. Empty when email is not set up at all. */
    suspend fun threads(limit: Int = 50, mailbox: String? = null): List<EmailThread> {
        val request = buildJsonObject {
            put("limit", JsonPrimitive(limit))
            // Absent means the inbox, which is what the computer defaults to --
            // so nothing is sent for it rather than a name this phone guessed.
            mailbox?.takeIf { it.isNotBlank() }?.let { put("mailbox", JsonPrimitive(it)) }
        }
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
            attachments = (this["attachments"] as? JsonArray).orEmpty().mapNotNull { row ->
                val o = row as? JsonObject ?: return@mapNotNull null
                val attachmentId = o["id"]?.jsonPrimitive?.contentOrNull() ?: return@mapNotNull null
                EmailAttachment(
                    id = attachmentId,
                    // The message that owns it. The provider's fetch needs both,
                    // and the summary carries its own copy rather than the reader
                    // having to remember which message a row came from.
                    messageId = o["messageId"]?.jsonPrimitive?.contentOrNull() ?: id,
                    filename = o["filename"]?.jsonPrimitive?.contentOrNull()
                        ?.takeIf { it.isNotBlank() } ?: "attachment",
                    mimeType = o["mimeType"]?.jsonPrimitive?.contentOrNull()
                        ?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
                    size = o["size"]?.jsonPrimitive?.contentOrNull()?.toLongOrNull() ?: 0L,
                )
            },
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

    /**
     * Mark, star or archive -- the verbs a mailbox needs once you can read it.
     *
     * Every action here is reversible by another action here, which is not an
     * accident: `EmailFlagAction` on the desktop is
     * `mark_read | mark_unread | star | unstar | archive | unarchive`, and its own
     * comment says deleting mail is deliberately absent. So nothing on this phone
     * can destroy a message, only move it out of the way.
     *
     * Applied to the whole thread rather than one message. That is what the row
     * being tapped represents, and marking one message of five as read leaves an
     * inbox that still says unread with nothing visibly unread in it.
     */
    suspend fun flag(threadId: String, action: MailFlag, accountId: String? = null): Boolean {
        val request = buildJsonObject {
            put("threadId", JsonPrimitive(threadId))
            put("action", JsonPrimitive(action.wire))
            accountId?.takeIf { it.isNotBlank() }?.let { put("accountId", JsonPrimitive(it)) }
        }
        val answer = socket.invoke(CHANNEL_FLAG, listOf(request)) as? JsonObject ?: return false
        return answer["ok"]?.jsonPrimitive?.contentOrNull() == "true"
    }

    /**
     * Delete a thread, which means moving it to the account's trash.
     *
     * The computer works out which mailbox that is -- Gmail, Microsoft and a
     * plain IMAP server all call it something different, and a phone carrying
     * its own list of spellings would be a second list to get wrong. It refuses
     * rather than guessing when an account has no trash, and says so.
     *
     * Recoverable from the desktop this phone is paired to, which is the whole
     * reason a delete button is safe to put in a pocket. Nothing in this app
     * expunges anything.
     */
    suspend fun trash(threadId: String, accountId: String? = null): Boolean {
        val request = buildJsonObject {
            put("threadId", JsonPrimitive(threadId))
            accountId?.takeIf { it.isNotBlank() }?.let { put("accountId", JsonPrimitive(it)) }
        }
        val answer = socket.invoke(CHANNEL_TRASH, listOf(request)) as? JsonObject ?: return false
        return answer["ok"]?.jsonPrimitive?.contentOrNull() == "true"
    }

    /**
     * One chunk of an attachment's bytes, starting at [offset].
     *
     * Chunked because the socket refuses a response over four megabytes and
     * base64 costs a third on top -- a single fetch would cap attachments below
     * the size of a photograph taken on this phone. The computer holds the last
     * one it read, so a long download is one request to the mail provider rather
     * than one per chunk.
     */
    suspend fun attachmentChunk(
        messageId: String,
        attachmentId: String,
        offset: Long,
        accountId: String? = null,
    ): AttachmentChunk? {
        val request = buildJsonObject {
            put("messageId", JsonPrimitive(messageId))
            put("attachmentId", JsonPrimitive(attachmentId))
            put("offset", JsonPrimitive(offset))
            accountId?.takeIf { it.isNotBlank() }?.let { put("accountId", JsonPrimitive(it)) }
        }
        val value = socket.invoke(CHANNEL_ATTACHMENT, listOf(request), timeout = 120.seconds)
            .unwrap() as? JsonObject ?: return null

        return AttachmentChunk(
            filename = value["filename"]?.jsonPrimitive?.contentOrNull().orEmpty(),
            mimeType = value["mimeType"]?.jsonPrimitive?.contentOrNull().orEmpty(),
            size = value["size"]?.jsonPrimitive?.contentOrNull()?.toLongOrNull() ?: 0L,
            offset = value["offset"]?.jsonPrimitive?.contentOrNull()?.toLongOrNull() ?: 0L,
            base64 = value["base64"]?.jsonPrimitive?.contentOrNull().orEmpty(),
            done = value["done"]?.jsonPrimitive?.contentOrNull() == "true",
        )
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
        const val CHANNEL_FLAG = "email:apply-flag"
        const val CHANNEL_TRASH = "email:trash"
        const val CHANNEL_SEARCH = "email:search"
        const val CHANNEL_ATTACHMENT = "email:get-attachment-chunk"
        const val CHANNEL_MAILBOXES = "email:list-mailboxes"
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

/** One piece of an attachment, as the computer hands it over. */
data class AttachmentChunk(
    val filename: String,
    val mimeType: String,
    /** The whole file's size, so a caller knows how far along it is. */
    val size: Long,
    val offset: Long,
    val base64: String,
    /** True when this piece reaches the end, so nobody has to do the arithmetic. */
    val done: Boolean,
)

/** One mailbox, as a person would name it. */
data class MailFolder(
    /** What the server calls it, which is what a request has to carry. */
    val name: String,
    /** What to show: `[Gmail]/Sent Mail` becomes `Sent Mail`. */
    val label: String,
    /** Provider-managed, as opposed to a folder somebody made. */
    val system: Boolean,
)

/**
 * `[Gmail]/Sent Mail` becomes `Sent Mail`, `INBOX.Archive` becomes `Archive`.
 *
 * The same reduction the desktop makes, and for the same reason: a server's
 * namespace is not what anybody calls the folder, and showing the path makes a
 * list of five mailboxes unreadable on a phone.
 */
internal fun friendlyFolderName(name: String): String {
    val withoutNamespace = name.replace(Regex("""^\[[^]]+][/.]?"""), "").trim()
    val leaf = withoutNamespace.split('/', '.').lastOrNull()?.trim()
    return leaf?.takeIf { it.isNotBlank() } ?: name
}
