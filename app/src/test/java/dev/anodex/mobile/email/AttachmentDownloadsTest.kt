package dev.anodex.mobile.email

import dev.anodex.mobile.ui.screens.fileSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name a downloaded attachment is written under.
 *
 * A filename arrives from whoever sent the mail, and this is the one place in
 * the app where a stranger's string becomes a path. The desktop learned this
 * already -- its save dialog reduces the name to its last segment, after an
 * attachment called `..\..\settings.json` opened the dialog pointed somewhere
 * the reader had never navigated to.
 */
class AttachmentDownloadsTest {

    @Test
    fun `a path in the name is reduced to the file`() {
        assertEquals("settings.json", safeFileName("..\\..\\settings.json"))
        assertEquals("report.pdf", safeFileName("/etc/passwd/../report.pdf"))
        assertEquals("notes.txt", safeFileName("folder/notes.txt"))
    }

    @Test
    fun `an absolute path does not stay absolute`() {
        val name = safeFileName("C:\\Windows\\System32\\drivers\\etc\\hosts")
        assertEquals("hosts", name)
        assertFalse(name.contains('\\'))
        assertFalse(name.contains('/'))
    }

    @Test
    fun `characters a filesystem refuses are dropped`() {
        assertEquals("report2026.pdf", safeFileName("report:2026?.pdf"))
        assertFalse(safeFileName("a<b>c|d.txt").any { it in "<>|:*?\"" })
    }

    @Test
    fun `a name that is only trouble becomes something writable`() {
        // A file called nothing cannot be written or found again. Every one of
        // these reduces to empty, and empty has to become a name.
        for (hostile in listOf("", "   ", "...", "/", "\\", "??")) {
            assertTrue("$hostile produced a blank name", safeFileName(hostile).isNotBlank())
        }
    }

    @Test
    fun `an ordinary name is left alone`() {
        // The common case, which a sanitiser earns its keep by not mangling.
        assertEquals("Q3 report (final).pdf", safeFileName("Q3 report (final).pdf"))
        assertEquals("photo_2026-09-18.jpg", safeFileName("photo_2026-09-18.jpg"))
    }

    @Test
    fun `a very long name is cut to something a filesystem accepts`() {
        assertTrue(safeFileName("a".repeat(400) + ".pdf").length <= 120)
    }

    @Test
    fun `sizes read the way a file manager writes them`() {
        assertEquals("512 B", fileSize(512))
        assertEquals("2 KB", fileSize(2048))
        assertEquals("2.4 MB", fileSize(2_500_000))
        assertEquals("1.0 GB", fileSize(1024L * 1024 * 1024))
    }

    @Test
    fun `an unknown size says so rather than showing zero`() {
        // Some providers omit it. "0 B" reads as an empty file, which is a
        // different and wrong claim about somebody's attachment.
        assertEquals("Unknown size", fileSize(0))
        assertEquals("Unknown size", fileSize(-1))
    }
}
