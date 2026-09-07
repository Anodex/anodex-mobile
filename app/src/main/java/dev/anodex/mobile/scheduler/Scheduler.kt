package dev.anodex.mobile.scheduler

import dev.anodex.mobile.transport.AnodexSocket
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A task the computer runs on its own. */
data class ScheduledTask(
    val id: String,
    val name: String,
    /** What it asks Anodex to do. Shown in full only on the task itself. */
    val prompt: String,
    val enabled: Boolean,
    /** Epoch millis, or null once a one-off has run, or while it is switched off. */
    val nextRunAt: Long?,
    val lastRunAt: Long?,
    /** `ok`, `failed`, `skipped`… straight from the computer. */
    val lastRunStatus: String?,
    val lastRunSummary: String?,
    /** Total runs ever, including ones aged out of the retained history. */
    val runCount: Int,
)

/**
 * The computer's scheduled tasks, read from away.
 *
 * Read-only on purpose, for now. Listing is the thing somebody wants from a phone —
 * *did the 6am run go through, and what did it say* — and it is answerable without
 * being able to break anything. Creating and editing a task means choosing a
 * recurrence and a tool set, which is a real editor rather than a screen, and
 * `scheduler:run-now` fires work on a machine nobody is watching.
 *
 * Those channels are not denied, so this is a decision about what to build rather
 * than what is permitted. Worth revisiting once the list has proved useful.
 */
class Scheduler(private val socket: AnodexSocket) {

    /**
     * Every task on the computer.
     *
     * Throws rather than returning empty when the answer is a shape this cannot read.
     * The distinction is the whole point: "there are no tasks" and "there are tasks I
     * could not parse" look identical to somebody staring at a blank screen, and the
     * second one is a bug that will otherwise never be reported as one.
     */
    suspend fun list(): List<ScheduledTask> {
        val answer = socket.invoke(CHANNEL_LIST)
        val tasks = parseTasks(answer)

        if (tasks.isEmpty() && countsEntries(answer) > 0) {
            error("Your computer sent ${countsEntries(answer)} tasks this app could not read.")
        }

        return tasks
    }

    /** How many entries the computer sent, whatever this could make of them. */
    private fun countsEntries(element: JsonElement?): Int = when (element) {
        is JsonArray -> element.size
        is JsonObject -> (element["value"] as? JsonArray)?.size ?: 0
        else -> 0
    }

    private companion object {
        const val CHANNEL_LIST = "scheduler:list"
    }
}

/**
 * The computer's answer, read defensively.
 *
 * Wrapped in the desktop's `Result` shape, so the array is a level down from where
 * it looks like it should be — reading it too high yields an empty list rather than
 * an error, which is the failure that hides.
 */
internal fun parseTasks(element: JsonElement?): List<ScheduledTask> {
    val array = when (element) {
        is JsonArray -> element
        is JsonObject -> element["value"] as? JsonArray ?: return emptyList()
        else -> return emptyList()
    }

    return array.filterIsInstance<JsonObject>().mapNotNull { entry ->
        val id = entry["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        ScheduledTask(
            id = id,
            // A task with no name still has to be pickable out of a list.
            name = entry["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: "Untitled task",
            prompt = entry["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            // Absent reads as on: a task the computer listed is one it intends to run,
            // and showing it as disabled would be the more misleading guess.
            enabled = entry["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
            nextRunAt = entry["nextRunAt"]?.jsonPrimitive?.longOrNull,
            lastRunAt = entry["lastRunAt"]?.jsonPrimitive?.longOrNull,
            lastRunStatus = entry["lastRunStatus"]?.jsonPrimitive?.contentOrNull,
            lastRunSummary = entry["lastRunSummary"]?.jsonPrimitive?.contentOrNull,
            runCount = entry["runCount"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }
}

/**
 * "in 4 hours", "2 days ago", "just now".
 *
 * Relative rather than a clock time, because the useful question from a phone is
 * *how long until* or *how long since*, and an absolute time makes the reader do
 * the subtraction — across a timezone, if they are away.
 */
fun relativeTime(epochMs: Long?, now: Long = System.currentTimeMillis()): String? {
    if (epochMs == null || epochMs <= 0) return null

    val delta = epochMs - now
    val ahead = delta >= 0
    val minutes = kotlin.math.abs(delta) / 60_000

    val amount = when {
        minutes < 1 -> return if (ahead) "any moment" else "just now"
        minutes < 60 -> "$minutes ${plural(minutes, "minute")}"
        minutes < 60 * 24 -> (minutes / 60).let { "$it ${plural(it, "hour")}" }
        else -> (minutes / (60 * 24)).let { "$it ${plural(it, "day")}" }
    }

    return if (ahead) "in $amount" else "$amount ago"
}

private fun plural(count: Long, word: String) = if (count == 1L) word else word + "s"
