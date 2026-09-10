package dev.anodex.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch

/**
 * The app's two button weights.
 *
 * Not Material's buttons: those carry their own elevation, ripple tint and corner scale, and
 * bending them back to Anodex's flatter, squarer look is more code than drawing them. These are
 * built from tokens only, and both clear the 48dp touch floor — the desktop's 26px controls do not
 * port, so visual size and touch target are allowed to differ.
 */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AnodexTheme.colors
    Box(
        modifier = modifier
            .heightIn(min = Touch.minTarget)
            .clip(Radii.md)
            .background(colors.accent)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.x6),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = AnodexTheme.type.bodyEmphasis, color = colors.textOnAccent)
    }
}

@Composable
fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AnodexTheme.colors
    Box(
        modifier = modifier
            .heightIn(min = Touch.minTarget)
            .clip(Radii.md)
            .background(colors.bgSurface2)
            .border(1.dp, colors.border, Radii.md)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.x6),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = AnodexTheme.type.bodyEmphasis, color = colors.text)
    }
}

/**
 * For the one action that cannot be taken back.
 *
 * Outlined in the danger colour rather than filled with it. A solid red block reads
 * as the primary thing to do on the screen, which is the opposite of true here — and
 * unpairing is a button you should have to mean.
 */
@Composable
fun DangerButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AnodexTheme.colors
    Box(
        modifier = modifier
            .heightIn(min = Touch.minTarget)
            .clip(Radii.md)
            .background(colors.dangerSoft)
            .border(1.dp, colors.danger, Radii.md)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.x6),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = AnodexTheme.type.bodyEmphasis, color = colors.dangerInk)
    }
}
