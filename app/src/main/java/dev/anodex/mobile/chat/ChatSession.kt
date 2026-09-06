package dev.anodex.mobile.chat

import dev.anodex.mobile.transport.AnodexSocket
import dev.anodex.mobile.transport.ServerFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/** One turn in the transcript. */
data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String,
    /** True while tokens are still arriving for this message. */
    val streaming: Boolean = false,
) {
    enum class Role { USER, ASSISTANT }
}

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
) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val conversationId = UUID.randomUUID().toString()

    init {
        scope.launch {
            socket.events.collect { event -> onEvent(event) }
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _sending.value) return

        val messageId = UUID.randomUUID().toString()
        _error.value = null
        _sending.value = true
        _messages.value = _messages.value +
            ChatMessage(messageId, ChatMessage.Role.USER, trimmed) +
            ChatMessage(assistantIdFor(messageId), ChatMessage.Role.ASSISTANT, "", streaming = true)

        scope.launch {
            try {
                socket.invoke(CHANNEL_SEND, listOf(request(messageId, trimmed)))
            } catch (e: Exception) {
                _error.value = e.message ?: "That didn't reach your computer."
            } finally {
                _sending.value = false
                finishStreaming()
            }
        }
    }

    private fun request(messageId: String, text: String): JsonObject = buildJsonObject {
        put("conversationId", conversationId)
        put("messageId", messageId)
        put("content", text)
    }

    private fun onEvent(event: ServerFrame.Event) {
        if (event.channel != CHANNEL_STREAM) return
        val payload = event.payload?.jsonObject ?: return
        if (payload["conversationId"]?.jsonPrimitive?.content != conversationId) return

        val token = payload["token"]?.jsonPrimitive?.content ?: return
        appendToken(token)
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

    private fun assistantIdFor(messageId: String) = "$messageId:reply"

    private companion object {
        const val CHANNEL_SEND = "chat:send"
        const val CHANNEL_STREAM = "chat:stream"
    }
}
