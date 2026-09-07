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
@Immutable
data class AnodexTypography(
    val body: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = TextSize.base,
        lineHeight = 22.sp,
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
    val chatBody: TextStyle = body.copy(fontSize = 17.sp, lineHeight = 26.sp),
    val chatBodyEmphasis: TextStyle = chatBody.copy(fontWeight = FontWeight.Medium),

    /** A heading inside a reply, kept a clear step above `chatBody`. */
    val chatHeading: TextStyle = chatBody.copy(
        fontSize = 20.sp,
        lineHeight = 28.sp,
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
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    val label: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = TextSize.sm,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
    ),
    val meta: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = TextSize.xs,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
    ),
    val badge: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = TextSize.xxs,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
    ),
    val heading: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = TextSize.lg,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    val title: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = TextSize.xl,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    val display: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = TextSize.xxl,
        lineHeight = 36.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    /** Code, tool output, file paths. Desktop `--font-mono`. */
    val mono: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = TextSize.sm,
        lineHeight = 20.sp,
    ),
)
