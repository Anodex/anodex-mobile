package dev.anodex.mobile.ui

import dev.anodex.mobile.ui.components.AnodexIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The icons are copied from the desktop by hand, so this is where they drift.
 *
 * There is no shared build between an Electron app and an Android one, and these
 * glyphs are bespoke — the mail envelope is cut to match the Anodex mark's facet,
 * the bot's head is a hexagon for the same reason. Nothing at runtime notices if
 * one of them is edited on the computer and not here; the two apps just slowly
 * stop looking like the same product.
 *
 * The paths below are `src/renderer/components/Icon.tsx` verbatim. When this test
 * fails, the correct fix is almost always to copy the desktop's path over, not to
 * update the expectation.
 */
class AnodexIconTest {

    @Test
    fun `chat is the desktop's speech bubble with the cut corner`() {
        assertEquals(
            listOf("M3 5a2 2 0 0 1 2-2h11l5 5v7a2 2 0 0 1-2 2H7l-4 4V5z"),
            AnodexIcon.CHAT.strokes,
        )
    }

    @Test
    fun `bot is the hexagonal head with antenna and two eyes`() {
        assertEquals(
            listOf(
                "M12 2v3",
                "M8 5h8l4 7.5L16 20H8l-4-7.5L8 5z",
                "M9.5 11.5v2",
                "M14.5 11.5v2",
            ),
            AnodexIcon.BOT.strokes,
        )
    }

    @Test
    fun `mail keeps the faceted envelope rather than a plain rectangle`() {
        assertEquals(
            listOf(
                "M2 7a2 2 0 0 1 2-2h11.5L22 10.5v6.5a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7z",
                "m3 6.4 9 6.4 5.2-5.2",
            ),
            AnodexIcon.MAIL.strokes,
        )
    }

    @Test
    fun `monitor keeps its rounded corners`() {
        // The desktop draws this one as <rect rx="2">, which has no path-parser
        // equivalent, so it is the single glyph transcribed rather than copied.
        // Square corners read as a different icon at 20dp, so the arcs are pinned.
        val box = AnodexIcon.MONITOR.strokes.first()

        assertTrue("expected arcs in $box", box.contains("a2 2 0 0 1"))
        assertEquals(4, Regex("a2 2 0 0 1").findAll(box).count())
    }

    @Test
    fun `refresh is the desktop's arrow curling back on itself`() {
        // Copied from `Icon.tsx` rather than drawn. An arrow that means "check
        // again" on the computer has to mean it on the phone, and the two split
        // into the same sub-paths so a diff between them stays line-for-line.
        assertEquals(
            listOf(
                "M21 12a9 9 0 1 1-3-6.7L21 8",
                "M21 3v5h-5",
            ),
            AnodexIcon.REFRESH.strokes,
        )
    }

    @Test
    fun `every glyph parses as a closed set of sub-paths`() {
        // A path string with a typo does not throw — it silently renders as
        // nothing, which on a tab bar looks like a blank space rather than a bug.
        for (icon in AnodexIcon.entries) {
            assertTrue("${icon.name} has no strokes", icon.strokes.isNotEmpty())
            for (stroke in icon.strokes) {
                assertTrue(
                    "${icon.name} stroke does not start with a move: $stroke",
                    stroke.startsWith("M") || stroke.startsWith("m"),
                )
                assertTrue(
                    "${icon.name} stroke has characters no path command uses: $stroke",
                    stroke.all { it.isDigit() || it in "MmLlHhVvCcSsQqTtAaZz .,-" },
                )
            }
        }
    }
}
