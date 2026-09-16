package dev.anodex.mobile.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The mark on a filled control, as the phone draws it.
 *
 * The desktop keeps this in `BrandFill.module.css` and every button that carries a
 * screen's answer composes it. There is no shared stylesheet between an Electron app
 * and an Android one, so this file is the seam — the same job as [AnodexIcons] does
 * for the glyphs, and it can drift the same way.
 *
 * It belongs on the one affirmative control of a surface — the primary button, the
 * send key — and nowhere it would compete with that one. The switches and the rest
 * of the accent surfaces stay as they are.
 */
object Brand {

    /**
     * Violet into blue on the diagonal, drawn the way the app icon is drawn.
     *
     * The desktop writes `linear-gradient(135deg, violet, accent 72%)`. CSS's 135deg
     * runs top-left to bottom-right, which is what Compose's default linear brush
     * does when it is left to span the shape it is painted into — so the two agree
     * without either having to name an angle.
     */
    fun gradient(colors: AnodexColors): Brush =
        Brush.linearGradient(0f to colors.accentViolet, 0.72f to colors.accent)

    /**
     * The modelling over that gradient: a lift along the top edge and a bevel at the
     * bottom, so the face reads as a solid lit from above rather than as colour laid
     * on a rectangle.
     *
     * The desktop gets this from two inset shadows. Compose has no inset shadow, so
     * it is one overlay brush instead — the same two effects, drawn rather than cast.
     * Stops are tight at each end because the lift is a line, not a fade: spread over
     * the whole face it turns the button into a wash and loses the edge entirely.
     */
    val sheen: Brush = Brush.verticalGradient(
        0.00f to Color.White.copy(alpha = 0.20f),
        0.06f to Color.Transparent,
        0.80f to Color.Transparent,
        1.00f to Color.Black.copy(alpha = 0.18f),
    )
}
