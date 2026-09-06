package dev.anodex.mobile.workspace

import dev.anodex.mobile.transport.AnodexSocket
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
