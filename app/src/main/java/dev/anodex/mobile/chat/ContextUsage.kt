package dev.anodex.mobile.chat

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * How full one conversation's context is, as the computer measures it.
 *
 * **Not the same number the phone used to read.** `EngineState.contextTokensUsed`
 * is the engine's live KV-cache index — present only while a generation is in
 * flight in that session, and absent the rest of the time. The phone read that,
 * found nothing, and drew nothing: a ring that never appeared.
 *
 * The desktop's own meter has never used that field either. It projects what the
 * *next* turn will see — the system prompt, the active tool schemas, the history
 * the ledger will replay, and the room reserved for the reply — from stores that
 * live in its renderer. None of them exist on a phone, and two of them have no
 * channel, so this is read over `chat:context-usage` rather than worked out here.
 *
 * Working it out here was the alternative, and it would have been wrong in a way
 * nothing would have reported: tool schemas alone are usually thousands of tokens,
 * so a phone-side guess from message text would have read far too low and quietly
 * disagreed with the meter on the machine.
 */
data class ContextUsage(
    /** Projected tokens the next turn will occupy. */
    val usedTokens: Int,
    /** The window they are measured against. */
    val contextSize: Int,
    /**
     * The conversation this was measured for.
     *
     * Carried so a screen can refuse a figure that is not about what it is showing.
     * The phone is often looking at a different conversation than the desk is, and a
     * reading from the wrong one is worse than no reading.
     */
    val conversationId: String,
) {
    /** 0f..1f, clamped, or null when the window is not known. */
    val fraction: Float?
        get() {
            if (contextSize <= 0) return null
            return (usedTokens.toFloat() / contextSize).coerceIn(0f, 1f)
        }
}

/**
 * Read a `chat:context-usage` reply.
 *
 * Null for anything that is not a complete reading. The desktop answers with null
 * itself whenever there is nothing honest to report — no conversation, no settings,
 * no model loaded — and that is a normal state rather than a failure, so it arrives
 * here as the same absence an unreadable reply does.
 */
fun contextUsageFrom(element: JsonElement?, conversationId: String): ContextUsage? {
    val projection = element as? JsonObject ?: return null
    val used = (projection["usedTokens"] as? JsonPrimitive)?.intOrNull ?: return null
    val size = (projection["contextSize"] as? JsonPrimitive)?.intOrNull ?: return null
    if (size <= 0) return null

    return ContextUsage(usedTokens = used, contextSize = size, conversationId = conversationId)
}
