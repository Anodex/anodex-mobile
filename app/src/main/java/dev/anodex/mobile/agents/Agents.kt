package dev.anodex.mobile.agents

import dev.anodex.mobile.transport.AnodexSocket
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull

/** One long-running agent run on the computer. */
data class AgentRun(
    val id: String,
    val goal: String,
    val status: Status,
    val conversationId: String,
    val turnsUsed: Int,
    val maxTurns: Int,
    val limitsEnabled: Boolean,
    val summary: String?,
    val lastError: String?,
    val plan: Plan?,
    val updatedAtEpochMs: Long,
) {
    enum class Status {
        RUNNING,

        /**
         * Waiting for a human to approve its plan before it starts work.
         *
         * The single most useful thing a phone can show. A run in this state is
         * doing nothing at all until somebody looks at it, and the person who can
         * unblock it is usually not at the desk — which is the entire argument for
         * carrying the app (§8).
         */
        NEEDS_REVIEW,

        DONE,
        STOPPED,
        ERROR;

        companion object {
            fun parse(value: String?): Status = when (value) {
                "running" -> RUNNING
                "needs-review" -> NEEDS_REVIEW
                "stopped" -> STOPPED
                "error" -> ERROR
                else -> DONE
            }
        }
    }
}

data class Plan(val title: String, val steps: List<PlanStep>)

data class PlanStep(val id: String, val title: String, val status: String)

/**
 * Reading and answering agent runs.
 *
 * Read-mostly on purpose. A run is created at the computer, where the goal is
 * written and the tools are chosen; from the phone the useful verbs are the ones
 * that unblock or end something — approve a plan, reject it, stop a run that has
 * gone wrong.
 */
class Agents(private val socket: AnodexSocket) {

    suspend fun list(): List<AgentRun> = parseAgentRuns(socket.invoke(CHANNEL_LIST))

    suspend fun approvePlan(runId: String) {
        socket.invoke(CHANNEL_APPROVE, listOf(JsonPrimitive(runId)))
    }

    suspend fun rejectPlan(runId: String) {
        socket.invoke(CHANNEL_REJECT, listOf(JsonPrimitive(runId)))
    }

    /**
     * Start a run on the computer.
     *
     * The goal and the project travel; the tool set does not. The computer clamps
     * whatever is asked for to the same vetted list its own editor offers, and
     * forces the plan gate on for anything started from here — so a run begun from
     * a phone always stops and shows its plan before touching a file.
     *
     * [lookOnly] asks for a run that reads and reports without changing anything.
     * It is a subset of the same list, so it needs no special permission: the
     * computer will narrow it, never widen it.
     */
    suspend fun start(goal: String, projectId: String?, lookOnly: Boolean) {
        val request = buildJsonObject {
            put("goal", goal)
            put("projectId", projectId?.let(::JsonPrimitive) ?: JsonNull)
            // Whatever is loaded on the computer. Choosing a cloud model from here
            // would be picking something to spend money with, from a screen that
            // does not show what it costs.
            put("provider", "local")
            put("model", JsonNull)
            put("requirePlan", true)
            put(
                "enabledTools",
                buildJsonArray { for (name in toolsFor(lookOnly)) add(JsonPrimitive(name)) },
            )
        }
        socket.invoke(CHANNEL_CREATE, listOf(request))
    }

    /**
     * What to ask for.
     *
     * Deliberately coarse. The desktop has a checklist of every tool; a phone
     * offering the same thing would be a long list of names nobody can weigh while
     * standing up. Two intentions — look, or build — cover what somebody away from
     * their computer actually means, and the computer narrows either one to what it
     * considers safe regardless of what arrives.
     */
    private fun toolsFor(lookOnly: Boolean): List<String> =
        if (lookOnly) LOOK_ONLY_TOOLS else emptyList()

    suspend fun stop(runId: String) {
        socket.invoke(CHANNEL_STOP, listOf(JsonPrimitive(runId)))
    }

    private companion object {
        const val CHANNEL_LIST = "agent:list"
        const val CHANNEL_CREATE = "agent:create"
        const val CHANNEL_APPROVE = "agent:approve-plan"

        /**
         * A run that only reads.
         *
         * Named here rather than derived, because the phone has no copy of the tool
         * catalogue. Anything in this list the computer does not recognise is
         * dropped by the same clamp that refuses everything else, so a name going
         * stale costs a slightly smaller run rather than a failure.
         *
         * An empty ask means "whatever you would have offered" — the computer's own
         * default build set.
         */
        val LOOK_ONLY_TOOLS = listOf(
            "read_file",
            "read_multiple_files",
            "read_file_range",
            "find_files",
            "list_directory",
            "search_files",
            "search_code",
            "code_outline",
            "git_status",
            "git_diff",
            "list_changes",
            "get_file_info",
            "web_search",
            "fetch_url",
        )
        const val CHANNEL_REJECT = "agent:reject-plan"
        const val CHANNEL_STOP = "agent:stop"
    }
}


/**
 * The computer's runs, from a reply or from a broadcast.
 *
 * One parser for both. `agent:runs-changed` carries the same array `agent:list`
 * answers with, and a second reader for it would agree on the day it was written
 * and drift after — which on this screen means a run whose state depends on whether
 * you asked for it or were told.
 *
 * Sorted here rather than at the call site for the same reason: whatever is blocked
 * belongs at the top, and a list that reorders itself depending on how it arrived
 * would be its own kind of wrong.
 */
internal fun parseAgentRuns(element: kotlinx.serialization.json.JsonElement?): List<AgentRun> {
    val array = when (element) {
        is JsonArray -> element
        is JsonObject -> element["value"] as? JsonArray ?: return emptyList()
        else -> return emptyList()
    }

    return array.filterIsInstance<JsonObject>()
        .mapNotNull { it.toRun() }
        // Anything waiting on a human first, then by recency. A run that is blocked
        // is the reason the screen was opened.
        .sortedWith(
            compareByDescending<AgentRun> { it.status == AgentRun.Status.NEEDS_REVIEW }
                .thenByDescending { it.updatedAtEpochMs },
        )
}
private fun JsonObject.toRun(): AgentRun? {
    val id = str("id") ?: return null
    return AgentRun(
        id = id,
        goal = str("goal") ?: "Agent run",
        status = AgentRun.Status.parse(str("status")),
        conversationId = str("conversationId") ?: "",
        turnsUsed = num("turnsUsed"),
        maxTurns = num("maxTurns"),
        limitsEnabled = str("limitsEnabled") == "true",
        summary = str("summary")?.takeIf { it.isNotBlank() },
        lastError = str("lastError")?.takeIf { it.isNotBlank() },
        plan = (this["plan"] as? JsonObject)?.toPlan(),
        updatedAtEpochMs = num("updatedAt").toLong(),
    )
}

private fun JsonObject.toPlan(): Plan = Plan(
    title = str("title") ?: "Plan",
    steps = (this["steps"] as? JsonArray)
        ?.filterIsInstance<JsonObject>()
        ?.mapNotNull { step ->
            val id = step.str("id") ?: return@mapNotNull null
            PlanStep(id, step.str("title") ?: "", step.str("status") ?: "pending")
        }
        .orEmpty(),
)

/**
 * A string field, or null when the computer sent null.
 *
 * `contentOrNull`, not `content`: `JsonNull` *is* a `JsonPrimitive`, and its
 * `content` is the four-character string "null". So a field the desktop
 * deliberately left empty arrived as the word null, survived `isNotBlank()`,
 * and a finished run showed "null" in red where its error would go.
 *
 * The same read fed `goal`, `status` and `conversationId`, where a null would
 * have been just as wrong and considerably quieter.
 */
private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.num(key: String): Int =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 0
