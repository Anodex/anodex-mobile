package dev.anodex.mobile.chat

import dev.anodex.mobile.transport.AnodexSocket
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A project on the computer. */
data class Project(
    val id: String,
    val name: String,
    val folderPath: String,
)

/** Which projects exist, and which one the desktop currently has open. */
data class ProjectsState(
    val projects: List<Project>,
    val activeProjectId: String?,
) {
    val active: Project? get() = projects.firstOrNull { it.id == activeProjectId }
}

/**
 * Reading and changing the desktop's active project.
 *
 * The project is what turns Anodex from a chat into a coding agent: it is the
 * workspace a turn reads and writes. Sending a message without one gets a
 * conversation; sending one with a project gets work done on real files. That is
 * why this exists rather than the phone simply never mentioning projects.
 *
 * **Switching is global.** There is one active project and it is shared with
 * whoever is sitting at the computer — `setActive` moves their workspace too. The
 * desktop refuses a switch mid-generation and announces one that came from here,
 * so the person at the desk is never quietly moved (handoff §10.1). The phone's
 * job is to make that consequence visible before the tap, not to pretend it is
 * a local preference.
 */
class Projects(private val socket: AnodexSocket) {

    suspend fun state(): ProjectsState = parseProjectsState(socket.invoke(CHANNEL_LIST)) ?: EMPTY

    /**
     * Change the active project on the computer.
     *
     * Throws if the desktop refuses — most often because it is mid-generation, and
     * that refusal is worth showing rather than swallowing: it is the difference
     * between "not now" and "that did not work".
     */
    suspend fun setActive(projectId: String?): ProjectsState {
        val argument = projectId?.let { JsonPrimitive(it) } ?: JsonNull
        val result = socket.invoke(CHANNEL_SET_ACTIVE, listOf(argument)) as? JsonObject
        return result?.toState() ?: EMPTY
    }

    private companion object {
        const val CHANNEL_LIST = "projects:list"
        const val CHANNEL_SET_ACTIVE = "projects:set-active"
        val EMPTY = ProjectsState(emptyList(), null)
    }
}


/**
 * The computer's projects, from a reply or from a broadcast.
 *
 * `projects:list` answers with this shape and `projects:changed` pushes it, so both
 * read it here. Null when the payload is not that shape at all, which lets a caller
 * tell "the computer has no projects" from "that was not an answer" — the two look
 * identical once you have turned the second into an empty list.
 */
internal fun parseProjectsState(element: JsonElement?): ProjectsState? {
    val root = element as? JsonObject ?: return null
    return root.toState()
}
    private fun JsonObject.toState(): ProjectsState {
    val list = (this["projects"] as? JsonArray)
        ?.filterIsInstance<JsonObject>()
        ?.mapNotNull { it.toProject() }
        .orEmpty()

    return ProjectsState(
        projects = list,
        activeProjectId = (this["activeProjectId"] as? JsonPrimitive)
            ?.takeIf { it !is JsonNull }
            ?.content,
    )
}

private fun JsonObject.toProject(): Project? {
    // Archived projects are still returned by the desktop's list; they are not
    // somewhere to start work from.
    if ((this["archived"] as? JsonPrimitive)?.content == "true") return null

    val id = (this["id"] as? JsonPrimitive)?.content ?: return null
    return Project(
        id = id,
        name = (this["name"] as? JsonPrimitive)?.content ?: "Untitled project",
        folderPath = (this["folderPath"] as? JsonPrimitive)?.content ?: "",
    )
}
