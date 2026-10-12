package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.profile.UserProfile
import dev.anodex.mobile.profile.firstNameOf
import dev.anodex.mobile.ui.components.PrimaryButton
import dev.anodex.mobile.ui.components.UserAvatar
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing

/**
 * The first thing after pairing: who this phone now belongs to.
 *
 * The computer asked for a name and a picture in its welcome so that both screens
 * would have something to say; this is the phone saying it, once. It reads the
 * profile the computer keeps — the phone never asks for a name of its own — and
 * falls back to a plain "You're paired" while that read is on its way or when no
 * name was given.
 */
@Composable
fun HelloScreen(user: UserProfile?, hostName: String, onStart: () -> Unit) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    val first = firstNameOf(user?.displayName)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05060A))
            .background(
                Brush.verticalGradient(
                    0.55f to Color.Transparent,
                    1f to Color(0xFF7C5CFF).copy(alpha = 0.22f),
                ),
            )
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp).padding(horizontal = Spacing.x6),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.x4),
        ) {
            Row(
                modifier = Modifier
                    .clip(Radii.pill)
                    .background(colors.successInk.copy(alpha = 0.12f))
                    .padding(horizontal = Spacing.x3, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(colors.successInk))
                Text("Paired with $hostName", style = type.meta, color = colors.successInk)
            }

            Box(contentAlignment = Alignment.Center) {
                // The glow the computer's welcome draws around the same picture.
                Box(
                    Modifier
                        .size(104.dp)
                        .blur(18.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.sweepGradient(
                                listOf(Color(0xFF7C5CFF), Color(0xFF4F8CFF), Color(0xFF38BDF8), Color(0xFF7C5CFF)),
                            ),
                        ),
                )
                UserAvatar(user?.avatarBase64, user?.displayName, user?.avatarLook ?: 0, size = 96.dp)
            }

            Text(
                text = if (first != null) "Hi, $first." else "You're paired.",
                style = type.display,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Anything you ask here runs on $hostName, with your models and your files.",
                style = type.body,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
            )
            PrimaryButton(label = "Start a chat", onClick = onStart, modifier = Modifier.fillMaxWidth())
        }
    }
}
