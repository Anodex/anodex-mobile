package dev.anodex.mobile.workspace

import dev.anodex.mobile.transport.AnodexSocket
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive

/** What came back when a file was asked for. */
sealed interface FileContent {
    data class Text(val content: String) : FileContent

    /**
     * Something the phone will not render.
     *
     * Deliberately not an error: an image or a 40MB log is a perfectly good file,
     * and the reader should say what it is rather than look like it failed.
     */
    data class Unreadable(val reason: String) : FileContent

    data class Failed(val reason: String) : FileContent
}

/**
 * Reading files out of the project the computer has open.
 *
 * Exists because a tool row saying `Edit src/sim/useDragBody.ts` is only half the
 * information: away from the desk the next question is always *what does it say
 * now*, and without an answer the phone can report that work happened but not what
 * the work was.
 *
 * Read-only, and not because writing is hard. A file editor on a phone, against a
 * repository on somebody's computer, with no diff and no undo, is a way to lose
 * work rather than a way to do it.
 */
class Workspace(private val socket: AnodexSocket) {

    /**
     * Every file in the project, newest first.
     *
     * Flattened deliberately. The computer answers with a tree, which is the right
     * shape for a dock beside an editor and the wrong one for a phone: nobody wants
     * to tap through four folders on a six-inch screen to reach a file whose path
     * they already know. The question from away is "what has changed", and a flat
     * list ordered by modification time answers it in one screen.
     */
    suspend fun listFiles(): List<WorkspaceFile> =
        parseWorkspaceFiles(runCatching { socket.invoke(CHANNEL_LIST) }.getOrNull())

    suspend fun read(relativePath: String): FileContent {
        val result = runCatching {
            socket.invoke(CHANNEL_READ, listOf(JsonPrimitive(relativePath)))
        }.getOrNull() as? JsonObject
            ?: return FileContent.Failed("Your computer didn't answer.")

        if (result["ok"]?.jsonPrimitive?.contentOrNull() != "true") {
            val message = (result["error"] as? JsonObject)
                ?.get("message")?.jsonPrimitive?.contentOrNull()
            return FileContent.Failed(message ?: "That file could not be read.")
        }

        val value = result["value"] as? JsonObject
            ?: return FileContent.Failed("That file could not be read.")

        return when (value["kind"]?.jsonPrimitive?.contentOrNull()) {
            "text" -> FileContent.Text(value["content"]?.jsonPrimitive?.contentOrNull().orEmpty())
            "image" -> FileContent.Unreadable("This is an image. Open it on the computer.")
            "too-large" -> FileContent.Unreadable(
                "This file is too big to send to a phone" +
                    (sizeOf(value["sizeBytes"])?.let { " ($it)" } ?: "") + "."
            )
            else -> FileContent.Unreadable("This isn't a text file.")
        }
    }

    private fun sizeOf(element: JsonElement?): String? {
        val bytes = (element as? JsonPrimitive)?.contentOrNull()?.toDoubleOrNull() ?: return null
        return when {
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000)
            bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000)
            else -> "$bytes bytes"
        }
    }

    private companion object {
        const val CHANNEL_LIST = "workspace:list-files"
        const val CHANNEL_READ = "workspace:read-file-content"
    }
}

/**
 * The file a tool row is about, if it is about one.
 *
 * Tool titles are written for people — `Read src/index.ts`, `Edit useDragBody.ts`,
 * `Run npm test` — so the path has to be recovered from prose. That is a heuristic,
 * and it is allowed to be: getting it wrong costs a row that is not tappable, or one
 * tap that reports the file does not exist. Neither is worth a stricter rule that
 * would miss real files.
 *
 * Returns null for anything without a path shape, which is what keeps `Run npm test`
 * and `Search "orbit drag"` from pretending to be files.
 */
fun filePathIn(title: String): String? {
    // A quoted string is a search term or a command argument, never a path this can
    // usefully open, and it is the most common false positive.
    if (title.contains('"') || title.contains('\'')) return null

    return title.split(' ', '\t')
        .asSequence()
        .map { it.trim().trim(',', ';', ':', '(', ')', '`') }
        .filter { it.isNotEmpty() }
        // A path either has a directory separator or a file extension. Requiring one
        // of the two keeps ordinary words out without excluding `README.md` or a
        // bare `src/main`.
        .filter { candidate ->
            val hasSeparator = candidate.contains('/') || candidate.contains('\\')
            val hasExtension = EXTENSION.containsMatchIn(candidate)
            hasSeparator || hasExtension
        }
        // Not a URL: those belong in a browser, not the workspace reader.
        .filterNot { it.startsWith("http://") || it.startsWith("https://") }
        // An absolute path is outside the project, and the desktop resolves these
        // relative to the workspace root, so sending one asks for the wrong file.
        .filterNot { it.startsWith("/") || ABSOLUTE_WINDOWS.containsMatchIn(it) }
        .firstOrNull()
}

private val EXTENSION = Regex("\\.[A-Za-z0-9]{1,8}$")
private val ABSOLUTE_WINDOWS = Regex("^[A-Za-z]:[\\\\/]")

/** `content` on a JSON null is the string "null", which is never what a caller wants. */
private fun JsonPrimitive.contentOrNull(): String? = if (this is JsonNull) null else content

/** One file in the project the computer has open. */
data class WorkspaceFile(
    /** Relative to the workspace root, forward-slashed. Its identity, and how it is read. */
    val path: String,
    val name: String,
    val sizeBytes: Long,
    /** Last modification, epoch millis. */
    val modifiedAt: Long,
    /**
     * Whether Anodex was the last to touch it.
     *
     * The single most useful column from away: it turns a list of files into a
     * record of what the computer has been doing while nobody was watching.
     */
    val editedByAi: Boolean,
) {
    /** "src/sim" — where it lives, for the line under the name. */
    val folder: String get() = path.substringBeforeLast('/', "")
}

/**
 * Flatten the computer's tree into files, newest first.
 *
 * Folders carry nothing a phone shows, so they are walked rather than rendered.
 * Depth is bounded: a cycle cannot occur in a filesystem tree the desktop built,
 * but a bound costs nothing and a stack overflow on a malformed answer costs the
 * whole app.
 */
internal fun parseWorkspaceFiles(element: JsonElement?): List<WorkspaceFile> {
    val array = when (element) {
        is JsonArray -> element
        is JsonObject -> element["value"] as? JsonArray ?: return emptyList()
        else -> return emptyList()
    }

    val files = mutableListOf<WorkspaceFile>()
    walk(array, files, depth = 0)
    return files.sortedByDescending { it.modifiedAt }
}

private fun walk(nodes: JsonArray, into: MutableList<WorkspaceFile>, depth: Int) {
    if (depth > MAX_TREE_DEPTH) return

    for (node in nodes.filterIsInstance<JsonObject>()) {
        when (node["type"]?.jsonPrimitive?.contentOrNull) {
            "folder" -> (node["children"] as? JsonArray)?.let { walk(it, into, depth + 1) }
            "file" -> {
                val path = node["path"]?.jsonPrimitive?.contentOrNull ?: continue
                into += WorkspaceFile(
                    path = path,
                    name = node["name"]?.jsonPrimitive?.contentOrNull
                        ?: path.substringAfterLast('/'),
                    sizeBytes = node["sizeBytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    modifiedAt = node["modifiedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                    editedByAi = node["editedBy"]?.jsonPrimitive?.contentOrNull == "ai",
                )
            }
        }
    }
}

/** Deeper than any real project, and shallow enough that a bad answer cannot blow the stack. */
private const val MAX_TREE_DEPTH = 32
