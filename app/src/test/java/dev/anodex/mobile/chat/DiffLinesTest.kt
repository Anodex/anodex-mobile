package dev.anodex.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The change somebody is being asked to approve.
 *
 * This is the highest-stakes text in the app: it is what a person reads before
 * letting a machine write to their files from another room. A diff that quietly
 * misses a line is worse than no diff, because it will be believed — so these
 * cases are mostly about *not losing* anything.
 */
class DiffLinesTest {

    private fun kinds(before: String, after: String) =
        diffLines(before, after).filter { it.text != "⋯" }.map { it.kind to it.text }

    @Test
    fun `an added line is marked added and nothing else moves`() {
        val result = kinds("a\nb", "a\nnew\nb")

        assertTrue(result.contains(DiffLine.Kind.ADDED to "new"))
        assertTrue(result.contains(DiffLine.Kind.KEPT to "a"))
        assertTrue(result.contains(DiffLine.Kind.KEPT to "b"))
    }

    @Test
    fun `a removed line is marked removed`() {
        val result = kinds("a\ngone\nb", "a\nb")

        assertTrue(result.contains(DiffLine.Kind.REMOVED to "gone"))
    }

    @Test
    fun `a changed line reads as one out and one in`() {
        val result = kinds("a\nold\nb", "a\nnew\nb")

        assertTrue(result.contains(DiffLine.Kind.REMOVED to "old"))
        assertTrue(result.contains(DiffLine.Kind.ADDED to "new"))
    }

    @Test
    fun `identical files produce no change at all`() {
        val changed = diffLines("a\nb\nc", "a\nb\nc").filter { it.kind != DiffLine.Kind.KEPT }

        assertTrue(changed.isEmpty())
    }

    @Test
    fun `every changed line survives the context trimming`() {
        // The trimming exists so a four-line edit in a long file is not buried in
        // the file. It must never drop the edit itself — which is the whole reason
        // somebody opened this card.
        val before = (1..200).joinToString("\n") { "line $it" }
        val after = before.replace("line 100", "line 100 EDITED")

        val added = diffLines(before, after).filter { it.kind == DiffLine.Kind.ADDED }
        val removed = diffLines(before, after).filter { it.kind == DiffLine.Kind.REMOVED }

        assertEquals(listOf("line 100 EDITED"), added.map { it.text })
        assertEquals(listOf("line 100"), removed.map { it.text })
    }

    @Test
    fun `a long file is not listed in full to show one edit`() {
        val before = (1..200).joinToString("\n") { "line $it" }
        val after = before.replace("line 100", "line 100 EDITED")

        // Two lines of context either side plus the change, not two hundred.
        assertTrue(diffLines(before, after).size < 20)
    }

    @Test
    fun `an enormous file says so instead of trying`() {
        // The table is old × new integers. Ten thousand lines a side is a hundred
        // million cells: minutes of work and a heap the app does not have. Refusing
        // is the honest answer; freezing is not.
        val huge = (1..5_000).joinToString("\n") { "line $it" }

        val result = diffLines(huge, huge + "\nmore")

        assertEquals(1, result.size)
        assertTrue(result.single().text.contains("Too large to compare"))
    }

    @Test
    fun `writing a new file shows every line as added`() {
        val result = diffLines("", "first\nsecond")

        assertEquals(
            listOf("first", "second"),
            result.filter { it.kind == DiffLine.Kind.ADDED }.map { it.text },
        )
    }
}
