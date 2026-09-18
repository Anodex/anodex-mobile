package dev.anodex.mobile.email

import dev.anodex.mobile.ui.screens.remoteImageCount
import dev.anodex.mobile.ui.screens.remoteImageUrls
import dev.anodex.mobile.ui.screens.wrapMailHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a message is allowed to fetch, and when.
 *
 * The desktop parks every remote URL on `data-remote-src` so that opening a
 * message fetches nothing. That is the mechanism that stops a tracking pixel
 * telling a sender the mail was read, when it was read, and roughly from where --
 * and it is one `replace` away from being undone by accident, silently, in a way
 * no screenshot would show.
 */
class MailBodyTest {

    private val newsletter = """
        <p>Hello</p>
        <img data-remote-src="https://sli.msuv.net/imp?id=1&stpe=pixel">
        <img data-remote-src="https://cdn.example.com/header.png">
        <img src="data:image/png;base64,AAAA">
    """.trimIndent()

    @Test
    fun `nothing remote is fetched until it is asked for`() {
        val page = wrapMailHtml(newsletter, images = emptyMap(), textArgb = 0xFFFFFF, backgroundArgb = 0)

        // Still parked. If a real `src="https` ever appears, opening a message has
        // started reporting back to whoever sent it.
        //
        // Matched with a boundary rather than as a substring: `data-remote-src="`
        // ends in `src="`, so a plain `contains` finds the parked attribute and
        // reports the failure it was written to catch. The first draft of this test
        // did exactly that.
        val live = Regex("(^|[^-])src=\"https")
        assertFalse("a remote url became a live src", live.containsMatchIn(page))
        assertTrue("the parked attribute should survive", page.contains("data-remote-src="))

        // The inline attachment is not remote and was never held back.
        assertTrue(page.contains("src=\"data:image/png;base64,AAAA\""))
    }

    @Test
    fun `asking for one image does not release the others`() {
        val page = wrapMailHtml(
            newsletter,
            images = mapOf("https://cdn.example.com/header.png" to "data:image/png;base64,BBBB"),
            textArgb = 0xFFFFFF,
            backgroundArgb = 0,
        )

        assertTrue(page.contains("src=\"data:image/png;base64,BBBB\""))
        // The tracking pixel was in the same message and is still parked. Matching
        // by exact URL rather than by pattern is what keeps that true.
        assertTrue(page.contains("data-remote-src=\"https://sli.msuv.net/imp?id=1&stpe=pixel\""))
    }

    @Test
    fun `the held-back images are counted and listed`() {
        // The count is shown on the button, because "2 images" and "60 images" are
        // different decisions to be asked to make.
        assertEquals(2, remoteImageCount(newsletter))
        assertEquals(
            listOf(
                "https://sli.msuv.net/imp?id=1&stpe=pixel",
                "https://cdn.example.com/header.png",
            ),
            remoteImageUrls(newsletter),
        )
    }

    @Test
    fun `a message with nothing remote offers nothing`() {
        val plain = "<p>Thursday works.</p>"
        assertEquals(0, remoteImageCount(plain))
        assertTrue(remoteImageUrls(plain).isEmpty())
    }

    @Test
    fun `wide layout is held to the screen`() {
        // Mail is written for a desktop window. Without this a 700px table scrolls
        // the whole message sideways, which on a phone reads as a broken page.
        val page = wrapMailHtml("<table width=700><tr><td>x</td></tr></table>", emptyMap(), 0xFFFFFF, 0)
        assertTrue(page.contains("max-width: 100%"))
    }
}
