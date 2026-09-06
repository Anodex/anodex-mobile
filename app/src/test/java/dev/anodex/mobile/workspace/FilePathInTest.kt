package dev.anodex.mobile.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Deciding which tool rows lead to a file.
 *
 * Tool titles are written for people — `Read src/index.ts`, `Run npm test`,
 * `Search "orbit drag"` — so the path has to be recovered from prose. That makes
 * this a heuristic, and it is allowed to be one: a miss costs a row that is not
 * tappable, which is invisible.
 *
 * The failure worth preventing is the other direction. A row that *looks* tappable
 * and then reports that the file does not exist teaches the user that the feature is
 * broken, and they stop using it — so the bias here is firmly towards saying no.
 */
class FilePathInTest {

    @Test
    fun `a read finds its file`() {
        assertEquals("src/sim/OrbitPanel.tsx", filePathIn("Read src/sim/OrbitPanel.tsx"))
    }

    @Test
    fun `an edit finds its file`() {
        assertEquals("src/sim/useDragBody.ts", filePathIn("Edit src/sim/useDragBody.ts"))
    }

    @Test
    fun `a bare filename in the project root counts`() {
        assertEquals("README.md", filePathIn("Read README.md"))
    }

    @Test
    fun `a directory with no extension still counts`() {
        assertEquals("src/main", filePathIn("List src/main"))
    }

    @Test
    fun `a shell command is not a file`() {
        // The most common row after reads and edits, and the one that would produce
        // the most dead taps.
        assertNull(filePathIn("Run npm test"))
        assertNull(filePathIn("Run git status"))
    }

    @Test
    fun `a search term is not a file`() {
        // A quoted string is a query or a command argument. `"orbit drag"` has no
        // extension, but `grep "*.ts"` would sail through the extension rule.
        assertNull(filePathIn("Search \"orbit drag\""))
        assertNull(filePathIn("Search \"config.json\""))
    }

    @Test
    fun `a URL is not a workspace file`() {
        // It has slashes and an extension and belongs in a browser.
        assertNull(filePathIn("Fetch https://example.com/index.html"))
    }

    @Test
    fun `an absolute path is refused`() {
        // The desktop resolves these relative to the workspace root, so sending one
        // asks for a different file than the one named — the worst outcome available.
        assertNull(filePathIn("Read /etc/hosts"))
        assertNull(filePathIn("Read C:/Windows/system.ini"))
        assertNull(filePathIn("Read C:\\Windows\\system.ini"))
    }

    @Test
    fun `ordinary prose yields nothing`() {
        assertNull(filePathIn("Thinking"))
        assertNull(filePathIn("Ran a tool"))
        assertNull(filePathIn(""))
    }

    @Test
    fun `trailing punctuation is trimmed off the path`() {
        // Titles are prose, and prose has commas in it.
        assertEquals("src/index.ts", filePathIn("Read src/index.ts, then edit it"))
        assertEquals("src/index.ts", filePathIn("Edit `src/index.ts`"))
    }

    @Test
    fun `the first path wins when a title names two`() {
        // `Move a.ts b.ts` — the first is the one the row is about, and guessing
        // between them would open the wrong file half the time.
        assertEquals("src/a.ts", filePathIn("Move src/a.ts src/b.ts"))
    }

    @Test
    fun `a sentence-ending full stop does not become an extension`() {
        // "Finished." would otherwise look like a file with a zero-length extension;
        // the extension rule requires at least one character after the dot.
        assertNull(filePathIn("Finished."))
        assertNull(filePathIn("Done reading."))
    }
}
