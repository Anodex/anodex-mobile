package dev.anodex.mobile.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anodex.mobile.R
import dev.anodex.mobile.ui.theme.AnodexColors
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii

/**
 * A personality's face: their picture, or a tinted monogram of their name.
 *
 * The same three-step fallback the desktop uses, and for the same reasons — a
 * personality that loses its picture should lose the picture, not the identity.
 *
 * 1. The shipped art for a built-in, keyed off its id.
 * 2. Nothing yet for a user's own picture: the desktop stores that as a *path* on
 *    its own disk, which means nothing here, and shipping the bytes over the socket
 *    on every personality list would put a megabyte on a call made at every connect.
 *    A lazy per-personality fetch is the right shape for that, and it is not built.
 * 3. A tinted monogram, which is also what the desktop falls back to when the file
 *    behind a path has moved.
 */
@Composable
fun PersonalityAvatar(
    /** The desktop's stable id — `builtin:direct` and friends. */
    id: String?,
    name: String,
    /** The desktop's tint name, used only when there is no picture to show. */
    tint: String,
    size: Dp = 22.dp,
    modifier: Modifier = Modifier,
) {
    val colors = AnodexTheme.colors
    val art = builtInArt(id)

    Box(
        modifier = modifier
            .size(size)
            // Slightly rounded rather than circular, matching the desktop: these are
            // square portraits, and a circle crops the corners of art that was drawn
            // to fill the frame.
            .clip(Radii.md)
            // The shipped art carries its own ground, so a tint behind it would only
            // show at the corners where the two roundings disagree.
            .background(if (art == null) monogramTint(tint, colors) else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(
                painter = painterResource(art),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            Text(
                text = initialsOf(name),
                color = colors.textOnAccent,
                // Scaled from the frame rather than fixed, so one avatar component
                // serves the byline, the settings row and anywhere else later.
                fontSize = (size.value * 0.38f).coerceAtLeast(8f).sp,
            )
        }
    }
}

/**
 * The art shipped for each built-in, by id.
 *
 * Keyed off the identity rather than anything stored, exactly as the desktop does:
 * a built-in cannot have its face replaced, and a *copy* of one is a user
 * personality that falls back to a monogram like any other.
 */
@DrawableRes
private fun builtInArt(id: String?): Int? = when (id) {
    "builtin:anodex" -> R.drawable.personality_anodex
    "builtin:direct" -> R.drawable.personality_vale
    "builtin:friendly" -> R.drawable.personality_wren
    "builtin:terse" -> R.drawable.personality_cass
    "builtin:encouraging" -> R.drawable.personality_juno
    "builtin:skeptical" -> R.drawable.personality_rook
    else -> null
}

/**
 * Word characters only: "Rook (mine)" must read RM, not "R(".
 *
 * Ported from the desktop's `personalityInitials` so the same personality is
 * abbreviated the same way on both screens.
 */
internal fun initialsOf(name: String): String {
    val parts = name.trim().ifEmpty { "Untitled" }
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotEmpty() }

    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}

/** The desktop's tint names, resolved against this theme. */
internal fun monogramTint(name: String, colors: AnodexColors): Color = when (name) {
    "violet" -> colors.accentViolet
    "green" -> colors.accentGreen
    "series-1" -> colors.series1
    "series-2" -> colors.series2
    "series-3" -> colors.series3
    "series-4" -> colors.series4
    else -> colors.accent
}
