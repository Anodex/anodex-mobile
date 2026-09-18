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
        // The desktop's own words when it has them: `err(code, message, detail)`
        // is written for a reader, and repeating it beats inventing a second
        // sentence about the same failure.
        val said = (result["error"] as? JsonObject)?.get("message")
            ?.jsonPrimitive?.contentOrNullish()?.takeIf { it.isNotBlank() }
        error(said ?: "Your computer would not $what.")
    }
    return result["value"]
}

/** `content` on a JSON null is the string "null", which is never what a caller wants. */
internal fun JsonPrimitive.contentOrNullish(): String? = if (this is JsonNull) null else content
