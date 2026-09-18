package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.ui.theme.AnodexTheme

/**
 * Who a message is from, as a circle.
 *
 * Every mail client draws one and this one did not, which is most of why a list
 * of messages here read as a list of text and a list of messages elsewhere reads
 * as a list of people. It is also the thing the eye lands on first when scanning
 * an inbox: the name is read second, and only if the circle did not already
 * answer the question.
 *
 * A letter on a colour rather than a fetched picture. Anodex will not reach out
 * to a sender's server to decorate a row -- that is the same request a tracking
 * pixel makes, for less reason.
 *
 * The colour is derived from the address, so a sender keeps the same circle for
 * ever without anything being stored. Derived from the *address* rather than the
 * display name, because a newsletter changes what it calls itself far more often
 * than it changes where it sends from.
 */
@Composable
fun SenderAvatar(from: String, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val colors = AnodexTheme.colors
    val palette = listOf(
        colors.accent,
        colors.accentViolet,
        colors.accentCyan,
        colors.series1,
        colors.series2,
        colors.series3,
        colors.series4,
    )

    val key = avatarKey(from)
    val tint = palette[(key.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }) % palette.size]

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            // The letter sits on the sender's colour at a quarter strength rather
            // than on the colour itself: a saturated 40dp disc in a list of ten is
            // a row of traffic lights, and the name beside it stops being read.
            .background(tint.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = avatarLetter(from),
            style = AnodexTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
            color = tint,
        )
    }
}

/**
 * The letter in the circle.
 *
 * The display name's first letter when there is one, because that is what the
 * row says underneath it. An address otherwise -- and never the `<` of a
 * malformed `From`, which is what taking `first()` off the raw string gives.
 */
internal fun avatarLetter(from: String): String {
    val name = senderName(from).trim()
    val letter = name.firstOrNull { it.isLetterOrDigit() }
    return letter?.uppercase() ?: "?"
}

/**
 * What the colour is derived from: the address, lowercased.
 *
 * A sender that changes its display name -- "MSN Daily" to "MSN Daily Digest" --
 * keeps its circle. One that changes its address does not, which is correct: it
 * is a different sender until proven otherwise.
 */
internal fun avatarKey(from: String): String {
    val inAngles = from.substringAfter('<', "").substringBefore('>', "")
    return (if (inAngles.isNotBlank()) inAngles else from).trim().lowercase()
}
