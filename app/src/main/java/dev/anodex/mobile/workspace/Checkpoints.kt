package dev.anodex.mobile.workspace

import dev.anodex.mobile.transport.AnodexSocket
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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

/**
 * What a turn actually changed on the computer.
 *
 * This is the difference between starting a build from away and *trusting* one. The
 * transcript says what the model set out to do and the tool rows say what it tried;
 * this says which files ended up different, which is the only one of the three that
 * is a fact.
 *
 * Read-only from a phone, deliberately. The computer also knows how to put these
 * files back, and that is refused here: undoing an afternoon of work needs a diff
 * in front of you, and a filename and a byte count is not that.
 *
 * File contents are stripped on the way out — a checkpoint holds the whole before
 * and after of everything it touched, and one turn that rewrote a large file would
 * be a frame too big to send. Opening a file is a separate request, against the
 * file as it now stands.
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
