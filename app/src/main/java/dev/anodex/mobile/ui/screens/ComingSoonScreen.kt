package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Spacing

/**
 * A destination that exists in the drawer before it exists in the app.
 *
 * Deliberately a real screen rather than a hidden row. The navigation is what is
 * being judged right now, and a drawer that quietly omits two of its five entries
 * cannot be judged — you would be looking at a different shape from the one that
 * ships.
 *
 * It says what the thing will do rather than "coming soon" alone, because that is
 * the part worth checking: if the description is wrong, the screen behind it would
 * have been wrong too, and this is a much cheaper place to find out.
 */
@Composable
fun ComingSoonScreen(
    title: String,
    icon: AnodexIcon,
    description: String,
    modifier: Modifier = Modifier,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgApp)
            .padding(Spacing.x6),
        verticalArrangement = Arrangement.spacedBy(Spacing.x4, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnodexIcon(icon, size = 32.dp, tint = colors.textFaint, contentDescription = null)
        Text(title, style = type.heading, color = colors.text)
        Text(
            text = description,
            style = type.body,
            color = colors.textMuted,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Not built yet.",
            style = type.meta,
            color = colors.textFaint,
        )
    }
}

@Preview(name = "Coming soon", showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun PreviewComingSoon() {
    AnodexTheme(darkTheme = true) {
        ComingSoonScreen(
            title = "Workspace",
            icon = AnodexIcon.FOLDER,
            description = "The files in the project your computer has open, to read from here " +
                "and to hand to a turn.",
        )
    }
}
