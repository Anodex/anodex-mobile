package dev.anodex.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Brand
import dev.anodex.mobile.ui.theme.Touch

/**
 * The one round control in the app.
 *
 * A circle rather than the app's usual 6dp radius, and deliberately: it is the
 * only control that commits work to another machine, and it should not look
 * like the buttons that merely navigate.
 *
 * Shared rather than copied. It began in the chat composer and the mail
 * composer drew a plain grey glyph in its place, so the two screens that both
 * send something looked like different apps -- which is the whole argument for
 * a control this distinctive existing at all. One of them is now the other.
 */
@Composable
fun SendButton(
    modifier: Modifier = Modifier,
    /** False leaves it present and plainly inert, rather than removing it. */
    enabled: Boolean = true,
    /**
     * Draw it as Stop, on the danger wash.
     *
     * For a send that can still be called back -- a chat turn mid-stream. Mail
     * has no equivalent: once the computer has handed a message to a provider
     * there is nothing to press.
     */
    stop: Boolean = false,
    /** What a screen reader says, and what the action is called. */
    label: String = if (stop) "Stop" else "Send",
    // Last, so both call sites can hand it a trailing lambda. The Compose
    // convention puts `onClick` first; the two screens that use this read
    // better with the states named and the action on the end.
    onClick: () -> Unit,
) {
    val colors = AnodexTheme.colors

    val foreground = when {
        stop -> colors.dangerInk
        enabled -> colors.textOnAccent
        else -> colors.textFaint
    }

    // The most-pressed control in the app, so it is the one that most has to
    // look like this app: ready to send, it wears the mark. Stopping it is
    // danger, and an empty field leaves it present but plainly inert -- a
    // control that vanishes when there is nothing to send takes the layout
    // with it.
    val paint = Modifier.run {
        when {
            stop -> background(colors.dangerSoft)
            enabled -> background(Brand.gradient(colors)).background(Brand.sheen)
            else -> background(colors.bgSurface2)
        }
    }

    Box(
        modifier = modifier
            .size(Touch.minTarget)
            .clip(CircleShape)
            .then(paint)
            .clickable(enabled = enabled || stop, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        // The desktop's own two glyphs, not lookalikes drawn here. Send is a
        // paper plane in `Icon.tsx`; this drew an arrow instead, with a comment
        // claiming it was the desktop's -- which it never was, and nothing
        // catches a wrong comment next to a wrong drawing.
        //
        // The filled plane, because this one sits on the gradient: the stroked
        // plane is a hairline at this size and disappears into a saturated
        // fill. The desktop made the same swap, and for the same reason.
        AnodexIcon(
            icon = if (stop) AnodexIcon.STOP else AnodexIcon.SEND_FILL,
            size = if (stop) 15.dp else 18.dp,
            tint = foreground,
            contentDescription = null,
        )
    }
}
