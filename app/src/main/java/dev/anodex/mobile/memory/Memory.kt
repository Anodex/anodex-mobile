package dev.anodex.mobile.memory

import dev.anodex.mobile.transport.AnodexSocket
import dev.anodex.mobile.transport.unwrap
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
 * This used to say that "the `memory:` prefix is denied to remote callers" and
 * that read and forget were carved out of it by name. That was wrong: nothing
 * under `memory:` is refused, and `create` and `update` were simply never called
 * from here. The reasoning that followed it — that writing a memory from a phone
 * steers every later conversation from a device that might be in somebody else's
 * hand — is an argument about whether the phone is trusted, and the desktop
 * settled that one the other way: pairing is the trust boundary, and a paired
 * phone may do what its owner can do at the machine.
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
        socket.invoke(CHANNEL_DELETE, listOf(scopeOf(entry.projectId), JsonPrimitive(entry.id)))
    }

    /**
     * Remember something new.
     *
     * `kind` is the desktop's ranking hint rather than a category anyone browses
     * by: `identity` is retrieved ahead of everything but pinned entries, because
     * "what is my name" shares no words with how the answer was phrased when it
     * was saved. A memory typed on a phone is a `preference` unless it is said to
     * be otherwise -- the ordinary case, and the one that ranks like the entries
     * the model writes for itself.
     */
    suspend fun remember(
        text: String,
        projectId: String?,
        kind: String = KIND_DEFAULT,
    ): MemoryEntry? {
        val request = buildJsonObject {
            put("kind", kind)
            put("text", text)
            put("scope", scopeOf(projectId))
        }
        return (socket.invoke(CHANNEL_CREATE, listOf(request)).unwrap() as? JsonObject)?.asEntry()
    }

    /**
     * Correct one that is wrong.
     *
     * The whole reason the list is on the phone: a memory is injected into every
     * later prompt, so a wrong one keeps being wrong quietly. Being able to read
     * them was most of the value; being able to fix the wording without deleting
     * and retyping is the rest.
     */
    suspend fun reword(entry: MemoryEntry, text: String): MemoryEntry? {
        val patch = buildJsonObject { put("text", text) }
        val args = listOf(scopeOf(entry.projectId), JsonPrimitive(entry.id), patch)
        return (socket.invoke(CHANNEL_UPDATE, args).unwrap() as? JsonObject)?.asEntry()
    }

    /** Pin or unpin: pinned entries survive the storage cap and are retrieved first. */
    suspend fun setPinned(entry: MemoryEntry, pinned: Boolean): MemoryEntry? {
        val patch = buildJsonObject { put("pinned", pinned) }
        val args = listOf(scopeOf(entry.projectId), JsonPrimitive(entry.id), patch)
        return (socket.invoke(CHANNEL_UPDATE, args).unwrap() as? JsonObject)?.asEntry()
    }

    /**
     * Global or one project's, in the shape every write takes.
     *
     * Written once because four calls need it and each got it slightly wrong on
     * its own the last time this kind of thing was copied by hand -- a scope with
     * `type: "project"` and no `projectId` is accepted by the wire and rejected by
     * the store, which fails at the far end with nothing on screen to explain it.
     */
    private fun scopeOf(projectId: String?): JsonObject = buildJsonObject {
        if (projectId == null) {
            put("type", "global")
        } else {
            put("type", "project")
            put("projectId", projectId)
        }
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
        const val CHANNEL_CREATE = "memory:create"
        const val CHANNEL_UPDATE = "memory:update"

        /**
         * What a memory typed by a person is, absent any other signal.
         *
         * `MemoryKind` in `memory.types.ts` is
         * `identity | convention | gotcha | preference | open_task`. The phone does
         * not ask, because the choice is a ranking hint rather than something the
         * person is deciding, and a five-way picker in front of a one-line note is
         * a worse screen than a sensible default.
         */
        const val KIND_DEFAULT = "preference"
        const val CHANNEL_DELETE = "memory:delete"
    }
}
