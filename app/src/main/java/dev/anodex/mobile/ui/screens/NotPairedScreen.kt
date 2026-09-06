package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.ui.components.AnodexMark
import dev.anodex.mobile.ui.components.PrimaryButton
import dev.anodex.mobile.ui.components.SecondaryButton
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing

/**
 * The first screen: nothing is paired yet.
 *
 * It states the bargain plainly rather than burying it, because the bargain *is* the product. The
 * phone is a window onto the user's computer; it holds no models, no keys and no history, and if
 * the computer is unreachable the app does nothing. Someone who understands that on day one will
 * not read a later offline screen as the app being broken.
 *
 * Scanning is not built yet — the pairing QR needs the desktop bridge to exist before there is
 * anything to scan. Saying so beats a button that does nothing.
 */
@Composable
fun NotPairedScreen(
    onScan: (() -> Unit)?,
    onPreviewDesign: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgApp)
            .safeDrawingPadding()
            .padding(horizontal = Spacing.x6),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnodexMark(size = 72.dp)

        Text(
            text = "Pair with your computer",
            style = type.title,
            color = colors.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.x6),
        )

        Text(
            text = "Open Anodex on your PC, turn on Remote in Settings, and scan the code it shows.",
            style = type.body,
            color = colors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.x3),
        )

        Text(
            text = "Your models, projects, keys and history stay on that machine. This phone only " +
                "shows you what it is doing, and asks when it needs an answer.",
            style = type.meta,
            color = colors.textFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.x5),
        )

        if (onScan != null) {
            PrimaryButton(
                label = "Scan the code",
                onClick = onScan,
                modifier = Modifier.padding(top = Spacing.x8),
            )
        } else {
            Text(
                text = "Scanning isn't built yet — the desktop side has to exist before there is a " +
                    "code to scan.",
                style = type.meta,
                color = colors.warn,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = Spacing.x8)
                    .fillMaxWidth()
                    .clip(Radii.lg)
                    .background(colors.warnSoft)
                    .padding(Spacing.x4),
            )
        }

        SecondaryButton(
            label = "Preview design states",
            onClick = onPreviewDesign,
            modifier = Modifier.padding(top = Spacing.x4),
        )
    }
}

@Preview(name = "Not paired - dark", showBackground = true, heightDp = 760)
@Composable
private fun PreviewNotPairedDark() {
    AnodexTheme(darkTheme = true) {
        NotPairedScreen(onScan = null, onPreviewDesign = {})
    }
}

@Preview(name = "Not paired - light", showBackground = true, heightDp = 760)
@Composable
private fun PreviewNotPairedLight() {
    AnodexTheme(darkTheme = false) {
        NotPairedScreen(onScan = null, onPreviewDesign = {})
    }
}
