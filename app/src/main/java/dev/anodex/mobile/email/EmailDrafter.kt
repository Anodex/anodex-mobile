package dev.anodex.mobile.email

import dev.anodex.mobile.chat.chatRequest
import dev.anodex.mobile.transport.AnodexSocket
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Having Anodex write the message, the way it can at the computer.
 *
 * The desktop does this through a tool the model calls mid-conversation. There is
 * no equivalent here and there does not need to be one: the thing being asked for
 * is a paragraph of text, and `chat:send` returns a paragraph of text.
 *
 * Marked `temporary`, with a conversation id nobody keeps. `chat:send` generates a
 * reply and does not save it -- saving is a separate call this never makes -- so
 * asking for a draft does not leave a conversation in the list. That matters more
 * than it sounds: a mailbox that quietly files a chat every time somebody taps
 * "Write it for me" turns the conversation list into a log of attempts.
 *
 * No history, no project. The mail being answered is in the prompt, and a draft
 * written against whichever project happened to be open would pick up that
 * project's instructions -- a reply to a friend written in the register of a
 * codebase's contributing guide.
 */
class EmailDrafter(private val socket: AnodexSocket) {

    /**
     * Ask for a body, and get back what the model wrote.
     *
     * Null when the computer refused or answered with nothing. The screen keeps
     * whatever was already typed either way: this writes into a compose window
     * somebody may already have started, and losing their words to a failed draft
     * would be worse than the draft not arriving.
     */
    suspend fun draft(
        instruction: String,
        replyingTo: EmailNote? = null,
        to: List<String> = emptyList(),
        subject: String = "",
    ): String? {
        val prompt = buildPrompt(instruction, replyingTo, to, subject)
        val request = chatRequest(
            conversationId = "email-draft-${UUID.randomUUID()}",
            messageId = UUID.randomUUID().toString(),
            prompt = prompt,
            history = EMPTY_HISTORY,
            projectId = null,
            temporary = true,
        )

        // Longer than the default sixty seconds. A draft is one turn with no tools
        // to run, but it is a whole message rather than a sentence, and a local
        // model on somebody's desktop writes it at the speed that machine allows.
        val answer = socket.invoke(CHANNEL_SEND, listOf(request), timeout = 180.seconds)
        return contentOf(answer)?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * What the model is actually asked.
     *
     * Three things it has to be told and one it has to be told not to do. The last
     * is the one that matters: a model asked to write an email will write "Sure!
     * Here's a draft:" above it and an offer to revise below it, and both end up in
     * the message somebody sends. Everything returned goes straight into the body
     * field, so the instruction is that the answer *is* the body.
     */
    private fun buildPrompt(
        instruction: String,
        replyingTo: EmailNote?,
        to: List<String>,
        subject: String,
    ): String = buildString {
        if (replyingTo != null) {
            appendLine("Write a reply to this email.")
            appendLine()
            appendLine("From: ${replyingTo.from}")
            appendLine("Subject: ${replyingTo.subject}")
            appendLine()
            // The plain-text body rather than the HTML: markup would be most of the
            // context spent on nothing the reply depends on.
            appendLine(replyingTo.body.take(MAX_QUOTED_CHARS))
            appendLine()
        } else {
            appendLine("Write an email.")
            appendLine()
            if (to.isNotEmpty()) appendLine("To: ${to.joinToString(", ")}")
            if (subject.isNotBlank()) appendLine("Subject: $subject")
            appendLine()
        }

        if (instruction.isNotBlank()) {
            appendLine("What it should say:")
            appendLine(instruction)
            appendLine()
        }

        append(
            "Reply with the body of the message and nothing else -- no preamble, " +
                "no subject line, no quotes around it, and no offer to revise it. " +
                "Do not sign it with a name you have not been given.",
        )
    }

    /**
     * `chat:send` answers `{ ok, value: ChatResult }`, and `content` is the reply.
     *
     * Read out of the wrapper or out of a bare result, because both shapes have
     * been seen on this channel and reading only one of them fails by returning
     * nothing at all -- which looks exactly like a model that declined to answer.
     */
    private fun contentOf(answer: kotlinx.serialization.json.JsonElement?): String? {
        val fields = (answer as? JsonObject)?.let { (it["value"] as? JsonObject) ?: it }
        return (fields?.get("content") as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    private companion object {
        const val CHANNEL_SEND = "chat:send"

        /** Enough of the message to answer it; the rest is usually a quoted chain. */
        const val MAX_QUOTED_CHARS = 4000

        val EMPTY_HISTORY: JsonArray = buildJsonArray { }
    }
}
