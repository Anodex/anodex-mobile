package dev.anodex.mobile.transport

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The value inside a `Result`, or null if the call did not succeed.
 *
 * Most desktop handlers answer with `ok(value)` or `err(...)`, which arrives as
 * `{"ok": true, "value": …}`. A reader that forgets to open it gets the envelope
 * and looks for its fields on the wrong object — finding nothing, reporting
 * nothing, and looking exactly like a feature that does not work.
 *
 * That is not hypothetical. The context ring drew nothing for a full round of
 * "fixed it" because of precisely this: the channel worked, the projection was
 * right, and the reply was never opened.
 *
 * **Not every handler wraps.** `models:get-state` returns the engine state
 * directly, because it cannot fail in a way worth reporting. So this is applied per
 * channel rather than by the socket — the shape is the handler's choice, and
 * `protocol/anodex-protocol.json` is where to check which one a channel made.
 */
internal fun JsonElement?.unwrap(): JsonElement? {
    val result = this as? JsonObject ?: return null
    if (result["ok"]?.jsonPrimitive?.contentOrNullish() != "true") return null
    return result["value"]
}

/**
 * The value inside a `Result`, or a throw carrying what the computer said.
 *
 * [unwrap] answers null for a refusal and null for a reply that was not a
 * `Result` at all, which is right where a caller has a sensible empty answer and
 * wrong everywhere else. Reading a mailbox is everywhere else: `email:...`
 * returning a list turns a refusal into an empty list, and an empty list is a
 * claim -- no messages in that conversation -- that the app then draws. A
 * conversation that could not be read was rendered as a conversation with
 * nothing in it, on a screen with no words on it, for weeks.
 *
 * So: a caller that cannot tell an empty answer from a failed one asks for this
 * instead, and says what it is doing in [what] so the sentence reaching the
 * screen names the act rather than the channel.
 */
internal fun JsonElement?.unwrapOrThrow(what: String): JsonElement? {
    val result = this as? JsonObject ?: error("Your computer answered $what with something unreadable.")
    if (result["ok"]?.jsonPrimitive?.contentOrNullish() != "true") {
        error(whatWentWrong(result["error"] as? JsonObject) ?: "Your computer would not $what.")
    }
    return result["value"]
}

/**
 * The computer's own account of a failure, as one sentence.
 *
 * `err(code, message, detail)` splits a failure in two, and the halves are not
 * interchangeable. `message` is what the handler decided to call it -- "Could
 * not send email." -- written before anything had gone wrong and therefore the
 * same sentence for a rejected address, a stale password and an attachment over
 * the limit. `detail` is `toErrorMessage(error)`: the provider's actual words.
 *
 * Reading only `message`, which is what this used to do, is how a refusal
 * reaches a phone having lost the one part of it worth reading. The desktop's
 * own interface never made that mistake -- every `notifyError` on that side
 * passes `error.detail ?? error.message`, so the person at the computer has
 * been getting the real reason all along and the person holding the phone has
 * not.
 *
 * Both, where there are both and they differ. The desktop can afford to drop
 * the headline because it has a title line to put it on; here there is one
 * string, and the pair reads better than either alone -- "Could not send email.
 * Invalid login: 535 authentication failed." says what failed and why, and
 * neither half says both.
 */
private fun whatWentWrong(error: JsonObject?): String? {
    fun field(name: String) = error?.get(name)?.jsonPrimitive?.contentOrNullish()
        ?.trim()?.takeIf { it.isNotBlank() }

    val headline = field("message")
    val cause = field("detail")

    return when {
        cause == null -> headline
        headline == null -> cause
        // A handler that passed the same string twice, and a detail the
        // headline already contains, are both one sentence rather than two.
        cause == headline || headline.contains(cause) -> headline
        // Joined exactly as it was written, with no capital forced onto it.
        //
        // The first version uppercased the cause so the pair read as two
        // sentences. A `detail` is far more often a filename, a hostname or
        // a command than a sentence -- `video.mov` came back as `Video.mov`,
        // and `getaddrinfo ENOTFOUND` would have become `Getaddrinfo`.
        // Renaming something inside an error message is worse than a
        // lowercase letter after a full stop, and the whole point of this
        // function is to repeat what the computer said. Editing it is not
        // repeating it.
        else -> "$headline $cause"
    }
}

/** `content` on a JSON null is the string "null", which is never what a caller wants. */
internal fun JsonPrimitive.contentOrNullish(): String? = if (this is JsonNull) null else content
