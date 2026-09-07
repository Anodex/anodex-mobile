package dev.anodex.mobile.chat

import dev.anodex.mobile.transport.AnodexSocket
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** One way Anodex can be asked to answer. */
data class Personality(
    val id: String,
    val name: String,
    /** One line: "Direct. Answer first, reasoning after." Empty if it has none. */
    val role: String,
    /** The desktop's own tint name — `accent`, `violet`, `green`, `series-1`… */
    val tint: String,
)

data class PersonalityState(
    /** Null means the free-text style rather than a named personality. */
    val active: String?,
    val personalities: List<Personality>,
)

/**
 * Reading and changing how Anodex answers.
 *
 * Two channels of its own rather than `settings:`, which a phone cannot reach at
 * all — that prefix carries the permission mode, the MCP servers and the model
 * directory, and a client that can write to it can dismantle the protections that
 * let it connect. Choosing a personality changes the wording of a system prompt and
 * nothing else, so it got a door its own size.
 *
 * The choice is global, not phone-local: it moves for whoever is sitting at the
 * computer too, the same way the active project does.
 */
class Personalities(private val socket: AnodexSocket) {

    suspend fun state(): PersonalityState = parsePersonalityState(socket.invoke(CHANNEL_LIST))

    /** @param id null selects the free-text style instead of a named personality. */
    suspend fun setActive(id: String?): PersonalityState =
        parsePersonalityState(
            socket.invoke(CHANNEL_SET, listOf(id?.let(::JsonPrimitive) ?: JsonNull)),
        )

    private companion object {
        const val CHANNEL_LIST = "personality:list"
        const val CHANNEL_SET = "personality:set-active"
    }
}

/**
 * The computer's answer, read defensively.
 *
 * A personality missing an id or a name is dropped rather than shown blank: it could
 * not be selected anyway, since selecting is done by id. Everything else has a
 * sensible absence — no one-liner, and the accent as the tint, which is the same
 * default the desktop applies.
 */
internal fun parsePersonalityState(element: JsonElement?): PersonalityState {
    val root = element as? JsonObject ?: return PersonalityState(null, emptyList())

    return PersonalityState(
        active = root["active"]?.jsonPrimitive?.contentOrNull,
        personalities = (root["personalities"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.mapNotNull { it.asPersonality() }
            .orEmpty(),
    )
}

private fun JsonObject.asPersonality(): Personality? {
    val id = this["id"]?.jsonPrimitive?.contentOrNull ?: return null
    val name = this["name"]?.jsonPrimitive?.contentOrNull ?: return null

    return Personality(
        id = id,
        name = name,
        role = this["role"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        tint = this["tint"]?.jsonPrimitive?.contentOrNull ?: "accent",
    )
}
