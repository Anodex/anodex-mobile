package dev.anodex.mobile.chat

import dev.anodex.mobile.transport.AnodexSocket
import dev.anodex.mobile.transport.contentOrNullish
import dev.anodex.mobile.transport.ServerFrame
import dev.anodex.mobile.workspace.ChangedFile
import dev.anodex.mobile.workspace.TurnDiffResult
import dev.anodex.mobile.workspace.Checkpoints
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
    /** Written while the computer was unreachable, and not sent yet. */
    val queued: Boolean = false,
    /**
     * What the model thought before it answered, as far as this phone has it.
     *
     * Only a reasoning model writes any. Null when there is none, and also when there
     * is some on the computer that has not been read yet — see [hasThinking].
     */
    val thinking: String? = null,
    /**
     * The computer has thinking saved for this reply that is not in [thinking] yet.
     *
     * History arrives without it, because it is often longer than the reply, and it is
     * read when somebody opens it.
     */
    val hasThinking: Boolean = false,
    /**
     * The reply stopped partway — out of room, out of time, or the model failed — rather
     * than finishing or being stopped by the user. What it did is kept, so it can be
     * continued rather than only asked again.
     */
    val endedEarly: Boolean = false,
    /**
     * The pages this answer stood on, in the order the model first met them.
     *
     * The computer sends these with every turn and this phone threw them away.
     * `chat:send` answers with `webSources`, the desktop's renderer turns `[S1]`
     * into a link to the first of them and lists the rest under the reply -- and
     * the phone neither read the field nor saved it. So a turn made here was
     * stored with markers in the text and no sources behind them, and then the
     * *desktop* drew that conversation with dead numbers too, because it was
     * faithfully rendering what the phone had written. Found on 2026-09-17 in the
     * owner's own transcripts: 915 assistant messages, 3 carrying `[S..]` markers,
     * and not one of those three carrying a source.
     */
    val webSources: List<WebSource> = emptyList(),
    /**
     * A web tool ran for this turn.
     *
     * Kept apart from the list being empty, because the two mean opposite things.
     * No sources and no attempt is an ordinary answer. No sources *after* an
     * attempt means the model looked, found nothing, and answered from training
     * data anyway -- which a confident reply gives the reader no way to detect.
     */
    val webSearchAttempted: Boolean = false,
) {
    enum class Role { USER, ASSISTANT }
}

/**
 * One page an answer stood on.
 *
 * [id] is the marker the model writes in the text -- `S1`, `S2` -- minted per turn
 * by the computer's `WebSourceRegistry` and stable for that turn. [verified] means
 * the page was actually fetched rather than only appearing in a result list, which
 * is the difference between a source and a lead and is worth showing as such.
 */
data class WebSource(
    val id: String,
    val title: String,
    val url: String,
    val snippet: String? = null,
    val verified: Boolean = false,
)

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
     * Whether [initialMessages] came from the computer. False for turns this phone is
     * holding that the computer may not have saved yet, so the next save sends them.
     */
    initialMessagesSaved: Boolean = true,
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
    existingTitle: String? = null,
    /**
     * A turn this session sent has been answered, after its save.
     *
     * Called from the session's own scope, so it still fires for a turn whose screen
     * has moved on to another conversation — which is exactly when somebody needs to
     * be told.
     */
    private val onAnswered: (session: ChatSession, reply: ChatMessage) -> Unit = { _, _ -> },
    /**
     * A temporary chat: nothing about it is kept on the computer.
     *
     * Each turn asks the computer not to record it or remember anything from it, and
     * this session never saves the conversation or asks for a title. Close it and it
     * is gone.
     */
    val temporary: Boolean = false,
    /** An approval already waiting in this conversation when it is opened. See [PendingApprovals]. */
    initialApproval: ToolApproval? = null,
    /** This session answered an approval, so nothing else goes on offering it. */
    private val onApprovalAnswered: (id: String) -> Unit = {},
) {
    private val _title = MutableStateFlow(initialTitle(existingTitle, initialMessages.isNotEmpty()))

    /**
     * The computer's title for this conversation, once there is one.
     *
     * Starts as whatever the computer already stored and changes once, when a
     * conversation begun here is named after its first reply — see [nameAfterFirstReply].
     */
    val title: StateFlow<String?> = _title.asStateFlow()

    private val _messages = MutableStateFlow(initialMessages)
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _waitingForComputer = MutableStateFlow(false)

    /**
     * True while this turn is queued behind other work on the computer's model.
     *
     * The desktop says so with `chat:working`. Without it a question sent while an
     * agent run held the model showed "Thinking…" for five minutes and then gave up,
     * which is indistinguishable from a computer that has died.
     */
    val waitingForComputer: StateFlow<Boolean> = _waitingForComputer.asStateFlow()

    private val _reading = MutableStateFlow<ReadingProgress?>(null)

    /**
     * How far the computer's model has read this turn's prompt, while it reads.
     *
     * On a local model that read is most of the wait before any words: a long
     * conversation is read in full before the reply starts. Null when nothing is
     * being read, or on a computer too old to say.
     */
    val reading: StateFlow<ReadingProgress?> = _reading.asStateFlow()

    private val _approval = MutableStateFlow(initialApproval?.takeIf { it.conversationId == conversationId })

    /**
     * A tool call waiting for an answer, if any.
     *
     * The highest-value thing the phone does: a blocked run is stopped until
     * somebody answers, and answering from wherever you are is the point of
     * carrying this.
     */
    val approval: StateFlow<ToolApproval?> = _approval.asStateFlow()

    /**
     * An approval found waiting in this conversation after it opened — asked for when a
     * connection came back. Shown unless one is on screen already.
     */
    fun offerApproval(approval: ToolApproval) {
        if (approval.conversationId != conversationId || _approval.value != null) return
        _approval.value = approval
    }

    /** Answer a pending approval. Whichever screen answers first settles it. */
    fun respondToApproval(approved: Boolean) {
        val pending = _approval.value ?: return
        _approval.value = null
        onApprovalAnswered(pending.id)

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

    /** What this session hears from the computer, until it is closed. */
    private val collector = scope.launch {
        socket.events.collect { event -> onEvent(event) }
    }

    @Volatile private var closed = false

    /**
     * This session is no longer the one on screen.
     *
     * Stops it listening, once any turn it is running has finished — that turn still
     * saves, and still says it was answered. A session left listening after it was
     * replaced went on reading every token of every turn for as long as the app ran,
     * one more for each conversation opened.
     */
    fun close() {
        closed = true
        if (!_sending.value) collector.cancel()
    }

    /**
     * Ids of the turns the computer already has.
     *
     * The ones this session opened with came from it, and every save adds what it
     * sent. A save then carries only what is new: the computer merges a phone's save
     * into what it holds rather than replacing it, so sending two hundred turns it
     * already has, to add two, uploaded the whole transcript after every reply.
     */
    private val confirmedIds: MutableSet<String> = java.util.Collections.synchronizedSet(
        if (initialMessagesSaved) initialMessages.mapTo(HashSet()) { it.id } else HashSet(),
    )

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
            var answered = false

            // The failure is kept inside the `async` and rethrown at `await` below. An
            // `async` that throws fails the coroutine around it even when `await` is in
            // a try block, and from there the whole app: a connection dropping while an
            // answer was still arriving closed Anodex, because the socket fails every
            // call still waiting on it with the network error.
            val call = async { runCatching { socket.awaitResult(callId, TURN_CAP) } }

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
                val result = call.await().getOrThrow()
                // The finished turn carries its whole thinking, which this phone was not
                // sent as it was written unless somebody had it open.
                thinkingFromResult(result)?.let { setThinking(assistantIdFor(messageId), it) }
                if (endedEarlyFromResult(result)) markEndedEarly(assistantIdFor(messageId))
                setWebSources(
                    assistantIdFor(messageId),
                    webSourcesFromResult(result),
                    webSearchAttemptedFromResult(result),
                )
                answered = true
            } catch (e: CancellationException) {
                // Only ours is worth reporting. A cancellation from the scope going
                // away means the screen is gone and there is nobody to tell.
                if (wentQuiet) {
                    // Said as what is known, and what to do. A computer on a current
                    // build keeps the answer to a turn the phone stopped waiting for,
                    // so the chat is worth opening again; and a turn that truly died
                    // is one tap from being asked again with Retry.
                    _error.value = "Nothing from your computer for " +
                        "${IDLE_LIMIT.inWholeMinutes} minutes. It may still be working " +
                        "— open this chat again later, or tap Retry below."
                } else {
                    throw e
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "That didn't reach your computer."
            } finally {
                watchdog.cancel()
                _sending.value = false
                _waitingForComputer.value = false
                _reading.value = null

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

            // After the save, and outside `NonCancellable`: the turn is safe on the
            // computer by now, and a title is worth a model call only while somebody
            // is still here to read it.
            if (answered) {
                _messages.value.firstOrNull { it.id == assistantIdFor(messageId) }
                    ?.takeIf { it.text.isNotBlank() }
                    ?.let { onAnswered(this@ChatSession, it) }
                nameAfterFirstReply(messageId)
            }
            if (closed) collector.cancel()
        }
    }

    /**
     * Ask the computer to name a conversation that was started here.
     *
     * The desktop window names its own chats after the first reply, but that happens
     * in its renderer, on the path its own composer takes. A turn sent from the phone
     * never passes through there, so every conversation begun on a phone kept the
     * first sixty characters of whatever was typed — or pasted — for good. The drawer
     * showed one as "Yes. Here is the **single combined master pr…".
     *
     * `chat:title` is the same call the window makes, reachable from a paired phone,
     * and it answers null when no model is loaded or the model produced nothing
     * usable. Null leaves the first-line title in place, which is what it was before.
     */
    /**
     * Call this conversation something else, here and on the computer.
     *
     * Saved through the same write as a turn, which the computer merges rather than
     * overwrites, so a rename can never cost a message. A conversation with nothing
     * in it yet keeps the name and writes it with its first turn — and is not then
     * renamed from under the person who chose it, because [nameAfterFirstReply] only
     * names a conversation that has no title.
     */
    fun rename(newTitle: String) {
        val trimmed = newTitle.trim().takeIf { it.isNotEmpty() } ?: return
        _title.value = trimmed.take(MAX_TITLE_LENGTH)
        scope.launch { withContext(NonCancellable) { persist() } }
    }

    private suspend fun nameAfterFirstReply(messageId: String) {
        if (temporary || _title.value != null) return

        val turns = _messages.value
        val question = turns.firstOrNull { it.id == messageId } ?: return
        // Only the first exchange. A later turn in an untitled conversation is one the
        // computer has already declined to name, and asking again after every message
        // would queue a model call behind each of them.
        if (turns.firstOrNull { it.role == ChatMessage.Role.USER }?.id != messageId) return
        val reply = turns.firstOrNull { it.id == assistantIdFor(messageId) } ?: return
        if (reply.text.isBlank()) return

        val named = runCatching {
            socket.invoke(CHANNEL_TITLE, listOf(titleRequest(question, reply)))
        }.getOrNull().let(::titleFromReply) ?: return

        _title.value = named
        withContext(NonCancellable) { persist() }
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
    /**
     * Ask an edited version of one of your questions in place of the original.
     *
     * The computer cuts the conversation back to just before it (`conversations:branch-for-edit`)
     * and the edited question is sent from there, so what followed the original goes —
     * as editing does on the computer, and in ChatGPT and Claude.
     *
     * Sent as a new question instead, with a note saying so, when the computer refuses:
     * in a project chat whose later replies may have changed files (only the computer can
     * roll those back), or on a computer too old to edit from a phone. A conversation the
     * computer has never saved — a temporary chat, or one still on its first turn — is
     * simply cut here.
     */
    fun editAndResend(messageId: String, text: String) {
        if (_sending.value || text.isBlank()) return
        val index = _messages.value.indexOfFirst { it.id == messageId && it.role == ChatMessage.Role.USER }
        if (index < 0) {
            send(text)
            return
        }

        scope.launch {
            val outcome = if (temporary) {
                EditOutcome.CUT
            } else {
                runCatching {
                    socket.invoke(CHANNEL_BRANCH_FOR_EDIT, listOf(JsonPrimitive(conversationId), JsonPrimitive(messageId)))
                }.fold(onSuccess = ::editOutcomeOf, onFailure = { EditOutcome.UNSUPPORTED })
            }

            if (outcome == EditOutcome.CUT) _messages.value = _messages.value.take(index)
            send(text)
            // After sending, which clears the error line as a turn starts.
            _error.value = when (outcome) {
                EditOutcome.CUT -> return@launch
                EditOutcome.PROJECT_CHAT ->
                    "Sent as a new message. Editing an earlier message in a project chat is done on the " +
                        "computer, where any file changes after it can be rolled back."
                EditOutcome.REFUSED -> "Sent as a new message — that one couldn't be edited in place."
                EditOutcome.UNSUPPORTED ->
                    "Sent as a new message. Update Anodex on your computer to edit messages in place."
            }
        }
    }

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
        temporary = temporary,
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
                _waitingForComputer.value = false
                _reading.value = null
                appendToken(payload["token"]?.jsonPrimitive?.content ?: return)
            }

            // Sent by a current computer only while somebody has the thinking open —
            // first everything so far, marked `replace`, then the rest as it is written.
            // It is also the model working: ignoring it let a model that thinks for a
            // while before answering trip the five-minute silence limit.
            CHANNEL_THINKING -> {
                val payload = event.payload?.jsonObject ?: return
                if (payload["conversationId"]?.jsonPrimitive?.content != conversationId) return
                lastActivityAt = System.currentTimeMillis()
                _waitingForComputer.value = false
                _reading.value = null
                val token = (payload["token"] as? JsonPrimitive)?.contentOrNull ?: return
                val replace = (payload["replace"] as? JsonPrimitive)?.contentOrNull == "true"
                appendThinking(token, replace)
            }

            CHANNEL_WORKING -> {
                val phase = workingPhase(event.payload, conversationId) ?: return
                lastActivityAt = System.currentTimeMillis()
                _waitingForComputer.value = phase == WorkingPhase.WAITING_FOR_MODEL
                if (phase == WorkingPhase.READING) _reading.value = readingProgressOf(event.payload)
            }

            CHANNEL_ACTIVITY -> {
                val payload = event.payload?.jsonObject ?: return
                if (payload["conversationId"]?.jsonPrimitive?.content != conversationId) return
                val call = payload["call"]?.jsonObject ?: return
                lastActivityAt = System.currentTimeMillis()
                _waitingForComputer.value = false
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

    private fun markEndedEarly(messageId: String) {
        _messages.value = _messages.value.map { if (it.id == messageId) it.copy(endedEarly = true) else it }
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

    private fun appendThinking(token: String, replace: Boolean) {
        _messages.value = _messages.value.map { message ->
            if (message.role == ChatMessage.Role.ASSISTANT && message.streaming) {
                message.copy(thinking = if (replace) token else message.thinking.orEmpty() + token)
            } else {
                message
            }
        }
    }

    private fun setThinking(messageId: String, text: String) {
        _messages.value = _messages.value.map {
            if (it.id == messageId) it.copy(thinking = text, hasThinking = false) else it
        }
    }

    /**
     * Read one reply's thinking from the computer, for a reply that has some saved.
     *
     * Asked for when somebody opens it rather than sent with the conversation, because
     * it is often longer than the reply. A read that fails leaves it unread, so
     * opening it again tries again.
     */
    fun loadThinking(messageId: String) {
        val message = _messages.value.firstOrNull { it.id == messageId } ?: return
        if (!message.hasThinking || message.thinking != null) return
        scope.launch {
            val answer = runCatching {
                socket.invoke(CHANNEL_READ_THINKING, listOf(JsonPrimitive(conversationId), JsonPrimitive(messageId)))
            }.getOrElse { return@launch }
            val text = thinkingTextOf(answer)
            _messages.value = _messages.value.map {
                if (it.id == messageId) it.copy(thinking = text.orEmpty(), hasThinking = false) else it
            }
        }
    }

    /**
     * Bring this conversation up to date with the computer's copy, in place.
     *
     * [tail] is the computer's newest turns. What this session holds from before the
     * first of them is kept, and from there the computer's version replaces this one —
     * so a reply finished at the desk, or turns cut back by an edit there, arrive
     * without the whole conversation being read again. Returns false when the two do
     * not overlap, which means the whole conversation has to be read instead.
     *
     * [complete] says [tail] is the whole conversation, and then it simply replaces.
     */
    fun syncFromComputer(tail: OpenedConversation, complete: Boolean): Boolean {
        if (_sending.value) return true
        val current = _messages.value
        val merged = if (complete) {
            tail.messages
        } else {
            val first = tail.messages.firstOrNull() ?: return false
            val at = current.indexOfFirst { it.id == first.id }
            if (at < 0) return false
            current.take(at) + tail.messages
        }
        val known = current.associateBy { it.id }
        _messages.value = merged.map { turn -> keepWhatOnlyThisPhoneHas(known[turn.id], turn) }
        merged.mapTo(confirmedIds) { it.id }
        _title.value = titleFromComputer(tail.storedTitle, _title.value)
        return true
    }

    private fun finishStreaming() {
        _messages.value = _messages.value.map {
            if (it.streaming) it.copy(streaming = false) else it
        }
    }

    /**
     * Attach a finished turn's sources to its reply.
     *
     * Both fields together, because "no sources" and "no sources after looking"
     * are different statements and storing only the list collapses them.
     */
    private fun setWebSources(id: String, sources: List<WebSource>, attempted: Boolean) {
        if (sources.isEmpty() && !attempted) return
        _messages.value = _messages.value.map {
            if (it.id == id) it.copy(webSources = sources, webSearchAttempted = attempted) else it
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
        if (temporary) return
        // A picture sent with no caption is still a turn. Filtering on text alone
        // dropped it from the save entirely, so sending an image and nothing else
        // wrote a conversation that did not contain it.
        val turns = _messages.value.filter { it.text.isNotBlank() || it.attachments.isNotEmpty() }
        if (turns.isEmpty()) return
        val toSend = turnsToSave(turns, confirmedIds.toSet())

        val now = System.currentTimeMillis()
        val conversation = buildJsonObject {
            put("id", conversationId)
            // Whatever this turn actually ran against, so the conversation is filed
            // where the work happened rather than somewhere it did not.
            put("projectId", projectId?.let(::JsonPrimitive) ?: JsonNull)
            put("title", titleToSave(_title.value, turns))
            put("createdAt", createdAt)
            put("updatedAt", now)
            put(
                "messages",
                buildJsonArray {
                    for (turn in toSend) {
                        add(
                            buildJsonObject {
                                put("id", turn.id)
                                put(
                                    "role",
                                    if (turn.role == ChatMessage.Role.USER) "user" else "assistant",
                                )
                                put("content", turn.text)
                                put("createdAt", now)

                                // The pages the reply stood on, in the desktop's own
                                // shape. Left out until now, which is how a turn made
                                // on the phone came to be stored with `[S1]` in the
                                // text and nothing behind it -- and why the *desktop*
                                // then drew that conversation with dead numbers as
                                // well, since it renders what is saved. Both fields,
                                // because an empty list after an attempt is the
                                // reader's warning that the answer came from training
                                // data, and dropping it as "falsy" loses that.
                                if (turn.webSources.isNotEmpty()) {
                                    put(
                                        "webSources",
                                        buildJsonArray {
                                            for (source in turn.webSources) {
                                                add(
                                                    buildJsonObject {
                                                        put("id", source.id)
                                                        put("title", source.title)
                                                        put("url", source.url)
                                                        source.snippet?.let { put("snippet", it) }
                                                        put("verified", source.verified)
                                                    },
                                                )
                                            }
                                        },
                                    )
                                }
                                if (turn.webSearchAttempted) put("webSearchAttempted", true)

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
                                // Only what this phone sent. An attachment read back from the
                                // computer has no path here, and the computer already has it.
                                val sentHere = turn.attachments.filterNot { it.fromComputer }
                                if (sentHere.isNotEmpty()) {
                                    put(
                                        "attachments",
                                        buildJsonArray {
                                            for (file in sentHere) {
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
            .onSuccess { toSend.mapTo(confirmedIds) { it.id } }
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
    /**
     * Fill in what earlier turns changed, for a conversation read off the computer.
     *
     * [recordChangedFiles] fills this as a turn ends, and until now it was the only
     * thing that ever did — so reopening the app emptied every row and took the
     * diff behind it out of reach. A screen whose purpose is checking a run you
     * were not present for cannot be reachable only while you are present.
     *
     * One request for the whole conversation, and it only ever adds: a turn this
     * phone watched already carries sizes and the kind of each change, which the
     * list does not have, so anything already recorded is left exactly as it is.
     */
    private fun fillChangedFilesFromHistory() {
        val project = projectId ?: return

        scope.launch {
            val byTurn = runCatching { checkpoints.changedByTurn(project, conversationId) }
                .getOrDefault(emptyMap())
            if (byTurn.isEmpty()) return@launch

            _messages.value = _messages.value.map { message ->
                if (message.changedFiles.isNotEmpty()) return@map message
                // Keyed on the user message that started the turn, which is what the
                // computer files a checkpoint under — the same translation
                // `userIdFor` does for the diff.
                val paths = byTurn[userIdFor(message.id)] ?: return@map message
                message.copy(
                    changedFiles = paths.map { path ->
                        // Paths are all the list carries. A row with no byte count
                        // says nothing rather than something wrong, and the kind of
                        // change arrives with the diff when one is opened.
                        ChangedFile(
                            path = path,
                            kind = null,
                            beforeSize = 0,
                            afterSize = 0,
                            conflicted = false,
                        )
                    }
                )
            }
        }
    }

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

    /**
     * What changed inside one of the files [recordChangedFiles] listed.
     *
     * Takes the *reply* id, because that is what the transcript on screen is made
     * of — and translates it here, beside [assistantIdFor], because the desktop
     * records checkpoints against the user message that started the turn. That
     * translation written anywhere else is a second copy of the same rule, and the
     * copy nobody looks at is the one that goes wrong.
     *
     * Best effort, like the listing it belongs to: a turn with no checkpoint is the
     * ordinary answer, not a failure worth putting on screen.
     */
    suspend fun diffOf(replyId: String, path: String): TurnDiffResult {
        val project = projectId
            ?: return TurnDiffResult.Failed("This turn did not run inside a project.")

        return runCatching {
            checkpoints.diffOf(project, conversationId, userIdFor(replyId), path)
        }.getOrElse {
            // Guarded for the same reason every other call on this socket is: it
            // can die mid-request, and `failPending` resumes the call with the
            // failure rather than the answer.
            TurnDiffResult.Failed(it.message ?: "Lost the connection to your computer.")
        }
    }

    private val checkpoints = Checkpoints(socket)

    init {
        // After `checkpoints`, deliberately. Kotlin runs initialisers in
        // declaration order and does not follow a call into a function body, so
        // an `init` placed higher compiles and then reads a property that has not
        // been assigned — surviving only because the work happens to be launched
        // rather than run. That is luck, not a guarantee.
        if (initialMessages.isNotEmpty()) fillChangedFilesFromHistory()
    }

    private fun assistantIdFor(messageId: String) = "$messageId$REPLY_SUFFIX"

    private fun userIdFor(replyId: String) = replyId.removeSuffix(REPLY_SUFFIX)

    private companion object {
        /** What this app adds to a user message id to name the answer to it. */
        const val REPLY_SUFFIX = ":reply"
        const val CHANNEL_SEND = "chat:send"
        const val CHANNEL_BRANCH_FOR_EDIT = "conversations:branch-for-edit"
        const val CHANNEL_STREAM = "chat:stream"
        const val CHANNEL_THINKING = "chat:thinking-stream"
        const val CHANNEL_WORKING = "chat:working"
        const val CHANNEL_ACTIVITY = "tools:activity"
        const val CHANNEL_CONFIRM_REQUEST = "tools:confirm-request"
        const val CHANNEL_CONFIRM_CANCELLED = "tools:confirm-cancelled"
        const val CHANNEL_CONFIRM_RESPONSE = "tools:confirm-response"
        const val CHANNEL_SAVE = "conversations:save"
        const val CHANNEL_STOP = "chat:stop"
        const val CHANNEL_TITLE = "chat:title"
        const val CHANNEL_READ_THINKING = "conversations:thinking"

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
/** The title the computer gives a conversation before anyone has named it. */
internal fun isPlaceholderTitle(title: String): Boolean = title.trim() == "New chat"

/**
 * The name a session starts with, or null for a conversation still to be named.
 *
 * An empty conversation made at the computer reads "New chat" until its first turn.
 * Taken as a name, it stopped the phone naming the conversation after that turn, so a
 * chat created at the desk and first used on the phone kept "New chat" for good.
 */
internal fun initialTitle(stored: String?, hasMessages: Boolean): String? =
    stored?.takeIf { it.isNotBlank() && !(isPlaceholderTitle(it) && !hasMessages) }

/**
 * The title after the computer's copy arrives. The computer records a phone's first
 * turn under the placeholder before the phone has named it, and that is not a name —
 * neither in place of none nor over the one the phone has since given it.
 */
internal fun titleFromComputer(stored: String?, current: String?): String? = when {
    stored == null -> current
    isPlaceholderTitle(stored) -> current
    else -> stored
}

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
    val line = first.lineSequence()
        .map(::withoutMarkdown)
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
    return when {
        line.isEmpty() -> "New chat"
        line.length <= MAX_TITLE_LENGTH -> line
        else -> line.take(MAX_TITLE_LENGTH).trimEnd() + "…"
    }
}

/**
 * A line with its markdown marks taken out, for use as a title.
 *
 * A title is drawn as plain text everywhere it appears, so `**bold**` in a pasted
 * prompt reached the drawer as four literal asterisks. Only the marks are removed —
 * emphasis, inline code, a heading or quote prefix, a list bullet — never the words
 * inside them.
 */
internal fun withoutMarkdown(line: String): String = line
    .replace(Regex("""^\s*(?:#{1,6}\s+|>\s*|[-*+]\s+|\d+[.)]\s+)"""), "")
    // Flanked the way emphasis is, so a path like `src/**/*.ts` or a name like
    // `__init__.py` is text rather than a pair of marks.
    .replace(Regex("""(?<![\w*/])\*\*(?![\s*])(.+?)(?<![\s*])\*\*(?![\w*/])"""), "$1")
    .replace(Regex("""(?<!\w)__(?![\s_])(.+?)(?<![\s_])__(?![\w.])"""), "$1")
    .replace(Regex("""(?<![\w*/])\*(?![\s*])([^*]+?)(?<!\s)\*(?![\w*/])"""), "$1")
    // Half a pair: a title cut through its own bold keeps the opening marks and loses
    // the closing ones, and the drawer showed "Here is the **single combined ma…".
    // Only `**` on a word edge: a glob's `**` between slashes stays, and a lone `__`
    // is left alone because `__init__.py` is made of them.
    .replace(Regex("""(?<![\w*/])\*\*(?=\w)|(?<=\w)\*\*(?![\w*/])"""), "")
    .replace(Regex("""`([^`]*)`"""), "$1")
    .replace(Regex("""\s+"""), " ")
    .trim()
    // A rule or a stray run of marks is not a line of text at all.
    .let { if (it.matches(Regex("""[*_`#>~=-]*"""))) "" else it }

/**
 * The turns a save sends, given the ids the computer already has.
 *
 * Only the new ones, because the computer merges a phone's save into what it holds.
 * Everything, for a conversation never saved, which it may hold none of. And the last
 * turn alone when nothing is new — a rename — so the save has a turn to merge into
 * rather than arriving empty.
 */
internal fun turnsToSave(turns: List<ChatMessage>, confirmed: Set<String>): List<ChatMessage> {
    if (confirmed.isEmpty()) return turns
    val unsent = turns.filterNot { it.id in confirmed }
    return unsent.ifEmpty { turns.takeLast(1) }
}

/**
 * One turn after a sync: the computer's copy, keeping what only this phone holds.
 *
 * The computer's transcript for a phone carries text, author and attachments — not
 * the tool rows, the changed files, or thinking already read. A turn whose text is
 * unchanged is kept exactly as this phone has it, which is every turn it watched
 * finish: replacing those emptied the tool log and the thinking of a reply the
 * moment it ended.
 */
internal fun keepWhatOnlyThisPhoneHas(had: ChatMessage?, computer: ChatMessage): ChatMessage {
    if (had == null) return computer
    if (had.text == computer.text) {
        return had.copy(
            hasThinking = had.thinking == null && (had.hasThinking || computer.hasThinking),
            endedEarly = had.endedEarly || computer.endedEarly,
        )
    }
    return computer.copy(
        tools = had.tools,
        changedFiles = had.changedFiles,
        thinking = had.thinking,
        hasThinking = had.thinking == null && computer.hasThinking,
        endedEarly = had.endedEarly || computer.endedEarly,
        // The computer's list when it has one, this phone's otherwise. A
        // conversation saved before sources were recorded comes back without
        // them, and taking the computer's empty list on faith would blank the
        // sources of a reply somebody is looking straight at -- the same mistake
        // that made this whole feature look broken, one layer further in.
        webSources = computer.webSources.ifEmpty { had.webSources },
        webSearchAttempted = computer.webSearchAttempted || had.webSearchAttempted,
    )
}

/**
 * The sources on `chat:send`'s answer, in or out of its `{ ok, value }`.
 *
 * Both shapes, like [thinkingFromResult] below and for the same reason: this
 * channel has been seen answering either way, and reading only one of them
 * returns nothing, which is indistinguishable from a turn that used no sources.
 */
internal fun webSourcesFromResult(element: JsonElement?): List<WebSource> {
    val fields = (element as? JsonObject)?.let { (it["value"] as? JsonObject) ?: it }
    val list = fields?.get("webSources") as? JsonArray ?: return emptyList()
    return list.mapNotNull { entry ->
        val o = entry as? JsonObject ?: return@mapNotNull null
        val id = (o["id"] as? JsonPrimitive)?.contentOrNullish() ?: return@mapNotNull null
        val url = (o["url"] as? JsonPrimitive)?.contentOrNullish() ?: return@mapNotNull null
        WebSource(
            id = id,
            // The host is a worse title than the page's own and a much better one
            // than an empty row, which is what a missing title used to draw.
            title = (o["title"] as? JsonPrimitive)?.contentOrNullish()?.takeIf { it.isNotBlank() }
                ?: url,
            url = url,
            snippet = (o["snippet"] as? JsonPrimitive)?.contentOrNullish()?.takeIf { it.isNotBlank() },
            verified = (o["verified"] as? JsonPrimitive)?.contentOrNullish() == "true",
        )
    }
}

/** Whether a web tool ran this turn, which is not the same as whether it found anything. */
internal fun webSearchAttemptedFromResult(element: JsonElement?): Boolean {
    val fields = (element as? JsonObject)?.let { (it["value"] as? JsonObject) ?: it }
    return (fields?.get("webSearchAttempted") as? JsonPrimitive)?.contentOrNullish() == "true"
}

/** The thinking on `chat:send`'s answer — the turn result, in or out of its `{ ok, value }`. */
internal fun thinkingFromResult(element: JsonElement?): String? {
    val fields = (element as? JsonObject)?.let { (it["value"] as? JsonObject) ?: it } ?: return null
    return (fields["thinking"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}

/**
 * Whether `chat:send`'s answer says the reply stopped partway: `stopped` for a reason
 * other than the user pressing Stop.
 */
internal fun endedEarlyFromResult(element: JsonElement?): Boolean {
    val fields = (element as? JsonObject)?.let { (it["value"] as? JsonObject) ?: it } ?: return false
    val stopped = (fields["stopped"] as? JsonPrimitive)?.content == "true"
    val reason = (fields["stopReason"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return stopped && reason != null && reason != "user"
}

/** What Continue sends: the same instruction the computer resumes a reply with. */
internal const val CONTINUE_MESSAGE = "Continue from where you stopped."

/**
 * Whether a reply can be continued: the newest reply, stopped partway, having written
 * or done something to continue from.
 */
internal fun canContinue(message: ChatMessage, isNewest: Boolean): Boolean =
    isNewest &&
        message.role == ChatMessage.Role.ASSISTANT &&
        !message.streaming &&
        message.endedEarly &&
        (message.text.isNotBlank() || message.tools.isNotEmpty())

/** `conversations:thinking`'s answer: the text, or null for none. */
internal fun thinkingTextOf(element: JsonElement?): String? {
    val value = (element as? JsonObject)?.get("value") ?: element
    return (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}

/** What a quiet turn says it is doing — the desktop's `ChatWorkingEvent.phase`. */
internal enum class WorkingPhase { WAITING_FOR_MODEL, WORKING, READING }

/** How much of its prompt the computer's model has read, cached tokens included. */
data class ReadingProgress(val done: Long, val total: Long) {
    /**
     * "Reading · 45%" while enough is left to be worth showing, otherwise null — a
     * short read would flash past before it could be read.
     */
    val label: String?
        get() = if (total <= 0 || total - done < MIN_VISIBLE_READ_TOKENS) null else "Reading · $percent%"

    /** Whole percent read, never 100 while reading is still going. */
    val percent: Int
        get() = if (total <= 0) 0 else ((done * 100) / total).toInt().coerceIn(0, 99)

    companion object {
        const val MIN_VISIBLE_READ_TOKENS = 1_024L
    }
}

/** The `reading` progress on a `chat:working` event, or null when it has none. */
internal fun readingProgressOf(payload: kotlinx.serialization.json.JsonElement?): ReadingProgress? {
    val reading = (payload as? JsonObject)?.get("reading") as? JsonObject ?: return null
    val done = (reading["done"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: return null
    val total = (reading["total"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: return null
    return if (total > 0) ReadingProgress(done.coerceIn(0, total), total) else null
}

/**
 * The phase out of a `chat:working` event for [conversationId], or null when the event
 * is for another conversation or unreadable.
 *
 * An unknown phase reads as working: the one thing an event on this channel always
 * means is that the turn is alive.
 */
internal fun workingPhase(
    payload: kotlinx.serialization.json.JsonElement?,
    conversationId: String,
): WorkingPhase? {
    val fields = payload as? JsonObject ?: return null
    if ((fields["conversationId"] as? JsonPrimitive)?.content != conversationId) return null
    return when ((fields["phase"] as? JsonPrimitive)?.content) {
        "waiting-for-model" -> WorkingPhase.WAITING_FOR_MODEL
        "reading" -> WorkingPhase.READING
        else -> WorkingPhase.WORKING
    }
}

/**
 * The start of a reply, plain, for a notification.
 *
 * A lock screen shows a line or two, and a reply is markdown — a code fence or a
 * table as its first line reads as noise there. Marks are dropped, lines joined, and
 * the result cut on a word near [REPLY_PREVIEW_CHARS].
 */
internal fun replyPreview(text: String): String {
    val plain = text.lineSequence()
        .filterNot { it.trimStart().startsWith("```") }
        .map(::withoutMarkdown)
        .filter { it.isNotBlank() }
        .joinToString(" ")
    if (plain.length <= REPLY_PREVIEW_CHARS) return plain
    val cut = plain.take(REPLY_PREVIEW_CHARS)
    val lastSpace = cut.lastIndexOf(' ').takeIf { it > REPLY_PREVIEW_CHARS / 2 } ?: cut.length
    return cut.take(lastSpace).trimEnd() + "…"
}

internal const val REPLY_PREVIEW_CHARS = 160

/** What `chat:title` is asked, in the shape of the desktop's `ChatTitleRequest`. */
internal fun titleRequest(question: ChatMessage, reply: ChatMessage): JsonObject = buildJsonObject {
    put("userPrompt", question.text)
    put("assistantReply", reply.text)
    put("attachmentNames", buildJsonArray { question.attachments.forEach { add(JsonPrimitive(it.name)) } })
    put("editedFiles", buildJsonArray { reply.changedFiles.forEach { add(JsonPrimitive(it.path)) } })
}

/**
 * The title out of `chat:title`'s answer, or null when there is not one.
 *
 * The handler returns the string itself rather than an `{ ok, value }` envelope, but
 * both are read: a handler changing its shape should cost a title, not a crash.
 */
internal fun titleFromReply(element: kotlinx.serialization.json.JsonElement?): String? {
    val value = (element as? JsonObject)?.let { it["value"] } ?: element
    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return withoutMarkdown(text).takeIf { it.isNotBlank() }?.take(MAX_TITLE_LENGTH)
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
    temporary: Boolean = false,
): JsonObject = buildJsonObject {
    // Only when set. A computer from before temporary chats ignores the key, and
    // an absent key is what every ordinary turn has always sent.
    if (temporary) put("temporary", true)
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

/** What the computer said to cutting a conversation back for an edit. */
internal enum class EditOutcome { CUT, PROJECT_CHAT, REFUSED, UNSUPPORTED }

/**
 * `conversations:branch-for-edit`'s answer. Not found means the computer never saved
 * this conversation, so there is nothing there to cut — and cutting here is right.
 */
internal fun editOutcomeOf(element: JsonElement?): EditOutcome {
    val fields = element as? JsonObject ?: return EditOutcome.UNSUPPORTED
    val ok = (fields["ok"] as? JsonPrimitive)?.contentOrNull
    if (ok == "true") return EditOutcome.CUT
    val code = ((fields["error"] as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull
    return when (code) {
        "conversations.edit-not-found" -> EditOutcome.CUT
        "conversations.edit-project-chat" -> EditOutcome.PROJECT_CHAT
        null -> EditOutcome.UNSUPPORTED
        else -> EditOutcome.REFUSED
    }
}
