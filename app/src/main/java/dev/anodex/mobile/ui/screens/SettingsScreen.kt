package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.ui.components.SecondaryButton
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch

/**
 * How Anodex answers, and what the app is.
 *
 * The personalities are the desktop's own five, by name and in their own words.
 * They are a real setting rather than a cosmetic one — they change the system
 * prompt — so they belong in front of the user rather than buried behind an
 * "advanced" disclosure.
 *
 * Chosen on the phone, applied on the computer: like the active project, this is one
 * setting shared by both, not a phone-local preference.
 */
@Composable
fun SettingsScreen(
    installedVersion: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    selectedPersonality: String = "Vale",
    onSelectPersonality: (String) -> Unit = {},
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    // Local until the desktop exposes a channel for it. Deliberately visible now:
    // the shape of the screen is what is being judged, and a selector that does not
    // move cannot be judged at all.
    var chosen by remember { mutableStateOf(selectedPersonality) }

    val personalities = listOf(
        Personality("Vale", "Direct. Answer first, reasoning after.", colors.accent),
        Personality("Wren", "Warm, and explains the reasoning.", colors.accentCyan),
        Personality("Cass", "Terse. As few words as will do.", colors.accentViolet),
        Personality("Juno", "Encouraging without the sugar.", colors.success),
        Personality("Rook", "Skeptical. Argues with the premise.", colors.warn),
    )

    Column(modifier.fillMaxSize().background(colors.bgApp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.x3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
        ) {
            SecondaryButton(label = "Back", onClick = onClose)
            Text("Settings", style = type.bodyEmphasis, color = colors.text)
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                text = "HOW ANODEX ANSWERS",
                style = type.badge,
                color = colors.textFaint,
                modifier = Modifier.padding(horizontal = Spacing.x4, vertical = Spacing.x2),
            )

            for (personality in personalities) {
                PersonalityRow(
                    personality = personality,
                    selected = personality.name == chosen,
                    onClick = {
                        chosen = personality.name
                        onSelectPersonality(personality.name)
                    },
                )
            }

            Text(
                text = "Changing this changes how the computer replies, for both of you.",
                style = type.meta,
                color = colors.textFaint,
                modifier = Modifier.padding(horizontal = Spacing.x4, vertical = Spacing.x3),
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.x4, vertical = Spacing.x2)
                    .heightIn(min = 1.dp, max = 1.dp)
                    .background(colors.border)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(Spacing.x4),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Version", style = type.body, color = colors.text)
                Text(installedVersion, style = type.meta, color = colors.textFaint)
            }
        }
    }
}

private data class Personality(val name: String, val role: String, val tint: Color)

@Composable
private fun PersonalityRow(
    personality: Personality,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.accentSoft else colors.bgApp)
            .heightIn(min = Touch.minTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        // The tint each personality already carries on the desktop, so the choice is
        // recognisable before the words are read.
        Box(Modifier.size(8.dp).clip(CircleShape).background(personality.tint))

        Column(Modifier.weight(1f)) {
            Text(personality.name, style = type.bodyEmphasis, color = colors.text)
            Text(personality.role, style = type.meta, color = colors.textMuted)
        }

        if (selected) Text("✓", style = type.body, color = colors.accent)
    }
}

@Preview(name = "Settings", showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun PreviewSettings() {
    AnodexTheme(darkTheme = true) {
        SettingsScreen(installedVersion = "0.20.0", onClose = {})
    }
}
