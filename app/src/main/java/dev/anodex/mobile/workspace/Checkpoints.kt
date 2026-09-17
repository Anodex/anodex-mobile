package dev.anodex.mobile.workspace

import dev.anodex.mobile.transport.AnodexSocket
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One file a turn changed. */
data class ChangedFile(
    val path: String,
    /** `added`, `modified`, `deleted` — the computer's own word for it. */
    val kind: String?,
    val beforeSize: Long,
    val afterSize: Long,
    /** True when the file was edited again after the turn, so the record is stale. */
    val conflicted: Boolean,
) {
    /** How much bigger or smaller the file got. Zero for a rename or a rewrite in place. */
    val sizeDelta: Long get() = afterSize - beforeSize
}

/** One line of a diff, as the computer drew it. */
data class DiffRow(
    val kind: Kind,
    val text: String,
    /** How many unchanged lines were collapsed here. Only set on [Kind.GAP]. */
    val collapsed: Int = 0,
) {
    enum class Kind { UNCHANGED, ADDED, REMOVED, BLANK, GAP }
}

/**
 * What changed inside one file, in one turn.
 *
 * The counts are the whole truth even when [rows] is cut short: they are taken
 * before the cut, so a phone never under-reports the size of a change.
 */
data class TurnDiff(
    val path: String,
    val kind: String,
    /** Nothing to draw, and the reason is worth saying: a PNG is not a failure. */
    val binary: Boolean,
    val added: Int,
    val removed: Int,
    val rows: List<DiffRow>,
    val truncated: Boolean,
)

/**
 * What a turn actually changed on the computer.
 *
 * This is the difference between starting a build from away and *trusting* one. The
 * transcript says what the model set out to do and the tool rows say what it tried;
 * this says which files ended up different, which is the only one of the three that
 * is a fact.
 *
 * Read-only from a phone, deliberately. The computer also knows how to put these
 * files back, and that is not offered here: undoing an afternoon of work needs a
 * diff in front of you.
 *
 * [diffOf] is the first half of that sentence being made true. `inspect` strips
 * file contents on the way out — a checkpoint holds the whole before and after of
 * everything it touched, and one turn that rewrote a large file would be a frame
 * too big to send — which left the phone able to say *that* a file changed and
 * never *what*. A diff is smaller than either side of it, so the computer draws
 * one and sends that instead.
 */
class Checkpoints(private val socket: AnodexSocket) {

    suspend fun changedBy(
        projectId: String,
        conversationId: String,
        messageId: String,
    ): List<ChangedFile> {
        val request = buildJsonObject {
            put("projectId", projectId)
            put("conversationId", conversationId)
            put("messageId", messageId)
        }

        val answer = socket.invoke(CHANNEL_INSPECT, listOf(request)) as? JsonObject ?: return emptyList()

        // `checkpoints:*` answers in the desktop's Result wrapper. A turn that
        // changed nothing has no checkpoint at all, and the computer says so with an
        // error rather than an empty list — which is not a fault worth reporting,
        // just the ordinary shape of a question that read files and wrote none.
        if (answer["ok"]?.jsonPrimitive?.contentOrNull == "false") return emptyList()

        val files = (answer["value"] as? JsonObject)?.get("files") as? JsonArray ?: return emptyList()

        return files.filterIsInstance<JsonObject>().mapNotNull { it.asChangedFile() }
    }

    /**
     * What changed inside one of those files.
     *
     * Null when the turn has no checkpoint at all, which is the ordinary answer
     * for a turn that read files and wrote none — not a fault worth reporting.
     */
    suspend fun diffOf(
        projectId: String,
        conversationId: String,
        messageId: String,
        path: String,
    ): TurnDiff? {
        val request = buildJsonObject {
            put("projectId", projectId)
            put("conversationId", conversationId)
            put("messageId", messageId)
            put("path", path)
        }

        val answer = socket.invoke(CHANNEL_DIFF, listOf(request)) as? JsonObject ?: return null
        if (answer["ok"]?.jsonPrimitive?.contentOrNull == "false") return null
        val value = answer["value"] as? JsonObject ?: return null

        return turnDiffFrom(value, fallbackPath = path)
    }

    private fun JsonObject.asChangedFile(): ChangedFile? {
        val path = this["path"]?.jsonPrimitive?.contentOrNull ?: return null
        return ChangedFile(
            path = path,
            kind = this["kind"]?.jsonPrimitive?.contentOrNull,
            beforeSize = this["beforeSize"]?.jsonPrimitive?.longOrNull ?: 0L,
            afterSize = this["afterSize"]?.jsonPrimitive?.longOrNull ?: 0L,
            conflicted = this["conflicted"]?.jsonPrimitive?.contentOrNull == "true",
        )
    }

    private companion object {
        const val CHANNEL_INSPECT = "checkpoints:inspect"
        const val CHANNEL_DIFF = "checkpoints:diff-file"
    }
}

/** "src/parser.py", from a path of any depth. Long paths do not fit on a phone. */
fun shortPath(path: String): String {
    val parts = path.replace('\\', '/').split('/').filter { it.isNotBlank() }
    return when {
        parts.size <= 2 -> parts.joinToString("/")
        // Enough to tell two files of the same name apart, which is the job.
        else -> "…/" + parts.takeLast(2).joinToString("/")
    }
}

/** "+240 bytes", "−1.2 KB", or null when the size did not move. */
fun describeDelta(delta: Long): String? {
    if (delta == 0L) return null
    val sign = if (delta > 0) "+" else "−"
    val magnitude = kotlin.math.abs(delta)
    return when {
        magnitude < 1024 -> "$sign$magnitude bytes"
        magnitude < 1024 * 1024 -> "%s%.1f KB".format(sign, magnitude / 1024.0)
        else -> "%s%.1f MB".format(sign, magnitude / (1024.0 * 1024))
    }
}

/**
 * A diff as the computer sent it.
 *
 * Its own function, away from the socket, because this is where a change made on
 * the desktop lands: a renamed field or a new row type arrives here first, and a
 * mapping that can be run on a literal is a mapping that can be tested.
 */
internal fun turnDiffFrom(value: JsonObject, fallbackPath: String): TurnDiff = TurnDiff(
    path = value["path"]?.jsonPrimitive?.contentOrNull ?: fallbackPath,
    kind = value["kind"]?.jsonPrimitive?.contentOrNull ?: "modified",
    binary = value["binary"]?.jsonPrimitive?.contentOrNull == "true",
    added = value["added"]?.jsonPrimitive?.intOrNull ?: 0,
    removed = value["removed"]?.jsonPrimitive?.intOrNull ?: 0,
    rows = (value["rows"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map { it.asRow() },
    truncated = value["truncated"]?.jsonPrimitive?.contentOrNull == "true",
)

private fun JsonObject.asRow(): DiffRow = DiffRow(
    kind = when (this["type"]?.jsonPrimitive?.contentOrNull) {
        "added" -> DiffRow.Kind.ADDED
        "removed" -> DiffRow.Kind.REMOVED
        "blank" -> DiffRow.Kind.BLANK
        "gap" -> DiffRow.Kind.GAP
        // Anything the desktop invents later draws as context rather than
        // vanishing. A row this phone does not recognise is still a line of the
        // file, and dropping it silently would misreport the change.
        else -> DiffRow.Kind.UNCHANGED
    },
    text = this["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    collapsed = this["count"]?.jsonPrimitive?.intOrNull ?: 0,
)
