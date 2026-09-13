package dev.anodex.mobile.chat

import dev.anodex.mobile.transport.AnodexSocket
import dev.anodex.mobile.transport.ServerFrame
import dev.anodex.mobile.workspace.ChangedFile
import dev.anodex.mobile.workspace.Checkpoints
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One tool the model ran, collapsed to a line.
 *
 * On a phone the fact that a file was read matters; its 318 lines do not. The
 * desktop shows the full card and this shows the fact — which is also why a
 * finished call keeps its place in the transcript rather than disappearing: the
 * shape of a turn is most of what makes it readable later.
 */
data class ToolActivity(
    val id: String,
    val name: String,
    val title: String,
    val detail: String?,
    val status: Status,
) {
    enum class Status { RUNNING, DONE, FAILED }
}

/** One turn in the transcript. */
data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String,
    /** True while tokens are still arriving for this message. */
    val streaming: Boolean = false,
    /** Tools this turn ran, in the order they were called. */
    val tools: List<ToolActivity> = emptyList(),
    /**
     * Files sent up with this message.
     *
     * Only ever on a user message, and only ever ones that finished uploading — the
     * rule the whole design rests on is that the computer never sees a
     * message-with-attachment until the attachment is whole.
     */
    val attachments: List<UploadedFile> = emptyList(),
    /**
     * The files this turn actually changed on the computer.
     *
     * Read after the turn ends rather than inferred from the tool rows. The rows
     * say what was attempted; this says what ended up different, which is the only
     * one of the two worth trusting a run on from another room.
     */
    val changedFiles: List<ChangedFile> = emptyList(),
    /**
     * Who actually answered, stamped when the turn was sent.
     *
     * Per message, not per screen. The personality is a global setting, so reading
     * the *current* one to label an *old* reply relabels the whole transcript every
     * time it is changed — which is worse than useless when the reason to look is
     * "which one said this". A message is written by one personality and keeps it.
     *
     * Null where it is genuinely not known: history loaded back from the computer
     * carries no author, because the desktop's conversation store does not record
     * one. Blank is the honest rendering of that; a confident wrong name is not.
     */
    val persona: MessagePersona? = null,
) {
    enum class Role { USER, ASSISTANT }
}

/**
 * Who a reply was written by.
 *
 * Carries the id as well as the name, because the shipped artwork for a built-in
 * is keyed off the identity rather than anything stored — the same rule the
 * desktop follows, so a copy of a built-in does not inherit its face.
 */
data class MessagePersona(val id: String, val name: String, val tint: String)

/**
 * A conversation, driven over the remote socket.
 *
 * The transcript here is **not a cache**. It is what has arrived during this
 * connection, and it is deliberately discarded when the connection ends: the
 * desktop's conversation store is authoritative, and keeping a copy on the phone
 * would soften the guarantee the whole product rests on (handoff §2, §10.1).
 *
 * Tokens arrive as `chat:stream` events, which is why sending and receiving are
 * separate paths: `chat:send` resolves only when the whole turn is finished, and
 * a phone that waited for it would show nothing for thirty seconds.
 */
class ChatSession(
    private val socket: AnodexSocket,
    private val scope: CoroutineScope,
    /**
     * Who is set to answer, read fresh at the moment a turn is sent.
     *
     * A provider rather than a value, because the selection can change during the
     * life of a session and each turn should be stamped with what was in force when
     * it was asked.
     */
    private val activePersona: () -> MessagePersona? = { null },
    /**
     * Which conversation this is.
     *
     * A new id starts a new conversation on the computer; an existing one continues
     * it, and the desktop appends to the same stored transcript the user sees there.
     */
    val conversationId: String = UUID.randomUUID().toString(),
    initialMessages: List<ChatMessage> = emptyList(),
    /**
     * When this conversation began, for an existing one.
     *
     * Passed in rather than stamped on open: re-saving with "now" would overwrite
     * the real creation time of a conversation started days ago on the computer.
     */
    private val createdAt: Long = System.currentTimeMillis(),
    /**
     * The project this turn runs against, or null for plain chat.
     *
     * This is what turns Anodex from a conversation into a coding agent: with a
     * project it reads and writes real files; without one it is just talking. A
     * turn sent with the wrong value does not fail, it quietly does the wrong kind
     * of work, which is why it is passed explicitly rather than defaulted.
     */
    var projectId: String? = null,
    /**
     * The title this conversation already has on the computer, if it has one.
     *
     * Carried for the same reason [createdAt] is: a save writes the whole
     * conversation back, so anything not carried through is silently replaced. The
     * desktop generates a real title from the finished turn, asynchronously — and
     * without this, opening one of those conversations on the phone and sending a
     * single message renamed it to the first forty characters of the first thing
     * the user ever typed.
     */
    /**
     * The title the computer already has for this conversation, if any.
     *
     * Public so the chat header can show it. Null for a conversation that has not
     * been summarised yet, which is normal for the first minute or two — the header
     * falls back to the first thing the user said, exactly as the saved title does.
     */
    val existingTitle: String? = null,
) {
    private val _messages = MutableStateFlow(initialMessages)
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _approval = MutableStateFlow<ToolApproval?>(null)

    /**
     * A tool call waiting for an answer, if any.
     *
     * The highest-value thing the phone does: a blocked run is stopped until
     * somebody answers, and answering from wherever you are is the point of
     * carrying this.
     */
    val approval: StateFlow<ToolApproval?> = _approval.asStateFlow()

    /** Answer a pending approval. Whichever screen answers first settles it. */
    fun respondToApproval(approved: Boolean) {
        val pending = _approval.value ?: return
        _approval.value = null

        scope.launch {
            runCatching {
                socket.invoke(
                    CHANNEL_CONFIRM_RESPONSE,
                    listOf(
                        JsonPrimitive(pending.id),
                        buildJsonObject { put("approved", approved) },
                    ),
                )
            }
        }
    }

    init {
        scope.launch {
            socket.events.collect { event -> onEvent(event) }
        }
    }

    fun send(text: String, attachments: List<UploadedFile> = emptyList()) {
        val trimmed = text.trim()
        // An attachment on its own is a perfectly good message: "look at this" is
        // often the whole of what somebody wants to say.
        if ((trimmed.isEmpty() && attachments.isEmpty()) || _sending.value) return

        val messageId = UUID.randomUUID().toString()
        _error.value = null
        _sending.value = true

        // Built before the new turn is appended: the prompt carries this message, so
        // including it in history too would send it twice.
        val payload = request(messageId, trimmed, attachments)

        // Read now, not when the reply is drawn. Switching personality afterwards
        // changes who answers next, and must not rewrite who answered before.
        val persona = activePersona()

        _messages.value = _messages.value +
            ChatMessage(
                id = messageId,
                role = ChatMessage.Role.USER,
                text = trimmed,
                attachments = attachments,
            ) +
            ChatMessage(
                id = assistantIdFor(messageId),
                role = ChatMessage.Role.ASSISTANT,
                text = "",
                streaming = true,
                persona = persona,
            )

        // On the wire before this function returns, and deliberately not inside the
        // coroutine below.
        //
        // Sending a message and then leaving the app used to lose it. Android can
        // destroy the Activity while the process lives on — under memory pressure,
        // or simply after a while away — and that clears the ViewModel, which
        // cancels `viewModelScope`, which cancelled this turn before it had sent
        // anything. "Thinking…" was already on screen, because the placeholder above
        // is appended synchronously, so the app looked like it was working on a
        // message the desktop had never heard of.
        //
        // `enqueue` does not suspend: it hands the frame to OkHttp's writer and
        // returns, and OkHttp transmits queued frames before any close. So once this
        // line has run the prompt is the desktop's problem, and it will finish the
        // turn whether or not this phone is still watching — the conversation lives
        // there, and re-opening it reads the answer back.
        val callId = try {
            socket.enqueue(CHANNEL_SEND, listOf(payload))
        } catch (e: Exception) {
            // Nothing was sent, so the optimistic turn above is a lie. Take it back
            // rather than leaving a question on screen that nobody was asked.
            _messages.value = _messages.value.filterNot {
                it.id == messageId || it.id == assistantIdFor(messageId)
            }
            _error.value = e.message ?: "That didn't reach your computer."
            _sending.value = false
            return
        }

        scope.launch {
            // A turn is answered when it is finished, and a real one reads files,
            // searches, and thinks between them. The default sixty seconds is a
            // sensible bound for a question with an answer and a hopeless one for a
            // turn doing work — it killed turns that were visibly still going, while
            // tokens were arriving on screen.
            //
            // What matters is not how long it takes but whether anything is still
            // happening, so the bound is on silence instead. Every token and every
            // tool call resets it; nothing for IDLE_LIMIT means the far end really
            // has stopped.
            lastActivityAt = System.currentTimeMillis()
            var wentQuiet = false

            val call = async { socket.awaitResult(callId, TURN_CAP) }

            val watchdog = launch {
                while (isActive) {
                    delay(IDLE_POLL)
                    if (System.currentTimeMillis() - lastActivityAt > IDLE_LIMIT.inWholeMilliseconds) {
                        wentQuiet = true
                        call.cancel()
                        break
                    }
                }
            }

            try {
                call.await()
            } catch (e: CancellationException) {
                // Only ours is worth reporting. A cancellation from the scope going
                // away means the screen is gone and there is nobody to tell.
                if (wentQuiet) {
                    _error.value = "Your computer went quiet for " +
                        "${IDLE_LIMIT.inWholeMinutes} minutes. The turn may still be " +
                        "running there."
                } else {
                    throw e
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "That didn't reach your computer."
            } finally {
                watchdog.cancel()
                _sending.value = false

                // `NonCancellable`, and the conversation depends on it.
                //
                // A `finally` block runs when a coroutine is cancelled, but every
                // *suspension point* inside it throws immediately — so `persist`,
                // which is a call over the socket, never got past its first suspend
                // when the scope died. The turn was on the phone's screen and had
                // never been written to the computer, which is where conversations
                // actually live. Nothing was corrupt; the save simply never happened,
                // and the phone had no way to know.
                //
                // The desktop merges a remote save rather than overwriting, and
                // announces it so an open window picks it up, so finishing this is
                // both safe and the whole point.
                withContext(NonCancellable) {
                    finishStreaming()
                    recordChangedFiles(messageId)
                    persist()
                }
            }
        }
    }

    /**
     * Ask the same question again.
     *
     * Resends the user message that produced [assistantMessageId] as a new turn,
     * rather than replacing the reply in place. The desktop has no regenerate
     * channel, so there is nothing to ask it to redo — and a new turn is the honest
     * account of what happened anyway: the first answer was given, and then another
     * was asked for. Both stay in the transcript, which is also what makes them
     * comparable.
     */
    fun retry(assistantMessageId: String) {
        if (_sending.value) return

        val history = _messages.value
        val index = history.indexOfFirst { it.id == assistantMessageId }
        if (index <= 0) return

        val question = history
            .take(index)
            .lastOrNull { it.role == ChatMessage.Role.USER }
            ?: return

        send(question.text)
    }

    /**
     * Stop the turn that is running.
     *
     * A generation on the phone is a generation on the computer, and one that has
     * gone wrong can burn a long time and a lot of tokens before it stops on its
     * own. Being able to end it from wherever you are is most of why the stop
     * button matters more here than on the desktop, where the machine is in front
     * of you anyway.
     *
     * Deliberately not awaited for a result: the desktop aborts and the stream ends,
     * and blocking the UI on the acknowledgement would be slower than the thing it
     * is acknowledging.
     */
    fun stop() {
        if (!_sending.value) return
        scope.launch {
            runCatching { socket.invoke(CHANNEL_STOP, listOf(JsonPrimitive(conversationId))) }
            _sending.value = false
            finishStreaming()
        }
    }

    /** This session's turn, as [chatRequest] shapes it. */
    private fun request(
        messageId: String,
        text: String,
        attachments: List<UploadedFile>,
    ): JsonObject = chatRequest(
        conversationId = conversationId,
        messageId = messageId,
        prompt = text,
        history = historyForRequest(),
        projectId = projectId,
        attachments = attachments,
    )

    /**
     * The turns so far, as the desktop's ChatHistoryTurn.
     *
     * Excludes the turn being sent and the empty assistant placeholder waiting to
     * be streamed into — the prompt carries the former, and the latter has no
     * content yet.
     */
    private fun historyForRequest(): JsonArray = buildJsonArray {
        for (message in _messages.value) {
            if (message.text.isBlank()) continue
            add(
                buildJsonObject {
                    put("id", message.id)
                    put("role", if (message.role == ChatMessage.Role.USER) "user" else "assistant")
                    put("content", message.text)
                },
            )
        }
    }

    /**
     * When this conversation last heard anything from the computer.
     *
     * The send watchdog reads it. A turn is allowed to take as long as it takes,
     * provided it is still saying something.
     */
    private var lastActivityAt = 0L

    private fun onEvent(event: ServerFrame.Event) {
        when (event.channel) {
            CHANNEL_STREAM -> {
                val payload = event.payload?.jsonObject ?: return
                if (payload["conversationId"]?.jsonPrimitive?.content != conversationId) return
                lastActivityAt = System.currentTimeMillis()
                appendToken(payload["token"]?.jsonPrimitive?.content ?: return)
            }

            CHANNEL_ACTIVITY -> {
                val payload = event.payload?.jsonObject ?: return
                if (payload["conversationId"]?.jsonPrimitive?.content != conversationId) return
                val call = payload["call"]?.jsonObject ?: return
                lastActivityAt = System.currentTimeMillis()
                onToolCall(call)
            }

            CHANNEL_CONFIRM_REQUEST -> {
                val request = parseToolApproval(event.payload) ?: return
                // Prompts for other conversations belong to whoever opened them.
                if (request.conversationId.isNotEmpty() &&
                    request.conversationId != conversationId
                ) {
                    return
                }
                _approval.value = request
            }

            CHANNEL_CONFIRM_CANCELLED -> {
                // Answered on the computer, aborted, or expired. Either way the card
                // here is dead: its buttons would settle nothing.
                val cancelledId = (event.payload as? JsonPrimitive)?.content
                if (cancelledId == null || cancelledId == _approval.value?.id) {
                    _approval.value = null
                }
            }
        }
    }

    /**
     * Fold a tool call into the turn being streamed.
     *
     * Matched by id and replaced in place, because the desktop sends the same call
     * again as it progresses - running, then done. Appending each update instead
     * would turn one file read into three identical-looking lines.
     */
    private fun onToolCall(call: kotlinx.serialization.json.JsonObject) {
        fun field(key: String) = (call[key] as? JsonPrimitive)?.content

        val id = field("id") ?: return
        val activity = ToolActivity(
            id = id,
            name = field("name") ?: "tool",
            title = field("title") ?: field("name") ?: "Ran a tool",
            detail = field("detail")?.takeIf { it.isNotBlank() },
            status = when (field("status")) {
                "running", "pending" -> ToolActivity.Status.RUNNING
                "error", "failed", "denied" -> ToolActivity.Status.FAILED
                else -> ToolActivity.Status.DONE
            },
        )

        _messages.value = _messages.value.map { message ->
            if (message.role == ChatMessage.Role.ASSISTANT && message.streaming) {
                val existing = message.tools.indexOfFirst { it.id == id }
                val tools = if (existing >= 0) {
                    message.tools.toMutableList().apply { this[existing] = activity }
                } else {
                    message.tools + activity
                }
                message.copy(tools = tools)
            } else {
                message
            }
        }
    }

    private fun appendToken(token: String) {
        _messages.value = _messages.value.map { message ->
            if (message.role == ChatMessage.Role.ASSISTANT && message.streaming) {
                message.copy(text = message.text + token)
            } else {
                message
            }
        }
    }

    private fun finishStreaming() {
        _messages.value = _messages.value.map {
            if (it.streaming) it.copy(streaming = false) else it
        }
    }

    /**
     * Write the turn back to the computer.
     *
     * `chat:send` generates a reply; it does not save one. The desktop's own
     * renderer persists separately, through `conversations:save` - so a
     * conversation started on the phone was generated, streamed, and then lost the
     * moment the socket closed, never appearing in the list on either device.
     *
     * Best-effort on purpose: a failed save is worth reporting but must not throw
     * away a reply the user is already reading.
     */
    private suspend fun persist() {
        // A picture sent with no caption is still a turn. Filtering on text alone
        // dropped it from the save entirely, so sending an image and nothing else
        // wrote a conversation that did not contain it.
        val turns = _messages.value.filter { it.text.isNotBlank() || it.attachments.isNotEmpty() }
        if (turns.isEmpty()) return

        val now = System.currentTimeMillis()
        val conversation = buildJsonObject {
            put("id", conversationId)
            // Whatever this turn actually ran against, so the conversation is filed
            // where the work happened rather than somewhere it did not.
            put("projectId", projectId?.let(::JsonPrimitive) ?: JsonNull)
            put("title", titleToSave(existingTitle, turns))
            put("createdAt", createdAt)
            put("updatedAt", now)
            put(
                "messages",
                buildJsonArray {
                    for (turn in turns) {
                        add(
                            buildJsonObject {
                                put("id", turn.id)
                                put(
                                    "role",
                                    if (turn.role == ChatMessage.Role.USER) "user" else "assistant",
                                )
                                put("content", turn.text)
                                put("createdAt", now)

                                // Recorded with the message, so months later it is
                                // still possible to tell which personality wrote a
                                // reply — rather than reading whichever one happens
                                // to be selected when somebody looks.
                                // What the desktop draws in the transcript. Sent
                                // separately from the request's `userFiles`, which
                                // exists so the model can look at the picture and is
                                // not part of what gets stored — so a phone that sent
                                // only that produced a message the computer had no
                                // record of any attachment on, and showed nothing.
                                //
                                // Bytes are deliberately not included: the desktop
                                // re-reads the file from this path, which is where its
                                // own upload handler already put it.
                                if (turn.attachments.isNotEmpty()) {
                                    put(
                                        "attachments",
                                        buildJsonArray {
                                            for (file in turn.attachments) {
                                                add(
                                                    buildJsonObject {
                                                        put("path", file.path)
                                                        put("name", file.name)
                                                        put("sizeBytes", file.sizeBytes)
                                                        put(
                                                            "kind",
                                                            if (file.isImage) "image" else "text",
                                                        )
                                                    },
                                                )
                                            }
                                        },
                                    )
                                }

                                turn.persona?.let { persona ->
                                    put(
                                        "persona",
                                        buildJsonObject {
                                            put("id", persona.id)
                                            put("name", persona.name)
                                            put("tint", persona.tint)
                                        },
                                    )
                                }
                            },
                        )
                    }
                },
            )
        }

        runCatching { socket.invoke(CHANNEL_SAVE, listOf(conversation)) }
            .onFailure { _error.value = "Saved on your computer failed: ${it.message}" }
    }

    /**
     * Ask what the turn changed, and hang it on the reply.
     *
     * Only for a turn that ran inside a project — without one there are no files to
     * change and the computer has no checkpoint to answer with.
     *
     * Keyed on the *user* message id: that is what the desktop records changes
     * against while the turn runs, not the `:reply` id this app gives the answer.
     *
     * Best effort. A turn that wrote nothing has no checkpoint at all, which is the
     * ordinary case rather than a failure worth putting on screen.
     */
    private suspend fun recordChangedFiles(messageId: String) {
        val project = projectId ?: return
        val changed = runCatching {
            checkpoints.changedBy(project, conversationId, messageId)
        }.getOrDefault(emptyList())

        if (changed.isEmpty()) return

        val replyId = assistantIdFor(messageId)
        _messages.value = _messages.value.map {
            if (it.id == replyId) it.copy(changedFiles = changed) else it
        }
    }

    private val checkpoints = Checkpoints(socket)

    private fun assistantIdFor(messageId: String) = "$messageId:reply"

    private companion object {
        const val CHANNEL_SEND = "chat:send"
        const val CHANNEL_STREAM = "chat:stream"
        const val CHANNEL_ACTIVITY = "tools:activity"
        const val CHANNEL_CONFIRM_REQUEST = "tools:confirm-request"
        const val CHANNEL_CONFIRM_CANCELLED = "tools:confirm-cancelled"
        const val CHANNEL_CONFIRM_RESPONSE = "tools:confirm-response"
        const val CHANNEL_SAVE = "conversations:save"
        const val CHANNEL_STOP = "chat:stop"

        /**
         * How long the computer may say nothing before the turn is given up on.
         *
         * Generous, because thinking is silent: a large model on a long context can
         * be a couple of minutes between one tool finishing and the first token of
         * what it decided.
         */
        val IDLE_LIMIT = 5.minutes

        /** How often the watchdog looks. Cheap, so it can be often enough to feel prompt. */
        val IDLE_POLL = 10.seconds

        /**
         * A ceiling regardless, so a turn cannot be waited on for ever.
         *
         * The idle bound is the one that fires in practice; this only catches a turn
         * that keeps producing output and never finishes.
         */
        val TURN_CAP = 2.hours
    }
}

/**
 * The title to write back when saving a conversation.
 *
 * A save writes the whole conversation, so this is the only thing standing between
 * the phone and the computer's own title. The desktop summarises a finished turn
 * into a real title, asynchronously — and before this existed, opening one of those
 * conversations on the phone and sending a single message renamed it to the first
 * forty characters of the first thing the user ever typed.
 *
 * @param existing the title the computer already stores, or null if it has none yet.
 */
internal fun titleToSave(existing: String?, turns: List<ChatMessage>): String =
    existing?.takeIf { it.isNotBlank() } ?: titleFromFirstTurn(turns)

/**
 * A first-line title, so a new conversation is findable before the desktop
 * summarises it properly.
 *
 * Only ever used for a conversation that has no title yet. It beats a row reading
 * "Untitled" for the minute or two before the real one arrives.
 */
internal fun titleFromFirstTurn(turns: List<ChatMessage>): String {
    val first = turns.firstOrNull { it.role == ChatMessage.Role.USER }?.text ?: return "New chat"
    val line = first.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return when {
        line.isEmpty() -> "New chat"
        line.length <= MAX_TITLE_LENGTH -> line
        else -> line.take(MAX_TITLE_LENGTH).trimEnd() + "…"
    }
}

/** Long enough to identify a conversation, short enough for a phone-width row. */
internal const val MAX_TITLE_LENGTH = 60

/**
 * A ChatRequest, shaped from `protocol/anodex-protocol.json` rather than from memory.
 *
 * The first version of this sent `content` and no `history`, which the desktop
 * rejected outright: the required fields are `prompt` and `history`. Reading the
 * generated contract is the entire reason it exists, and hand-writing the shape is
 * exactly the mistake it is there to prevent.
 *
 * History is sent because the desktop is stateless per call — it holds the
 * conversation on disk, but this request is what a turn is generated from, so
 * omitting it means every message arrives with no memory of the last one.
 *
 * Free of the session so the shape can be asserted without a socket. Every defect
 * this request has had was a defect in its *shape*, and a shape that can only be
 * observed by connecting to a computer is one nobody checks.
 */
internal fun chatRequest(
    conversationId: String,
    messageId: String,
    prompt: String,
    history: JsonArray,
    projectId: String?,
    attachments: List<UploadedFile> = emptyList(),
): JsonObject = buildJsonObject {
    put("conversationId", conversationId)
    put("messageId", messageId)
    put("prompt", prompt)
    put("history", history)

    // Explicitly, even when there is no project. The desktop distinguishes an absent
    // key from a null one: absent means "whatever project is open at the computer",
    // null means "none". Omitting it sent every plain chat started on the phone into
    // whichever project the desk happened to be sitting in — the conversation filed
    // itself under Chats correctly, and then answered as though it were inside that
    // project's workspace.
    put("projectId", projectId?.let(::JsonPrimitive) ?: JsonNull)

    // Paths on the *computer*, where the bytes already are. The desktop turns an
    // image among these back into something the model can look at, so the file does
    // not travel a second time as base64 inside this request.
    if (attachments.isNotEmpty()) {
        put(
            "userFiles",
            buildJsonArray {
                for (file in attachments) {
                    add(
                        buildJsonObject {
                            put("path", file.path)
                            put("name", file.name)
                        },
                    )
                }
            },
        )
    }
}
