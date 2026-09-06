package dev.anodex.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.ui.theme.AnodexTheme

/**
 * The desktop's icon set, drawn here rather than redrawn.
 *
 * These are the exact path strings from `src/renderer/components/Icon.tsx`, and
 * that is the point: the two apps are one product, and an icon that means "agent
 * runs" on the computer has to mean it on the phone. Several of them are bespoke
 * — the mail glyph was cut to match the Anodex mark's facets, the bot's head is a
 * hexagon for the same reason — so substituting Material's would quietly throw
 * away the house style along with the recognisability.
 *
 * Copied by hand because there is no shared build between an Electron app and an
 * Android one. [AnodexIcons] is therefore the seam where the two can drift, and
 * `AnodexIconTest` pins the paths so a silent divergence shows up as a failure.
 */
@Composable
fun AnodexIcon(
    icon: AnodexIcon,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = AnodexTheme.colors.text,
    contentDescription: String? = null,
) {
    // Parsed once per glyph rather than per frame: the tab bar redraws on every
    // selection change and re-parsing four path strings each time is pure waste.
    val paths = remember(icon) { icon.strokes.map { PathParser().parsePathString(it).toPath() } }

    Canvas(
        modifier
            .size(size)
            .semantics { contentDescription?.let { this.contentDescription = it } }
    ) {
        // The glyphs are drawn on the same 24-unit grid the desktop uses, so the
        // stroke weight scales with the icon exactly as it does in the SVG.
        val factor = this.size.minDimension / VIEWPORT
        val stroke = Stroke(
            width = STROKE_WIDTH,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        scale(factor, pivot = Offset.Zero) {
            for (path in paths) drawPath(path, tint, style = stroke)
        }
    }
}

private const val VIEWPORT = 24f
private const val STROKE_WIDTH = 2f

/**
 * One glyph, as its stroked sub-paths.
 *
 * Split into separate strings exactly as the desktop splits them into separate
 * `<path>` elements, so a diff against `Icon.tsx` is line-for-line.
 */
enum class AnodexIcon(val strokes: List<String>) {
    /** Speech bubble with the Anodex corner cut. Chats. */
    CHAT(listOf("M3 5a2 2 0 0 1 2-2h11l5 5v7a2 2 0 0 1-2 2H7l-4 4V5z")),

    /** Hexagonal head, antenna, two eyes — the Anodex take on a robot. Agent runs. */
    BOT(
        listOf(
            "M12 2v3",
            "M8 5h8l4 7.5L16 20H8l-4-7.5L8 5z",
            "M9.5 11.5v2",
            "M14.5 11.5v2",
        )
    ),

    /** Envelope, cut to mirror the mark's facet rather than a plain rectangle. */
    MAIL(
        listOf(
            "M2 7a2 2 0 0 1 2-2h11.5L22 10.5v6.5a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7z",
            "m3 6.4 9 6.4 5.2-5.2",
        )
    ),

    /** A screen on a stand — the computer this phone is driving. */
    MONITOR(
        listOf(
            // The desktop draws this as <rect rx="2">. Written out as arcs because
            // a path parser has no rectangle primitive, and square corners here
            // read as a different icon entirely at 20dp.
            "M4 3h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
            "M8 21h8",
            "M12 17v4",
        )
    ),
}
