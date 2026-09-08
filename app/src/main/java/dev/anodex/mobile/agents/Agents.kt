package dev.anodex.mobile.agents

import dev.anodex.mobile.transport.AnodexSocket
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    suspend fun list(): List<AgentRun> {
        val result = socket.invoke(CHANNEL_LIST) as? JsonArray ?: return emptyList()
        return result.filterIsInstance<JsonObject>()
            .mapNotNull { it.toRun() }
            // Anything waiting on a human first, then by recency. A run that is
            // blocked is the reason the screen was opened.
            .sortedWith(
                compareByDescending<AgentRun> { it.status == AgentRun.Status.NEEDS_REVIEW }
                    .thenByDescending { it.updatedAtEpochMs },
            )
    }

    suspend fun approvePlan(runId: String) {
        socket.invoke(CHANNEL_APPROVE, listOf(JsonPrimitive(runId)))
    }

    suspend fun rejectPlan(runId: String) {
        socket.invoke(CHANNEL_REJECT, listOf(JsonPrimitive(runId)))
    }

    suspend fun stop(runId: String) {
        socket.invoke(CHANNEL_STOP, listOf(JsonPrimitive(runId)))
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

    private companion object {
        const val CHANNEL_LIST = "agent:list"
        const val CHANNEL_APPROVE = "agent:approve-plan"
        const val CHANNEL_REJECT = "agent:reject-plan"
        const val CHANNEL_STOP = "agent:stop"
    }
}
