package dev.anodex.mobile.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Anodex's type scale, **re-stepped for a phone** rather than ported literally.
 *
 * This is the one place the desktop tokens are deliberately not copied, and the reasoning matters
 * enough to write down.
 *
 * The desktop scale runs 10 / 11 / 12 / 13 / 14 / 16 / 20 / 26 px, with 13px as body. That is
 * right for a dense workspace viewed at arm's length on a monitor, and it is the visual density
 * that makes Anodex look like a tool rather than a toy. Reproduced at 13sp on a phone held at
 * 30cm, the same numbers land under every readability floor there is — body text on Android is
 * conventionally 16sp, and Anodex's own restraint is not a licence to ship text people squint at.
 *
 * So the *roles and their relationships* are preserved and the steps are recomputed for the
 * medium — the same move `themes/light.css` makes for colour, where the light series values are
 * their own computation rather than the dark ones lightened. It is a re-stepping, not a scaling.
 * Body sits at 15sp: denser than Material's 16sp default, which keeps the family resemblance,
 * without crossing into unreadable.
 *
 * Do not "fix" these back to the desktop numbers to match a screenshot.
 */
object TextSize {
    /** Desktop `--text-2xs` (10px). Badges and counters only; the practical floor on Android. */
    val xxs: TextUnit = 11.sp

    /** Desktop `--text-xs` (11px). Timestamps, token counts, the quietest metadata. */
    val xs: TextUnit = 12.sp

    /** Desktop `--text-sm` (12px). Secondary labels, card subtitles. */
    val sm: TextUnit = 13.sp

    /** Desktop `--text-base` (13px). Body — chat prose, the default for everything unmarked. */
    val base: TextUnit = 15.sp

    /** Desktop `--text-md` (14px). Emphasised body, primary list rows. */
    val md: TextUnit = 16.sp

    /** Desktop `--text-lg` (16px). Section and screen headings. */
    val lg: TextUnit = 18.sp

    /** Desktop `--text-xl` (20px). Screen titles. */
    val xl: TextUnit = 22.sp

    /** Desktop `--text-2xl` (26px). The single largest thing on a screen; used rarely. */
    val xxl: TextUnit = 30.sp
}

/**
 * Named styles, so screens ask for a role rather than a size.
 *
 * Both families are the platform's own: Anodex uses the host OS's UI font by design (the desktop
 * stack starts `-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto`), which on Android is
 * Roboto. Nothing is bundled, and the app inherits the user's font-size accessibility setting for
 * free because every size here is in sp.
 */
/**
 * How much bigger or smaller than the designed scale, and in what face.
 *
 * The ladder in [AnodexTypography] is designed, not arbitrary — every step has a
 * comment explaining why it sits where it does, and `chatBody` is deliberately off
 * the ladder entirely. So a size preference multiplies the whole thing rather than
 * setting sizes: the relationships between body, label and meta survive, and a reader
 * who wants everything larger gets everything larger rather than a flattened scale.
 *
 * Sizes stay in `sp`, so this multiplies whatever the phone's own accessibility
 * text-size setting already did. Somebody who has turned Android up to maximum and
 * then picks Large here means it.
 */
@Immutable
enum class FontScale(val label: String, val description: String, val factor: Float) {
    SMALL("Small", "Tighter, fits more on screen", 0.88f),
    MEDIUM("Medium", "The designed size", 1f),
    LARGE("Large", "Easier to read at arm's length", 1.15f),
}

/**
 * Which face the interface is set in.
 *
 * Mirrors the desktop's three, and means the same thing by them. `SYSTEM` is the
 * phone's own — Roboto on most, whatever the manufacturer shipped on others — which
 * is why it is the default: it is the face every other app on the device uses.
 *
 * `MONO` is a deliberate oddity rather than an oversight. Some people read a dense
 * interface better in a fixed pitch, and the desktop offers it, so this does too.
 * Chat code and tool output stay monospaced under every choice; this is about
 * everything else.
 */
@Immutable
enum class UiFont(val label: String, val description: String) {
    SYSTEM("System", "Whatever this phone uses elsewhere"),
    SANS("Sans-serif", "A plain proportional face"),
    MONO("Monospace", "Fixed pitch, for the whole interface"),
    ;

    val family: FontFamily
        get() = when (this) {
            SYSTEM -> FontFamily.Default
            SANS -> FontFamily.SansSerif
            MONO -> FontFamily.Monospace
        }
}

@Immutable
data class AnodexTypography(
    /** Multiplies every size below. See [FontScale]. */
    val scale: Float = 1f,
    /** The face for everything that is not deliberately monospaced. */
    val family: FontFamily = FontFamily.Default,
    val body: TextStyle = TextStyle(
        fontFamily = family,
        fontSize = TextSize.base * scale,
        lineHeight = 22.sp * scale,
        fontWeight = FontWeight.Normal,
    ),
    val bodyEmphasis: TextStyle = body.copy(fontWeight = FontWeight.Medium),

    /**
     * Chat prose, and only chat prose.
     *
     * Deliberately off the ladder above. Everything else in the app is *scanned* — list
     * rows, labels, counts, timestamps — and a scanned surface wants density, because
     * the win is fitting more of it on one screen. A reply is *read*, often several
     * paragraphs of it, held at arm's length, and at 15sp that starts to cost the
     * reader. So the one surface people read gets its own size, and the rest stays
     * where it is: enlarging every label to fix chat would only push the thing people
     * opened the app for further down the screen.
     *
     * The generous line height is the other half of it. Long-form text on a narrow
     * column needs the leading more than it needs the point size.
     */
    val chatBody: TextStyle = body.copy(fontSize = 17.sp * scale, lineHeight = 26.sp * scale),
    val chatBodyEmphasis: TextStyle = chatBody.copy(fontWeight = FontWeight.Medium),

    /** A heading inside a reply, kept a clear step above `chatBody`. */
    val chatHeading: TextStyle = chatBody.copy(
        fontSize = 20.sp * scale,
        lineHeight = 28.sp * scale,
        fontWeight = FontWeight.SemiBold,
    ),

    /**
     * Code inside a reply.
     *
     * Tracks `chatBody` rather than the compact `mono` used for tool output and file
     * paths. A snippet in the middle of prose that is four points smaller than the
     * prose reads as a footnote, and it is usually the part being asked about.
     */
    val chatMono: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 15.sp * scale,
        lineHeight = 22.sp * scale,
    ),
    val label: TextStyle = TextStyle(
        fontFamily = family,
        fontSize = TextSize.sm * scale,
        lineHeight = 18.sp * scale,
        fontWeight = FontWeight.Medium,
    ),
    val meta: TextStyle = TextStyle(
        fontFamily = family,
        fontSize = TextSize.xs * scale,
        lineHeight = 16.sp * scale,
        fontWeight = FontWeight.Normal,
    ),
    val badge: TextStyle = TextStyle(
        fontFamily = family,
        fontSize = TextSize.xxs * scale,
        lineHeight = 14.sp * scale,
        fontWeight = FontWeight.Medium,
    ),
    val heading: TextStyle = TextStyle(
        fontFamily = family,
        fontSize = TextSize.lg * scale,
        lineHeight = 24.sp * scale,
        fontWeight = FontWeight.SemiBold,
    ),
    val title: TextStyle = TextStyle(
        fontFamily = family,
        fontSize = TextSize.xl * scale,
        lineHeight = 28.sp * scale,
        fontWeight = FontWeight.SemiBold,
    ),
    val display: TextStyle = TextStyle(
        fontFamily = family,
        fontSize = TextSize.xxl * scale,
        lineHeight = 36.sp * scale,
        fontWeight = FontWeight.SemiBold,
    ),
    /** Code, tool output, file paths. Desktop `--font-mono`. */
    val mono: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = TextSize.sm * scale,
        lineHeight = 20.sp * scale,
    ),
)
