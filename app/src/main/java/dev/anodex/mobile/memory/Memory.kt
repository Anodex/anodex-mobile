package dev.anodex.mobile.memory

import dev.anodex.mobile.transport.AnodexSocket
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One thing the computer remembers. */
data class MemoryEntry(
    val id: String,
    val text: String,
    /** `fact`, `preference`, … straight from the computer. */
    val kind: String?,
    /** Null for a global memory; a project id when it belongs to one project. */
    val projectId: String?,
    val createdAtEpochMs: Long?,
    /** Pinned entries are always retrieved first and never evicted by the cap. */
    val pinned: Boolean,
) {
    /** True when this memory applies everywhere rather than inside one project. */
    val isGlobal: Boolean get() = projectId == null
}

/**
 * Reading what the computer remembers, and forgetting one line.
 *
 * Read and forget, and deliberately nothing else. The `memory:` prefix is denied
 * to remote callers because it sits with the configuration surfaces; these two are
 * carved out of it by name.
 *
 * The asymmetry is the point. A memory is injected into later prompts, so writing
 * one from a phone is a way to steer every future conversation from a device that
 * might be in somebody else's hand. Forgetting only ever narrows what the model is
 * told, and a memory that is *wrong* is exactly the thing worth being able to
 * remove from wherever you happen to be. One that is missing can wait until you
 * are at the machine.
 */
class Memory(private val socket: AnodexSocket) {

    /**
     * Everything remembered for a project, plus everything remembered globally.
     *
     * The desktop's `list` takes a project id and returns the global entries
     * alongside that project's, which is what the model itself would be given —
     * so passing null asks the same question a plain chat would.
     */
    suspend fun list(projectId: String?): List<MemoryEntry> {
        val answer = socket.invoke(
            CHANNEL_LIST,
            listOf(projectId?.let(::JsonPrimitive) ?: JsonNull),
        )

        // `memory:*` answers in the desktop's Result wrapper, so the array is a
        // level below where it looks like it should be. Reading it too high yields
        // an empty list rather than an error — the failure that hides.
        val array = when (answer) {
            is JsonArray -> answer
            is JsonObject -> answer["value"] as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }

        return array.filterIsInstance<JsonObject>().mapNotNull { it.asEntry() }
    }

    /** Forget one. The computer decides what that means on disk. */
    suspend fun forget(entry: MemoryEntry) {
        val scope = buildJsonObject {
            if (entry.projectId == null) {
                put("type", "global")
            } else {
                put("type", "project")
                put("projectId", entry.projectId)
            }
        }
        socket.invoke(CHANNEL_DELETE, listOf(scope, JsonPrimitive(entry.id)))
    }

    private fun JsonObject.asEntry(): MemoryEntry? {
        val id = this["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val text = this["text"]?.jsonPrimitive?.contentOrNull ?: return null

        // Archived entries are kept on disk but excluded from what the model is
        // given. Showing them here would list things Anodex is not actually using.
        if (this["archived"]?.jsonPrimitive?.contentOrNull == "true") return null

        return MemoryEntry(
            id = id,
            text = text,
            kind = this["kind"]?.jsonPrimitive?.contentOrNull,
            projectId = (this["scope"] as? JsonObject)
                ?.get("projectId")
                ?.jsonPrimitive
                ?.contentOrNull,
            createdAtEpochMs = this["createdAt"]?.jsonPrimitive?.longOrNull,
            pinned = this["pinned"]?.jsonPrimitive?.contentOrNull == "true",
        )
    }

    private companion object {
        const val CHANNEL_LIST = "memory:list"
        const val CHANNEL_DELETE = "memory:delete"
    }
}
