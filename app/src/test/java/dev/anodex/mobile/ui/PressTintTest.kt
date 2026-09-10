package dev.anodex.mobile.ui

import dev.anodex.mobile.ui.theme.LightColors
import dev.anodex.mobile.ui.theme.MidnightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wash every control in the app wears while a finger is on it.
 *
 * Worth pinning because it is derived, invisible in a diff, and easy to get wrong
 * in a direction nobody notices until it ships: a little too much alpha and a press
 * reads as a selection that stayed behind, a little too little and the control looks
 * like it did not register the tap at all.
 *
 * A new palette gets this for free — that is the point of deriving it — so these
 * tests are really about the *rule*, and they will fail if somebody replaces the
 * rule with a literal.
 */
class PressTintTest {

    @Test
    fun `the tint is the palette's own text colour, not a grey of its own`() {
        // Same hue as the page's words, so it belongs to the theme rather than
        // sitting on top of it. Only the alpha differs.
        assertEquals(MidnightColors.text.red, MidnightColors.pressTint.red, 0.0001f)
        assertEquals(MidnightColors.text.green, MidnightColors.pressTint.green, 0.0001f)
        assertEquals(MidnightColors.text.blue, MidnightColors.pressTint.blue, 0.0001f)

        assertEquals(LightColors.text.red, LightColors.pressTint.red, 0.0001f)
        assertEquals(LightColors.text.green, LightColors.pressTint.green, 0.0001f)
        assertEquals(LightColors.text.blue, LightColors.pressTint.blue, 0.0001f)
    }

    @Test
    fun `light carries less of it than dark`() {
        // The same alpha of warm charcoal on cream is a heavier mark than that alpha
        // of near-white on near-black. Equal numbers would make the light theme look
        // like it selects what the dark theme merely presses.
        assertTrue(
            "light press tint should be lighter than dark's",
            LightColors.pressTint.alpha < MidnightColors.pressTint.alpha,
        )
    }

    @Test
    fun `both stay inside the band where a press is a press`() {
        for ((name, colors) in listOf("Midnight" to MidnightColors, "Light" to LightColors)) {
            val alpha = colors.pressTint.alpha
            assertTrue("$name press tint is invisible at $alpha", alpha >= 0.04f)
            assertTrue("$name press tint reads as a selection at $alpha", alpha <= 0.14f)
        }
    }
}
