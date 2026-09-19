package dev.anodex.mobile.voice

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
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Brand
import dev.anodex.mobile.ui.theme.Touch

/**
 * The composer's key, when there is nothing in the box to send.
 *
 * Deliberately the same circle, in the same place, on the same gradient as
 * `SendButton` — this is one control that changes what it does, not two controls
 * taking turns. The first keystroke turns it into Send and the last backspace turns
 * it back, and through all of that it never moves under a thumb already on its way
 * to it. That is the reason Send is round and always present, and the reason this
 * borrows the shape rather than inventing one.
 *
 * It is not drawn when the computer has not offered voice, which leaves Send exactly
 * where it has always been.
 */
@Composable
fun SpeakButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = AnodexTheme.colors

    Box(
        modifier = modifier
            .size(Touch.minTarget)
            .clip(CircleShape)
            .background(Brand.gradient(colors))
            .background(Brand.sheen)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Speak to Anodex" },
        contentAlignment = Alignment.Center,
    ) {
        // The pulse rather than a microphone: the mic beside it already means
        // dictation — speech that becomes text to read before it is sent — and two
        // microphones side by side would be two names for one thing.
        AnodexIcon(
            icon = AnodexIcon.ACTIVITY,
            size = 20.dp,
            tint = colors.textOnAccent,
            contentDescription = null,
        )
    }
}
