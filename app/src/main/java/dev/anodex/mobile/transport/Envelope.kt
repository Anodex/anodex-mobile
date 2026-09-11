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

/** `content` on a JSON null is the string "null", which is never what a caller wants. */
internal fun JsonPrimitive.contentOrNullish(): String? = if (this is JsonNull) null else content
