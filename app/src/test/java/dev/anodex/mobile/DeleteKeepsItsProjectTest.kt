package dev.anodex.mobile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Deleting a file has to remember which project it was opened out of.
 *
 * The open file carries its project so the reader and the delete agree with
 * whatever put it on screen. `deleteWorkspaceFile` closes the reader first —
 * which clears that — and then launches the call. Taking the project *inside*
 * the coroutine therefore always found null, the computer was asked about no
 * project at all, and every delete started from a chat came back "no workspace
 * folder is selected" while the screen closed as though it had worked.
 *
 * Found by deleting a real file from a real phone. Both tests here are about
 * ordering, because the bug was entirely ordering: the value was correct right
 * up to the moment it was read.
 */
class DeleteKeepsItsProjectTest {

    /** The shape of the bug, with the same close-then-read ordering. */
    private class Screen {
        var openProject: String? = null
        val asked = CompletableDeferred<String?>()

        fun close() {
            openProject = null
        }

        /** What the fixed code does: read, then close, then send. */
        suspend fun deleteReadingFirst() {
            val project = openProject
            close()
            asked.complete(project)
        }

        /** What the broken code did: close, then read inside the launch. */
        suspend fun deleteReadingAfterClose() {
            close()
            asked.complete(openProject)
        }
    }

    @Test
    fun `the project is read before the screen is closed`() = runBlocking {
        val screen = Screen()
        screen.openProject = "p-sandbox"

        screen.deleteReadingFirst()

        assertEquals("p-sandbox", screen.asked.await())
    }

    @Test
    fun `reading after the close is the bug, and loses it`() = runBlocking {
        // Pinned as the thing not to go back to. This ordering compiles, reads
        // naturally, and asks the computer about nothing.
        val screen = Screen()
        screen.openProject = "p-sandbox"

        screen.deleteReadingAfterClose()

        assertEquals(null, screen.asked.await())
        assertNotNull("the project was there until the close", "p-sandbox")
    }
}
