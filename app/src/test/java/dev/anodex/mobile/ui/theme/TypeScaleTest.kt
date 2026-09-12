package dev.anodex.mobile.ui.theme

import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a text-size preference is allowed to do to the scale.
 *
 * The ladder in [AnodexTypography] is designed. Every step has a reason written beside
 * it, and `chatBody` sits deliberately off the ladder because a reply is read where
 * everything else is scanned.
 *
 * So a preference multiplies rather than setting sizes. The relationships survive, and
 * somebody who asks for larger text gets a larger version of the same design — not a
 * flattened one where body and metadata have converged.
 */
class TypeScaleTest {

    @Test
    fun `the designed scale is what medium means`() {
        assertEquals(1f, FontScale.MEDIUM.factor)

        val designed = AnodexTypography()
        val medium = AnodexTypography(scale = FontScale.MEDIUM.factor)

        assertEquals(designed.body.fontSize, medium.body.fontSize)
        assertEquals(designed.chatBody.fontSize, medium.chatBody.fontSize)
    }

    @Test
    fun `larger moves every step, not just body`() {
        val large = AnodexTypography(scale = FontScale.LARGE.factor)
        val designed = AnodexTypography()

        for ((name, pair) in mapOf(
            "body" to (designed.body.fontSize to large.body.fontSize),
            "chatBody" to (designed.chatBody.fontSize to large.chatBody.fontSize),
            "meta" to (designed.meta.fontSize to large.meta.fontSize),
            "label" to (designed.label.fontSize to large.label.fontSize),
            "title" to (designed.title.fontSize to large.title.fontSize),
        )) {
            assertTrue("$name did not grow", pair.second.value > pair.first.value)
        }
    }

    @Test
    fun `the ladder keeps its order at every size`() {
        // The failure this guards: scaling some steps and not others, or rounding two
        // neighbours onto the same value — which reads as a flattened interface rather
        // than a resized one.
        for (scale in FontScale.entries) {
            val type = AnodexTypography(scale = scale.factor)

            assertTrue(
                "${scale.name}: meta is not below label",
                type.meta.fontSize.value < type.label.fontSize.value,
            )
            assertTrue(
                "${scale.name}: label is not below body",
                type.label.fontSize.value < type.body.fontSize.value,
            )
            assertTrue(
                "${scale.name}: body is not below chatBody",
                type.body.fontSize.value < type.chatBody.fontSize.value,
            )
            assertTrue(
                "${scale.name}: chatBody is not below title",
                type.chatBody.fontSize.value < type.title.fontSize.value,
            )
        }
    }

    @Test
    fun `line height grows with the text it wraps`() {
        // Scaling the size and not the leading is how large text ends up overlapping.
        val large = AnodexTypography(scale = FontScale.LARGE.factor)
        val designed = AnodexTypography()

        assertTrue(large.chatBody.lineHeight.value > designed.chatBody.lineHeight.value)
        assertTrue(large.body.lineHeight.value > designed.body.lineHeight.value)
    }

    @Test
    fun `the smallest text stays legible at the smallest setting`() {
        // `badge` is the floor of the whole app, and `xxs` is already described in
        // Typography.kt as "the practical floor on Android". Small must not push it
        // under 9sp, which is where it stops being small and starts being unreadable.
        val small = AnodexTypography(scale = FontScale.SMALL.factor)

        assertTrue(
            "badge fell to ${small.badge.fontSize.value}sp",
            small.badge.fontSize.value >= 9f,
        )
    }

    @Test
    fun `choosing a face changes the interface and not the code`() {
        // Monospace as a UI choice is a real option on the desktop and mirrored here.
        // But chat code and tool output are fixed-pitch for a reason that has nothing
        // to do with preference, and stay that way under every choice.
        val mono = AnodexTypography(family = UiFont.MONO.family)
        val sans = AnodexTypography(family = UiFont.SANS.family)

        assertEquals(FontFamily.Monospace, mono.body.fontFamily)
        assertEquals(FontFamily.SansSerif, sans.body.fontFamily)

        assertEquals(FontFamily.Monospace, sans.mono.fontFamily)
        assertEquals(FontFamily.Monospace, sans.chatMono.fontFamily)
    }

    @Test
    fun `system is the default, because it is what every other app uses`() {
        assertEquals(FontFamily.Default, UiFont.SYSTEM.family)
        assertEquals(FontFamily.Default, AnodexTypography().body.fontFamily)
    }
}
