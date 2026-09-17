package dev.anodex.mobile.workspace

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What goes on the wire when the phone asks for a file.
 *
 * The computer has two answers to "where do this project's files live". Asked
 * without a project it answers about the *active* one; asked with a project it
 * answers about that one. A phone reading a conversation it scrolled to is
 * frequently not looking at the active project, which is how the diff for a turn
 * and the file behind that diff came to read out of two different folders.
 *
 * So the project is sent when it is known. The shape matters in both directions:
 * sending a trailing `null` to an older computer is a frame it has never seen,
 * and leaving the argument off is the frame it has always seen.
 */
class WorkspaceArgumentsTest {

    private fun argsOf(path: String, projectId: String?): List<String?> {
        // Mirrors `Workspace.arguments`, which is private for the same reason it
        // is short: it is one rule, and this is the rule.
        val built = if (projectId == null) {
            listOf(JsonPrimitive(path))
        } else {
            listOf(JsonPrimitive(path), JsonPrimitive(projectId))
        }
        return JsonArray(built).map { (it as JsonPrimitive).content }
    }

    @Test
    fun `a known project travels beside the path`() {
        assertEquals(listOf("src/main.rs", "p-sandbox"), argsOf("src/main.rs", "p-sandbox"))
    }

    @Test
    fun `an unknown project is left off, not sent as null`() {
        // An older computer reads a second argument it does not expect. Leaving
        // it off keeps the frame byte-identical to every build before this one.
        assertEquals(listOf("src/main.rs"), argsOf("src/main.rs", null))
    }
}
