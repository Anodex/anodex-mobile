package dev.anodex.mobile.chat

import dev.anodex.mobile.transport.AnodexSocket
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    /**
     * What made this conversation, when it was not a person.
     *
     * Null means somebody typed it, which is the only kind worth putting in a
     * recents list. A scheduled task's run and a benchmark script both write
     * conversations exactly like a real one, so a list ordered by last write shows
     * the computer's activity rather than yours — which is how one chat the user
     * actually had ended up surrounded by eleven they had never opened.
     *
     * Unknown values are kept as-is rather than folded into null: a kind this build
     * has not heard of is still not a person, and guessing otherwise would put it
     * back in the list this exists to keep clean.
     */
    val origin: String? = null,
) {
    /** True when a person started this, which is the only kind recents should show. */
    val isMine: Boolean get() = origin == null
}

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
        val result = socket.invoke(CHANNEL_SUMMARIES) as? JsonArray ?: return emptyList()
        // Archived rows are dropped here rather than inside the parser, so the same
        // parse can serve the archive screen, where they are the entire point.
        return result.filterNot { it.isArchived() }
            .mapNotNull { it.asSummary() }
            .sortedByDescending { it.updatedAtEpochMs }
    }

    /**
     * Just these conversations' rows, read again after the computer said they changed.
     *
     * One row is a few hundred bytes where the whole list is tens of kilobytes, and a
     * save is announced after every reply. A conversation archived or gone is absent
     * from the answer.
     *
     * Null when the computer answered with its whole list instead — one from before it
     * could be asked for less ignores the ids — which the caller takes as the list.
     */
    suspend fun summariesOf(ids: Collection<String>): SummaryRead {
        val result = socket.invoke(
            CHANNEL_SUMMARIES,
            listOf(kotlinx.serialization.json.JsonArray(ids.map(::JsonPrimitive))),
        ) as? JsonArray ?: return SummaryRead.Rows(emptyList())
        val rows = result.filterNot { it.isArchived() }.mapNotNull { it.asSummary() }
        return if (rows.any { it.id !in ids }) {
            SummaryRead.WholeList(rows.sortedByDescending { it.updatedAtEpochMs })
        } else {
            SummaryRead.Rows(rows)
        }
    }

    /** Whether the computer has archived this row. */
    private fun kotlinx.serialization.json.JsonElement.isArchived(): Boolean =
        (this as? JsonObject)?.get("archived")?.jsonPrimitive?.contentOrNull() == "true"

    /**
     * Load one conversation's most recent turns.
     *
     * This used to re-list *every* conversation and filter, because the desktop had
     * no read-one channel. That was described as "wasteful in principle; in practice
     * a conversation list is small" — and it was wrong. A real store is over a
     * hundred megabytes, a WebSocket message is buffered whole before it can be
     * read, and the phone died with `OutOfMemoryError` inside OkHttp's reader thread
     * where nothing can catch it. Opening a conversation downloaded the entire store
     * twice.
     *
     * Capped at [RECENT_MESSAGES] because the end of a transcript is what a reader
     * wants and a single conversation can itself be tens of megabytes.
     */
    suspend fun messagesOf(conversationId: String): List<ChatMessage> =
        open(conversationId)?.messages.orEmpty()

    /**
     * Load one conversation: its recent turns, and where it is filed.
     *
     * The filing matters as much as the turns. Opening a plain chat used to take its
     * project from the drawer's summary *or else whichever project the computer had
     * open* — and a plain chat's project is null, so the "or else" always won. The
     * next message ran inside that project's files, and its save moved the chat into
     * that project's folder on the computer. `conversations:get` already said where the
     * conversation lives; it was being thrown away.
     *
     * Null when the computer has no such conversation.
     */
    /**
     * A picture on a message, sized for a phone, as JPEG bytes.
     *
     * Null when the computer has no such picture any more, or is too old to send one.
     */
    suspend fun attachmentPreview(conversationId: String, messageId: String, index: Int): ByteArray? {
        val answer = socket.invoke(
            CHANNEL_ATTACHMENT_PREVIEW,
            listOf(JsonPrimitive(conversationId), JsonPrimitive(messageId), JsonPrimitive(index)),
        )
        return parseAttachmentPreview(answer)
    }

    suspend fun open(conversationId: String, limit: Int = RECENT_MESSAGES): OpenedConversation? {
        val conversation = socket.invoke(
            CHANNEL_GET,
            listOf(JsonPrimitive(conversationId), JsonPrimitive(limit)),
        ) as? JsonObject ?: return null

        return parseOpenedConversation(conversation, limit) { it.asMessage() }
    }

    /**
     * Conversations whose messages match [query], best first, each with the passage
     * that matched.
     *
     * Asked of the computer because the phone holds no transcripts to search. A
     * computer too old to have the channel refuses it, which is the caller's to treat
     * as "titles only".
     */
    suspend fun search(query: String): List<MessageMatch> =
        parseMessageMatches(socket.invoke(CHANNEL_SEARCH, listOf(JsonPrimitive(query))))

    /**
     * Archive one conversation.
     *
     * The desktop calls this `delete` and means archive: the record is kept, flagged,
     * and dropped out of every list until it is restored. Nothing here is capable of
     * destroying one — `conversations:delete-permanent` exists on the computer and is
     * deliberately not spoken from the phone, which is a device that gets left on
     * tables.
     *
     * The archived flag is what [asSummary] already filters on, so a conversation
     * archived from here disappears from this app's lists for the same reason it
     * disappears from the desktop's.
     */
    suspend fun archive(conversationId: String) {
        socket.invoke(CHANNEL_ARCHIVE, listOf(JsonPrimitive(conversationId)))
    }

    /** Put one back, which is the whole reason archiving needs no confirmation. */
    suspend fun restore(conversationId: String) {
        socket.invoke(CHANNEL_RESTORE, listOf(JsonPrimitive(conversationId)))
    }

    /**
     * What has been archived.
     *
     * A separate channel rather than a flag on [list], because `list` deliberately
     * drops archived rows — the drawer must never show one — and a boolean argument
     * threading through it would be one `false` away from putting them back.
     */
    suspend fun listArchived(): List<ConversationSummary> {
        val result = socket.invoke(CHANNEL_LIST_ARCHIVED) as? JsonArray ?: return emptyList()
        // `asSummary` drops archived rows, which is exactly wrong here: every row in
        // this answer is archived by definition. Parsed without that filter.
        return result.mapNotNull { it.asSummary() }
            .sortedByDescending { it.updatedAtEpochMs }
    }

    /**
     * Gone, not hidden.
     *
     * The only call in this client that cannot be undone from either end. The screen
     * that reaches it asks first, and asks in terms of what is lost rather than
     * whether the user is sure.
     */
    suspend fun deletePermanently(conversationId: String) {
        socket.invoke(CHANNEL_DELETE_PERMANENT, listOf(JsonPrimitive(conversationId)))
    }

    private fun kotlinx.serialization.json.JsonElement.asSummary(): ConversationSummary? {
        val fields = this as? JsonObject ?: return null

        val id = fields["id"]?.jsonPrimitive?.contentOrNull() ?: return null
        // Counted by the desktop now. The summary deliberately carries no messages —
        // they are the entire reason the full list could not be sent.
        val messages = fields["messageCount"]?.jsonPrimitive?.contentOrNull()
            ?.toDoubleOrNull()?.toInt() ?: 0
        // An untitled conversation is one the desktop has not summarised yet, which
        // is normal for a turn or two rather than an error.
        val storedTitle = fields["title"]?.jsonPrimitive?.contentOrNull()
            ?.takeIf { it.isNotBlank() }

        return ConversationSummary(
            id = id,
            // An untitled conversation is one the desktop has not summarised yet,
            // which is normal for a turn or two rather than an error.
            //
            // Shown without markdown marks, since a title is drawn as plain text and
            // plenty already on the computer were cut from a pasted prompt. Only the
            // display value: `storedTitle` is what gets saved back, untouched.
            title = storedTitle?.let(::withoutMarkdown)?.takeIf { it.isNotBlank() } ?: "Untitled",
            storedTitle = storedTitle,
            createdAtEpochMs = fields["createdAt"]?.jsonPrimitive?.contentOrNull()
                ?.toDoubleOrNull()?.toLong() ?: 0L,
            updatedAtEpochMs = fields["updatedAt"]?.jsonPrimitive?.contentOrNull()
                ?.toDoubleOrNull()?.toLong() ?: 0L,
            messageCount = messages,
            projectId = fields["projectId"]?.jsonPrimitive?.contentOrNull(),
            origin = fields["origin"]?.jsonPrimitive?.contentOrNull(),
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
            // Absent on anything written before it was recorded, and on a turn sent
            // with no character selected. Both render as no byline rather than as a
            // guess taken from whatever is selected now.
            persona = (this["persona"] as? JsonObject)?.asPersona(),
            attachments = parseRemoteAttachments(this["attachments"]),
            hasThinking = this["hasThinking"]?.jsonPrimitive?.contentOrNull() == "true",
            // Read back so a conversation held at the computer shows its sources
            // here too. The desktop has recorded these on every turn for months;
            // this phone simply never looked, so history arrived with `[S1]` in the
            // text and nothing to point it at.
            webSources = parseWebSources(this["webSources"]),
            webSearchAttempted = this["webSearchAttempted"]?.jsonPrimitive?.contentOrNull() == "true",
            // A reply the computer recorded as ending in an error kept what it had done.
            // Desktop 0.9.14 says so as `endedEarly`; the error text stays over there.
            endedEarly = role != "user" && (
                this["endedEarly"]?.jsonPrimitive?.contentOrNull() == "true" ||
                    !this["error"]?.jsonPrimitive?.contentOrNull().isNullOrBlank()
                ),
        )
    }

    private fun JsonObject.asPersona(): MessagePersona? {
        val name = this["name"]?.jsonPrimitive?.contentOrNull() ?: return null
        return MessagePersona(
            id = this["id"]?.jsonPrimitive?.contentOrNull() ?: return null,
            name = name,
            tint = this["tint"]?.jsonPrimitive?.contentOrNull() ?: "accent",
        )
    }

    private companion object {
        const val CHANNEL_SUMMARIES = "conversations:list-summaries"
        const val CHANNEL_GET = "conversations:get"
        const val CHANNEL_ATTACHMENT_PREVIEW = "conversations:attachment-preview"
        const val CHANNEL_SEARCH = "conversations:search"

        /** Archive, in the desktop's own words. See [archive]. */
        const val CHANNEL_ARCHIVE = "conversations:delete"
        const val CHANNEL_RESTORE = "conversations:restore"
        const val CHANNEL_LIST_ARCHIVED = "conversations:list-archived"
        const val CHANNEL_DELETE_PERMANENT = "conversations:delete-permanent"

        /**
         * How much of a transcript to pull when opening one.
         *
         * Enough to scroll back through a long session, small enough that a
         * conversation with thousands of turns and embedded tool output cannot
         * become a frame the phone chokes on.
         */
        const val RECENT_MESSAGES = 200
    }
}

/** What [Conversations.summariesOf] was answered with. */
sealed interface SummaryRead {
    /** The rows asked for that still exist; any missing is archived or gone. */
    data class Rows(val rows: List<ConversationSummary>) : SummaryRead

    /** The whole list, from a computer that does not read rows by id. */
    data class WholeList(val rows: List<ConversationSummary>) : SummaryRead
}

/**
 * The conversation list with [changed] rows brought up to date.
 *
 * [asked] is every id that was read: one asked for and not answered is archived or
 * gone, and leaves the list.
 */
internal fun withChangedRows(
    list: List<ConversationSummary>,
    asked: Collection<String>,
    changed: List<ConversationSummary>,
): List<ConversationSummary> {
    val fresh = changed.associateBy { it.id }
    val kept = list.filterNot { it.id in asked }
    return (kept + fresh.values).sortedByDescending { it.updatedAtEpochMs }
}

/** `content` on a JSON null is the string "null", which is never what a caller wants. */
private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content

/** One conversation as `conversations:get` returns it to the phone. */
data class OpenedConversation(
    val messages: List<ChatMessage>,
    /** Null for a plain chat — which is an answer, not a gap to fill with a guess. */
    val projectId: String?,
    val storedTitle: String?,
    val createdAtEpochMs: Long?,
    /**
     * True when these are all of the conversation's turns: the computer sent fewer
     * than were asked for and dropped none to fit.
     */
    val complete: Boolean = false,
)

/**
 * The fields of an opened conversation, with messages read by [message].
 *
 * Separate from the socket so the one decision that went wrong — reading a null
 * project as "not known" — can be tested on its own.
 */
internal fun parseOpenedConversation(
    conversation: JsonObject,
    limit: Int? = null,
    message: (JsonObject) -> ChatMessage?,
): OpenedConversation {
    fun text(key: String) = (conversation[key] as? JsonPrimitive)?.contentOrNull()?.takeIf { it.isNotBlank() }
    val sent = (conversation["messages"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

    return OpenedConversation(
        messages = sent.mapNotNull(message),
        projectId = text("projectId"),
        storedTitle = text("title"),
        createdAtEpochMs = text("createdAt")?.toDoubleOrNull()?.toLong()?.takeIf { it > 0 },
        complete = limit != null && sent.size < limit && text("partial") != "true",
    )
}

/** A conversation found by something said in it. */
data class MessageMatch(val conversationId: String, val excerpt: String)

/** `conversations:search`'s answer, read leniently: a malformed row is skipped, not fatal. */
internal fun parseMessageMatches(element: kotlinx.serialization.json.JsonElement?): List<MessageMatch> {
    val rows = (element as? JsonArray)
        ?: ((element as? JsonObject)?.get("value") as? JsonArray)
        ?: return emptyList()
    return rows.filterIsInstance<JsonObject>().mapNotNull { row ->
        val id = (row["conversationId"] as? JsonPrimitive)?.contentOrNull() ?: return@mapNotNull null
        val excerpt = (row["excerpt"] as? JsonPrimitive)?.contentOrNull().orEmpty()
        // Plain, like a title: an excerpt is cut from a reply mid-markdown, and on a
        // result row "1. **Inst…" is asterisks rather than emphasis.
        MessageMatch(
            id,
            excerpt.lineSequence().map(::withoutMarkdown).filter { it.isNotBlank() }.joinToString(" "),
        )
    }
}

/** A message's attachments as the computer describes them: name, kind and size, in order. */
internal fun parseRemoteAttachments(element: kotlinx.serialization.json.JsonElement?): List<UploadedFile> =
    (element as? kotlinx.serialization.json.JsonArray).orEmpty()
        .filterIsInstance<JsonObject>()
        .map { item ->
            UploadedFile(
                path = "",
                name = item["name"]?.jsonPrimitive?.contentOrNull() ?: "Attachment",
                sizeBytes = item["sizeBytes"]?.jsonPrimitive?.contentOrNull()?.toDoubleOrNull()?.toLong() ?: 0L,
                isImage = item["kind"]?.jsonPrimitive?.contentOrNull() == "image",
                fromComputer = true,
            )
        }

/** `conversations:attachment-preview`, tolerant of the `{ok, value}` envelope. */
internal fun parseAttachmentPreview(element: kotlinx.serialization.json.JsonElement?): ByteArray? {
    val fields = (element as? JsonObject)?.let { obj ->
        (obj["value"] as? JsonObject) ?: obj.takeIf { it.containsKey("base64") }
    } ?: return null
    val base64 = fields["base64"]?.jsonPrimitive?.contentOrNull() ?: return null
    return runCatching { java.util.Base64.getDecoder().decode(base64) }.getOrNull()?.takeIf { it.isNotEmpty() }
}

/**
 * The sources recorded against a stored turn.
 *
 * Defensive in the same way as everything else read off the wire: an entry with
 * no id or no url is dropped rather than rendered as a link to nowhere, and a
 * missing title falls back to the url, which is a worse label than the page's own
 * and a much better one than an empty row.
 */
private fun parseWebSources(element: JsonElement?): List<WebSource> {
    val list = element as? JsonArray ?: return emptyList()
    return list.mapNotNull { entry ->
        val o = entry as? JsonObject ?: return@mapNotNull null
        val id = o["id"]?.jsonPrimitive?.contentOrNull() ?: return@mapNotNull null
        val url = o["url"]?.jsonPrimitive?.contentOrNull() ?: return@mapNotNull null
        WebSource(
            id = id,
            title = o["title"]?.jsonPrimitive?.contentOrNull()?.takeIf { it.isNotBlank() } ?: url,
            url = url,
            snippet = o["snippet"]?.jsonPrimitive?.contentOrNull()?.takeIf { it.isNotBlank() },
            verified = o["verified"]?.jsonPrimitive?.contentOrNull() == "true",
        )
    }
}
