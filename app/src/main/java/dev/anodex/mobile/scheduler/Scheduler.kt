package dev.anodex.mobile.scheduler

import dev.anodex.mobile.transport.AnodexSocket
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
    /**
     * The runs the computer still keeps, newest first.
     *
     * "Did the 6am run go through, and what did it say" is the whole reason to open
     * a task from a phone, and the answer was already stored and already crossing
     * the wire — it simply had nowhere to be shown.
     */
    val runs: List<TaskRun> = emptyList(),
)

/**
 * A phrase the computer understood, and what it made of it.
 *
 * [recurrence] is kept as raw JSON on purpose — the phone hands it straight back
 * when creating the task and never looks inside.
 */
data class ParsedWhen(
    val recurrence: JsonObject,
    /** "Weekdays at 7:00 AM" — the computer's own wording, so the preview and the
     *  task card can never describe one schedule two different ways. */
    val label: String,
    /** Set when the input was accepted but adjusted, e.g. an interval raised to its
     *  minimum. Null when it was taken literally. */
    val note: String?,
)

/** One time a task ran. */
data class TaskRun(
    val id: String,
    val startedAtEpochMs: Long?,
    val durationMs: Long,
    /** `success`, `failed`, `skipped`… straight from the computer. */
    val status: String?,
    val summary: String?,
    /**
     * How late it started, in millis.
     *
     * Worth showing because a task that runs twenty seconds after its slot is fine
     * and one that runs forty minutes late is a sleeping computer, and those look
     * identical if all you see is that it succeeded.
     */
    val delayedMs: Long,
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
        if (tasks.isNotEmpty()) return tasks

        // Four different things used to look identical here, and only one of them is
        // "you have no tasks". Naming which one it is costs a line and saves a day:
        // the first version of this check folded a missing answer and an empty list
        // into the same silence, which is exactly the case that then happened.
        when {
            answer == null ->
                error("Your computer answered with nothing at all. Restart Anodex there.")

            // The one case that is not a fault: the computer looked and there is
            // genuinely nothing. Returns rather than throwing, so an empty scheduler
            // reads as empty instead of broken.
            answer is JsonArray && answer.isEmpty() -> return emptyList()

            answer is JsonArray ->
                error("Your computer sent ${answer.size} tasks this app could not read.")

            else ->
                error("Your computer answered in a shape this app did not expect.")
        }
    }

    /**
     * Run a task now, regardless of its schedule.
     *
     * Starts real work on a machine nobody is watching, which is why it is a
     * deliberate tap at the bottom of a task rather than anything reachable by
     * accident. The computer answers nothing on success and throws with its own
     * message on failure, which is exactly what the caller needs.
     */
    suspend fun runNow(id: String) {
        socket.invoke(CHANNEL_RUN_NOW, listOf(JsonPrimitive(id)))
    }

    /**
     * Read a typed phrase into a schedule, on the computer.
     *
     * The parser is TypeScript and lives on the desktop, so the phone sends the
     * words rather than carrying a second implementation of the same rules in
     * Kotlin. Two parsers would agree on the day they were written and disagree
     * after that, and the disagreement would be invisible until a task ran at the
     * wrong hour.
     *
     * Null for anything it cannot read, which is the ordinary state of a field
     * somebody is halfway through typing.
     */
    suspend fun parseWhen(text: String): ParsedWhen? {
        val answer = socket.invoke(CHANNEL_PARSE_WHEN, listOf(JsonPrimitive(text)))
        val obj = answer as? JsonObject ?: return null
        val recurrence = obj["recurrence"] as? JsonObject ?: return null
        val label = obj["label"]?.jsonPrimitive?.contentOrNull ?: return null
        return ParsedWhen(
            recurrence = recurrence,
            label = label,
            note = obj["note"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /**
     * Create a task.
     *
     * The recurrence goes back exactly as [parseWhen] returned it. The phone never
     * builds one itself and has no Kotlin model of its shape — which is deliberate:
     * a recurrence the phone assembled would be a third opinion about a format only
     * the computer stores.
     */
    suspend fun create(
        prompt: String,
        name: String?,
        recurrence: JsonObject,
        projectId: String?,
    ) {
        val request = buildJsonObject {
            put("prompt", prompt)
            name?.takeIf { it.isNotBlank() }?.let { put("name", it) }
            put("projectId", projectId?.let(::JsonPrimitive) ?: JsonNull)
            put("recurrence", recurrence)
            // Nothing, deliberately. A task made from a phone should not quietly get
            // file or shell access the person could not see themselves granting; a
            // task that needs tools is made at the computer where the list is shown.
            put("enabledTools", buildJsonArray { })
        }
        socket.invoke(CHANNEL_CREATE, listOf(request))
    }

    private companion object {
        const val CHANNEL_LIST = "scheduler:list"
        const val CHANNEL_RUN_NOW = "scheduler:run-now"
        const val CHANNEL_PARSE_WHEN = "scheduler:parse-when"
        const val CHANNEL_CREATE = "scheduler:create"
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
            // Newest first: a run log is read from the top, and the computer stores
            // it oldest first because that is the order it happened in.
            runs = (entry["runs"] as? JsonArray)
                ?.filterIsInstance<JsonObject>()
                ?.mapNotNull { it.asRun() }
                ?.asReversed()
                .orEmpty(),
        )
    }
}

/** One entry from a task's `runs` array, or null when it carries no id. */
private fun JsonObject.asRun(): TaskRun? {
    val id = this["id"]?.jsonPrimitive?.contentOrNull ?: return null
    return TaskRun(
        id = id,
        startedAtEpochMs = this["startedAt"]?.jsonPrimitive?.longOrNull,
        durationMs = this["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L,
        status = this["status"]?.jsonPrimitive?.contentOrNull,
        summary = this["summary"]?.jsonPrimitive?.contentOrNull,
        delayedMs = this["delayedMs"]?.jsonPrimitive?.longOrNull ?: 0L,
    )
}

/**
 * "19.4s", "1m 20s", "under a second".
 *
 * Seconds to one decimal below a minute: the difference between a run that took
 * 2.1s and one that took 19.4s is the interesting part, and rounding both to
 * "seconds" throws it away.
 */
fun formatDuration(ms: Long): String = when {
    ms < 1_000 -> "under a second"
    ms < 60_000 -> "%.1fs".format(ms / 1000.0)
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
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
