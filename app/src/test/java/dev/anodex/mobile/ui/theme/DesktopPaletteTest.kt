package dev.anodex.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import dev.anodex.mobile.chat.ReadingProgress
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The palette is copied from the desktop by hand, so this is where it drifts.
 *
 * `AnodexIconTest` does this job for the glyphs and has caught real divergence.
 * Nothing did it for the colours, the spacing or the radii — and those are
 * copied across the same seam, from the same files, by the same hand. Today a
 * third thing joined them (`Brand`), which is what prompted writing this down.
 *
 * The values below are `src/renderer/styles/themes/midnight.css` and
 * `src/renderer/styles/theme.css` verbatim. When this test fails, the correct
 * fix is almost always to copy the desktop's value over, not to update the
 * expectation — the same rule the icon test states.
 *
 * What this can and cannot do: it catches a change made on *this* side, which
 * is the common case, because the phone is where the copying happens. It cannot
 * see the desktop, so a value changed there and not here still passes. That gap
 * is real and is the reason to keep the list short and the copying rare.
 */
class DesktopPaletteTest {

    private fun hex(color: Color): String =
        "#%06X".format(color.value.shr(32).toLong() and 0xFFFFFF)

    @Test
    fun `the brand accents are the desktop's`() {
        // These three carry the mark: the gradient is drawn from violet to
        // accent, and every branded control on both apps is painted with them.
        assertEquals("#4F8CFF", hex(MidnightColors.accent))
        assertEquals("#7C5CFF", hex(MidnightColors.accentViolet))
        assertEquals("#38BDF8", hex(MidnightColors.accentCyan))
        assertEquals("#74F0A8", hex(MidnightColors.accentGreen))
    }

    @Test
    fun `the midnight surfaces are the desktop's`() {
        assertEquals("#080808", hex(MidnightColors.bgBase))
        assertEquals("#0C0C0C", hex(MidnightColors.bgApp))
        assertEquals("#111111", hex(MidnightColors.bgSurface))
        assertEquals("#161616", hex(MidnightColors.bgSurface2))
        assertEquals("#1C1C1C", hex(MidnightColors.bgElevated))
        assertEquals("#0F0F0F", hex(MidnightColors.bgInput))
    }

    @Test
    fun `the midnight ink and edges are the desktop's`() {
        assertEquals("#F0F0F0", hex(MidnightColors.text))
        assertEquals("#A0A0A0", hex(MidnightColors.textMuted))
        assertEquals("#606060", hex(MidnightColors.textFaint))
        assertEquals("#1F1F1F", hex(MidnightColors.border))
        assertEquals("#2A2A2A", hex(MidnightColors.borderStrong))
    }

    @Test
    fun `the spacing scale is the desktop's`() {
        // --space-1 through --space-10 in theme.css.
        assertEquals(4, Spacing.x1.value.toInt())
        assertEquals(8, Spacing.x2.value.toInt())
        assertEquals(12, Spacing.x3.value.toInt())
        assertEquals(16, Spacing.x4.value.toInt())
        assertEquals(20, Spacing.x5.value.toInt())
        assertEquals(24, Spacing.x6.value.toInt())
        assertEquals(32, Spacing.x8.value.toInt())
        assertEquals(40, Spacing.x10.value.toInt())
    }

    @Test
    fun `the reading threshold matches the desktop's`() {
        // readingProgressStore.ts. Below this much left to read, neither app
        // shows a percentage — so they have to agree or the same conversation
        // reports differently depending on which screen you are looking at.
        assertEquals(1_024L, ReadingProgress.MIN_VISIBLE_READ_TOKENS)
    }
}
