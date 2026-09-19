package dev.anodex.mobile.email

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A refusal has to arrive as a refusal, with its reason attached.
 *
 * The recurring defect in this codebase is a failure that reaches the reader
 * having lost everything that made it useful. `flag`, `trash` and `move` each
 * read the envelope by hand and returned a bare `false`, so the computer's own
 * `error.message` was dropped on the floor and the phone invented "Your
 * computer would not delete that." in its place -- a sentence that fits every
 * possible cause and identifies none of them.
 *
 * `trash`'s own note says the computer "refuses rather than guessing when an
 * account has no trash, and says so". It did say so. Nobody was listening.
 */
class MailRefusalTest {

    /**
     * The envelope is read in one place.
     *
     * Not a style rule. Three copies of the same check is how one of them comes
     * to be missing the part that repeats the desktop's words, which is exactly
     * what happened -- `unwrapOrThrow` was sitting in the same file, used four
     * lines away, while these three did it themselves.
     */
    @Test
    fun `nothing in the mail client reads the envelope by hand`() {
        val offenders = source("email/Email.kt").lines()
            .withIndex()
            // Comments are where the rule gets explained, including the note
            // above `send` about why it used to do this. Prose about the bug is
            // not the bug.
            .filterNot { (_, line) -> line.trimStart().startsWith("//") || line.trimStart().startsWith("*") }
            .filter { (_, line) -> """\["ok"\]""".toRegex().containsMatchIn(line) }
            .map { (n, line) -> "line ${n + 1}: ${line.trim()}" }

        assertEquals(
            "Envelope checks belong in unwrapOrThrow, which repeats the computer's own words",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * Every flag can name itself, because the sentence is built from it.
     *
     * `unwrapOrThrow` falls back to "Your computer would not $what." when the
     * desktop sends no message of its own, so a missing or badly-worded phrase
     * here becomes a sentence the reader sees.
     */
    @Test
    fun `every flag finishes the sentence it is dropped into`() {
        for (flag in MailFlag.entries) {
            val sentence = "Your computer would not ${flag.what}."
            assertTrue("$flag has no phrase", flag.what.isNotBlank())
            assertTrue(
                "$flag reads badly as: $sentence",
                sentence.first().isUpperCase() && !flag.what.first().isUpperCase(),
            )
        }
    }

    @Test
    fun `the phrases say what actually happened`() {
        // Spelled out rather than derived from `wire`, because "mark_unread"
        // becomes "mark unread" and "unarchive" becomes "unarchive that",
        // which is not a thing anybody says.
        assertEquals("archive that", MailFlag.ARCHIVE.what)
        assertEquals("put that back", MailFlag.UNARCHIVE.what)
        assertEquals("mark that unread", MailFlag.UNREAD.what)
    }

    private fun source(relative: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/java/dev/anodex/mobile/$relative")
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("Could not find $relative from ${File("").absolutePath}")
    }
}
