package dev.anodex.mobile.ui.components

import dev.anodex.mobile.ui.theme.AnodexColors
import dev.anodex.mobile.ui.theme.LightColors
import dev.anodex.mobile.ui.theme.MidnightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The update notice is opaque.
 *
 * It is the one notice in this app drawn *over* another screen rather than
 * inside one: with the computer unreachable, `FloatingUpdateBanner` sits on top
 * of the offline screen. Its only fill was `accentSoft` — twelve percent of blue
 * — and a tint is a fill only when something opaque is underneath it. Under the
 * header there always was. Floating there was not.
 *
 * Reported from a phone: "Gort is offline" and "No answer from 3 addresses on
 * port 47800" read straight through the release notes, both sets of words
 * occupying the same space.
 *
 * The obvious test is a pixel test, and it cannot be written here — Robolectric
 * has no real window, so `captureToImage` never gets a frame. So this reads the
 * source instead. That is a weaker instrument and worth saying plainly: it can
 * only check that an opaque fill is still laid down before the tint. It exists
 * because the line it guards looks redundant, and the next person to tidy this
 * file will delete it unless something objects.
 */
class UpdateBannerOpacityTest {

    private val source = File("src/main/java/dev/anodex/mobile/ui/components/UpdateBanner.kt")

    @Test
    fun `the tint is laid over an opaque fill, not over whatever is behind`() {
        val chain = source.readLines()
            .map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

        val tint = chain.indexOfFirst { it.startsWith(".background(colors.") && it.contains("Soft") }
        assertTrue("UpdateBanner no longer tints itself; this test is about that tint", tint >= 0)

        val opaqueBefore = chain.take(tint).any {
            it.startsWith(".background(colors.bg")
        }

        assertTrue(
            "The update notice draws a translucent tint with nothing opaque under it. " +
                "It is drawn over the offline screen, so whatever is on that screen " +
                "will read through it.",
            opaqueBefore,
        )
    }

    @Test
    fun `the base is opaque in both themes and the tint is not`() {
        // Which is the whole reason one goes under the other. Stated here so that
        // making `bgSurface` translucent — or `accentSoft` opaque, which would
        // hide a missing base rather than fix it — fails something.
        for (colors in listOf<AnodexColors>(MidnightColors, LightColors)) {
            assertEquals("the notice's base must be opaque", 1f, colors.bgSurface.alpha, 0f)
            assertTrue("accentSoft is a tint, not a surface", colors.accentSoft.alpha < 1f)
        }
    }
}
