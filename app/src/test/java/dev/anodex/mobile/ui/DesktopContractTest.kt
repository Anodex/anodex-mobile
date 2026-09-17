package dev.anodex.mobile.ui

import androidx.compose.ui.graphics.Color
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.theme.MidnightColors
import dev.anodex.mobile.ui.theme.Spacing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of the seam the other tests admit they cannot see.
 *
 * `DesktopPaletteTest` and `AnodexIconTest` pin this app's values against numbers
 * typed out by hand, and both say the same thing about their limits: they catch a
 * change made *here*, because here is where the copying happens, and a value
 * changed on the desktop and not here still passes.
 *
 * `protocol/anodex-design.json` is the desktop's own answer, generated from its
 * CSS and its `Icon.tsx` and copied in by `tools/sync-design-contract.sh`. This
 * test reads it. The drift it cannot see is now the drift it fails on.
 *
 * When this fails, the fix is almost always to copy the desktop's value over —
 * the same rule the two older tests state. When it is not, the right answer is a
 * comment here saying which value is deliberately different and why, because a
 * deliberate difference with no note is indistinguishable from a mistake.
 */
class DesktopContractTest {

    private val contract: JsonObject by lazy {
        val stream = javaClass.getResourceAsStream("/anodex-design.json")
            ?: error(
                "protocol/anodex-design.json is not on the test classpath — " +
                    "run tools/sync-design-contract.sh"
            )
        Json.parseToJsonElement(stream.reader().readText()).jsonObject
    }

    private fun hex(color: Color): String =
        "#%06x".format(color.value.shr(32).toLong() and 0xFFFFFF)

    /**
     * The desktop's variable names against this app's colours.
     *
     * Written out rather than reflected over: `Color` is a value class, so its
     * getters are name-mangled in the bytecode and a reflective lookup finds
     * nothing — which is the failure that reports success. The count assertion in
     * the test below is what keeps this list from quietly shrinking.
     */
    private val phoneColours: Map<String, Color> = mapOf(
        "--bg-base" to MidnightColors.bgBase,
        "--bg-app" to MidnightColors.bgApp,
        "--bg-surface" to MidnightColors.bgSurface,
        "--bg-surface-2" to MidnightColors.bgSurface2,
        "--bg-elevated" to MidnightColors.bgElevated,
        "--bg-input" to MidnightColors.bgInput,
        "--border" to MidnightColors.border,
        "--border-strong" to MidnightColors.borderStrong,
        "--text" to MidnightColors.text,
        "--text-muted" to MidnightColors.textMuted,
        "--text-faint" to MidnightColors.textFaint,
        "--accent" to MidnightColors.accent,
        "--accent-violet" to MidnightColors.accentViolet,
        "--accent-cyan" to MidnightColors.accentCyan,
        "--accent-green" to MidnightColors.accentGreen,
    )

    @Test
    fun `the contract is the one this app was built against`() {
        // A major bump means a name disappeared or changed meaning, and a test
        // that quietly compares nothing is the failure this whole file exists to
        // prevent.
        assertTrue(
            "unexpected design contract version",
            contract["version"]!!.jsonPrimitive.content.startsWith("1."),
        )
    }

    @Test
    fun `every midnight colour the desktop states is the colour this app draws`() {
        val midnight = contract["midnight"]!!.jsonObject
        val checked = mutableListOf<String>()

        for ((name, phone) in phoneColours) {
            val desktop = midnight[name]?.jsonPrimitive?.content
                ?: error("$name is no longer in the desktop's palette")
            assertEquals("$name drifted from the desktop", desktop.lowercase(), hex(phone))
            checked += name
        }

        // Guards the list itself, not the colours: a map that lost half its
        // entries would still pass every assertion above.
        assertEquals("the colour list changed size", 15, checked.size)
    }

    @Test
    fun `the spacing scale is the desktop's`() {
        val scale = contract["scale"]!!.jsonObject
        val phone = mapOf(
            "--space-1" to Spacing.x1, "--space-2" to Spacing.x2, "--space-3" to Spacing.x3,
            "--space-4" to Spacing.x4, "--space-5" to Spacing.x5, "--space-6" to Spacing.x6,
            "--space-8" to Spacing.x8, "--space-10" to Spacing.x10,
        )

        for ((name, dp) in phone) {
            val desktop = scale[name]?.jsonPrimitive?.content
                ?: error("$name is no longer in the desktop's scale")
            assertEquals(name, desktop.removeSuffix("px").toInt(), dp.value.toInt())
        }
    }

    @Test
    fun `every glyph both apps have is drawn from the same shapes`() {
        val glyphs = contract["glyphs"]!!.jsonObject
        var exact = 0
        var counted = 0

        for (icon in AnodexIcon.entries) {
            val name = icon.name.lowercase().replace('_', '-')
            val shape = glyphs[name]?.jsonObject ?: continue

            val elements = shape["elements"]!!.jsonArray.map { it.jsonPrimitive.content }
            val paths = shape["paths"]!!.jsonArray.map { it.jsonPrimitive.content }

            if (paths.size == elements.size) {
                // Drawn entirely from `<path>` over there, so it is comparable
                // character for character — this is the path-for-path copying the
                // icon table describes.
                // Joined, because where a subpath boundary falls is not part of
                // the drawing: the desktop writes `bot`'s two eyes as one path
                // with two `M` commands and this app writes them as two strokes,
                // which renders identically. Concatenating still catches a
                // reordering, and still catches any change to the geometry.
                assertEquals(
                    "$name drifted from the desktop",
                    paths.joinToString(""),
                    icon.strokes.joinToString(""),
                )
                exact++
            } else {
                // Built from `<line>`, `<circle>` or `<rect>` on the desktop, which
                // a path parser has no primitive for, so this app transcribes each
                // one as a path. The shapes cannot be compared, but the count can —
                // and a stroke added on one side and not the other is exactly the
                // drift that produced an icon missing its dot.
                assertEquals(
                    "$name has ${icon.strokes.size} strokes to the desktop's ${elements.size} shapes",
                    elements.size,
                    icon.strokes.size,
                )
                counted++
            }
        }

        assertTrue("compared only $exact glyphs path-for-path", exact >= 15)
        assertTrue("compared only $counted glyphs by shape count", counted >= 10)
    }
}
