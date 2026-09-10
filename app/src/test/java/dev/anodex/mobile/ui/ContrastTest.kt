package dev.anodex.mobile.ui

import androidx.compose.ui.graphics.Color
import dev.anodex.mobile.ui.theme.AnodexColors
import dev.anodex.mobile.ui.theme.LightColors
import dev.anodex.mobile.ui.theme.MidnightColors
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Both themes are first-class, and this is where that stops being a claim.
 *
 * `AGENTS.md` says the warm light palette is a deliberate differentiator and must be
 * validated as carefully as dark. It had not been. `accent`, `danger`, `warn` and
 * `success` were shared across both themes and chosen against a near-black field;
 * measured as text on cream they came out at 3.03, 3.13, 1.91 and 1.90 to one. The
 * logo ramp was worse — `accentGreen` at 1.14 is not a colour, it is a rumour. The
 * same values on Midnight measure between 5.3 and 9.7, so nobody working in dark
 * would ever have seen it.
 *
 * The `*Ink` tokens are the fix and these are the guard. They run on every push,
 * which is the point: the failure mode here is not writing the wrong colour, it is
 * adding a token later and never checking it against the pale ground.
 *
 * WCAG 2.1 thresholds: 4.5:1 for text, 3:1 for a graphical object. Everything below
 * is held to the text one, because every one of these is used as text somewhere.
 */
class ContrastTest {

    /** Every ground an ink can land on, in either theme. */
    private fun grounds(colors: AnodexColors) = mapOf(
        "bgBase" to colors.bgBase,
        "bgApp" to colors.bgApp,
        "bgSurface" to colors.bgSurface,
        "bgSurface2" to colors.bgSurface2,
        "bgElevated" to colors.bgElevated,
        "bgInput" to colors.bgInput,
    )

    private fun inks(colors: AnodexColors) = mapOf(
        "accentInk" to colors.accentInk,
        "dangerInk" to colors.dangerInk,
        "warnInk" to colors.warnInk,
        "successInk" to colors.successInk,
        "accentGreenInk" to colors.accentGreenInk,
        "accentCyanInk" to colors.accentCyanInk,
    )

    /** The 12% wash each status colour is read against inside a notice. */
    private fun softGrounds(colors: AnodexColors) = mapOf(
        "accentSoft" to Pair(colors.accentInk, over(colors.accent, 0.12f, colors.bgApp)),
        "dangerSoft" to Pair(colors.dangerInk, over(colors.danger, 0.12f, colors.bgApp)),
        "warnSoft" to Pair(colors.warnInk, over(colors.warn, 0.12f, colors.bgApp)),
        "successSoft" to Pair(colors.successInk, over(colors.success, 0.12f, colors.bgApp)),
    )

    @Test
    fun `every ink is readable on every surface of its own theme`() {
        for ((theme, colors) in themes()) {
            for ((inkName, ink) in inks(colors)) {
                for ((groundName, ground) in grounds(colors)) {
                    val ratio = contrast(ink, ground)
                    assertTrue(
                        "$theme: $inkName on $groundName is ${"%.2f".format(ratio)}:1, under 4.5",
                        ratio >= 4.5f,
                    )
                }
            }
        }
    }

    @Test
    fun `every ink is readable on its own soft wash`() {
        // Where `InlineProblem` puts it: danger text on a twelve-percent danger
        // ground is the narrowest pairing in the app, because the wash moves the
        // background *towards* the text.
        for ((theme, colors) in themes()) {
            for ((name, pair) in softGrounds(colors)) {
                val (ink, ground) = pair
                val ratio = contrast(ink, ground)
                assertTrue(
                    "$theme: ink on $name is ${"%.2f".format(ratio)}:1, under 4.5",
                    ratio >= 4.5f,
                )
            }
        }
    }

    @Test
    fun `body and muted text clear the bar too`() {
        for ((theme, colors) in themes()) {
            for ((name, ink) in listOf("text" to colors.text, "textMuted" to colors.textMuted)) {
                val ratio = contrast(ink, colors.bgApp)
                assertTrue(
                    "$theme: $name on bgApp is ${"%.2f".format(ratio)}:1, under 4.5",
                    ratio >= 4.5f,
                )
            }
        }
    }

    @Test
    fun `Midnight needs no re-stepping, so its inks are its base colours`() {
        // If this ever fails, the dark palette has grown a second name for a colour
        // it already had — two things to keep in step where one would do.
        assertTrue(MidnightColors.accentInk == MidnightColors.accent)
        assertTrue(MidnightColors.dangerInk == MidnightColors.danger)
        assertTrue(MidnightColors.warnInk == MidnightColors.warn)
        assertTrue(MidnightColors.successInk == MidnightColors.success)
        assertTrue(MidnightColors.accentGreenInk == MidnightColors.accentGreen)
        assertTrue(MidnightColors.accentCyanInk == MidnightColors.accentCyan)
    }

    @Test
    fun `Light does need it, and keeps the hue while doing it`() {
        // A re-stepping, not a swap. If somebody "fixes" a contrast failure by
        // reaching for a different colour, the app stops looking like itself in one
        // theme and this says so.
        assertTrue(LightColors.accentInk != LightColors.accent)
        for ((name, pair) in listOf(
            "accent" to Pair(LightColors.accent, LightColors.accentInk),
            "danger" to Pair(LightColors.danger, LightColors.dangerInk),
            "warn" to Pair(LightColors.warn, LightColors.warnInk),
            "success" to Pair(LightColors.success, LightColors.successInk),
            "accentGreen" to Pair(LightColors.accentGreen, LightColors.accentGreenInk),
            "accentCyan" to Pair(LightColors.accentCyan, LightColors.accentCyanInk),
        )) {
            val (base, ink) = pair
            val drift = hueDegreesBetween(base, ink)
            assertTrue(
                "$name's ink drifted ${"%.0f".format(drift)}° of hue from its base",
                drift <= 12f,
            )
            assertTrue(
                "$name's ink should be darker than its base, not lighter",
                luminance(ink) < luminance(base),
            )
        }
    }

    /**
     * Every place the app tells two flat fills apart without drawing a line between
     * them, as a pair of token names.
     */
    private val surfacePairs = listOf(
        "a card on the page" to Pair("bgSurface", "bgApp"),
        "the drawer behind the page" to Pair("bgBase", "bgApp"),
        "the active conversation row" to Pair("bgSurface2", "bgApp"),
        "a secondary button on a card" to Pair("bgSurface2", "bgSurface"),
        "a floating pill on the page" to Pair("bgElevated", "bgApp"),
        "an attachment tile on a card" to Pair("bgElevated", "bgSurface"),
        "a search field on the page" to Pair("bgInput", "bgApp"),
        "a text field on a card" to Pair("bgInput", "bgSurface"),
    )

    private fun surface(colors: AnodexColors, name: String): Color = when (name) {
        "bgBase" -> colors.bgBase
        "bgApp" -> colors.bgApp
        "bgSurface" -> colors.bgSurface
        "bgSurface2" -> colors.bgSurface2
        "bgElevated" -> colors.bgElevated
        "bgInput" -> colors.bgInput
        else -> error("unknown surface $name")
    }

    /**
     * A surface is only a surface if you can see where it starts.
     *
     * `bgApp`, `bgSurface` and `bgInput` were once the same value on the light
     * theme, byte for byte. This app separates surfaces by colour rather than by
     * shadow — that is written down in `Tokens.kt` as a deliberate choice — so three
     * tokens holding one value meant a filled card, a search field and the resting
     * shape of a loading list had no boundary at all in Light. Only the things that
     * happened to draw a border survived, which is why it went unnoticed: enough of
     * the app draws borders that it looked like a style rather than a fault.
     *
     * The floor is **Midnight's own weakest step**, measured rather than picked. The
     * dark theme is the one that has been looked at every day since the app existed,
     * so whatever separation it settles for is by definition enough — and holding
     * Light to it is `AGENTS.md`'s "validate it as carefully as dark" written as
     * something a machine can check.
     */
    @Test
    fun `every surface is distinguishable from the ones it sits on`() {
        val floor = surfacePairs.minOf { (_, pair) ->
            contrast(surface(MidnightColors, pair.first), surface(MidnightColors, pair.second))
        }

        for ((theme, colors) in themes()) {
            for ((what, pair) in surfacePairs) {
                val ratio = contrast(surface(colors, pair.first), surface(colors, pair.second))
                assertTrue(
                    "$theme: $what (${pair.first} on ${pair.second}) is " +
                        "${"%.3f".format(ratio)}:1, under Midnight's own weakest step of " +
                        "${"%.3f".format(floor)}:1",
                    ratio >= floor,
                )
            }
        }
    }

    @Test
    fun `the placeholder is a tint of the text, so it reads on either ground`() {
        for ((theme, colors) in themes()) {
            for (name in listOf("bgSurface", "bgApp")) {
                val ground = surface(colors, name)
                val filled = over(colors.placeholder, colors.placeholder.alpha, ground)
                val ratio = contrast(filled, ground)
                assertTrue(
                    "$theme: a resting bar on $name is ${"%.3f".format(ratio)}:1, invisible",
                    ratio >= 1.05f,
                )
            }
        }
    }

    /**
     * `textFaint` is below AA on purpose, and this is where that stops being an
     * accident waiting to be discovered.
     *
     * It measures 2.71:1 at worst on Midnight and 2.15:1 on Light, against the 4.5
     * `type.meta` and `type.badge` would need. That was weighed and kept: lifting it
     * collides with `textMuted`, so the whole three-step ramp has to move, and that
     * visibly changes a hundred-odd sites — a design call about hierarchy rather than
     * a defect. See `AGENTS.md`.
     *
     * A decision written only in prose is a decision that erodes. Twice while this
     * palette was being reworked, `textFaint` drifted a little further down without
     * anyone intending it — the deepest ground got deeper and the faintest text came
     * along for the ride. So the decision is pinned three ways: it stays the quiet
     * step, it may not sink past a floor, and Light may not drift further from
     * Midnight than it already has. Changing the ramp means changing this test on
     * purpose, which is exactly the moment somebody should have to think about it.
     */
    @Test
    fun `the faintest text is quiet on purpose, and stays where it was put`() {
        for ((theme, colors) in themes()) {
            val faint = worstOnAnyGround(colors, colors.textFaint)
            val muted = worstOnAnyGround(colors, colors.textMuted)

            assertTrue(
                "$theme: textFaint (${"%.2f".format(faint)}:1) is not quieter than " +
                    "textMuted (${"%.2f".format(muted)}:1) — the ramp has inverted",
                faint < muted,
            )
            assertTrue(
                "$theme: textFaint has sunk to ${"%.2f".format(faint)}:1. Below AA is a " +
                    "decision; below 2.0 is not one anybody made",
                faint >= 2.0f,
            )
        }

        // Light is already the worse of the two — about four fifths of Midnight's
        // ratio — and it is the theme where pale-on-cream is hardest to begin with.
        // It may not quietly get worse than that while a surface is being tuned.
        val darkFaint = worstOnAnyGround(MidnightColors, MidnightColors.textFaint)
        val lightFaint = worstOnAnyGround(LightColors, LightColors.textFaint)
        assertTrue(
            "Light's faint text has drifted to ${"%.2f".format(lightFaint)}:1 against " +
                "Midnight's ${"%.2f".format(darkFaint)}:1 — the gap between the themes is widening",
            lightFaint >= darkFaint * 0.75f,
        )
    }

    private fun worstOnAnyGround(colors: AnodexColors, ink: Color): Float =
        grounds(colors).values.minOf { contrast(ink, it) }

    private fun themes() = listOf("Midnight" to MidnightColors, "Light" to LightColors)

    // --- WCAG 2.1 relative luminance and contrast ------------------------------

    private fun channel(value: Float): Float =
        if (value <= 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)

    private fun luminance(color: Color): Float =
        0.2126f * channel(color.red) + 0.7152f * channel(color.green) + 0.0722f * channel(color.blue)

    private fun contrast(a: Color, b: Color): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    /** `foreground` at `alpha` composited over `background`, as the renderer would. */
    private fun over(foreground: Color, alpha: Float, background: Color): Color = Color(
        red = alpha * foreground.red + (1 - alpha) * background.red,
        green = alpha * foreground.green + (1 - alpha) * background.green,
        blue = alpha * foreground.blue + (1 - alpha) * background.blue,
    )

    /** How far apart two colours are on the wheel, 0 to 180. */
    private fun hueDegreesBetween(a: Color, b: Color): Float {
        val difference = kotlin.math.abs(hue(a) - hue(b))
        return min(difference, 360f - difference)
    }

    private fun hue(color: Color): Float {
        val r = color.red
        val g = color.green
        val b = color.blue
        val high = maxOf(r, g, b)
        val low = minOf(r, g, b)
        val span = high - low
        if (span == 0f) return 0f
        val raw = when (high) {
            r -> 60f * (((g - b) / span) % 6f)
            g -> 60f * (((b - r) / span) + 2f)
            else -> 60f * (((r - g) / span) + 4f)
        }
        return (raw + 360f) % 360f
    }
}
