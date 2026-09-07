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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.chat.Personality
import dev.anodex.mobile.ui.components.SecondaryButton
import dev.anodex.mobile.ui.theme.AnodexColors
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch

/**
 * How Anodex answers, and what the app is.
 *
 * The personalities are the computer's own, read from it — including any the user
 * wrote themselves. They are a real setting rather than a cosmetic one — they change
 * the system prompt — so they belong in front of the user rather than buried behind
 * an "advanced" disclosure.
 *
 * Chosen on the phone, applied on the computer: like the active project, this is one
 * setting shared by both, not a phone-local preference.
 */
@Composable
fun SettingsScreen(
    installedVersion: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    personalities: List<Personality> = emptyList(),
    activePersonalityId: String? = null,
    busy: Boolean = false,
    onSelectPersonality: (String?) -> Unit = {},
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

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

            // Empty before the computer has answered, and after a connection that
            // dropped. Said plainly rather than shown as a screen with no options,
            // which reads as a broken setting instead of a pending one.
            if (personalities.isEmpty()) {
                Text(
                    text = "Waiting for your computer\u2026",
                    style = type.meta,
                    color = colors.textFaint,
                    modifier = Modifier.padding(horizontal = Spacing.x4, vertical = Spacing.x3),
                )
            }

            for (personality in personalities) {
                PersonalityRow(
                    personality = personality,
                    tint = tintOf(personality.tint, colors),
                    selected = personality.id == activePersonalityId,
                    enabled = !busy,
                    onClick = { onSelectPersonality(personality.id) },
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

/**
 * The desktop's tint names, resolved against this theme.
 *
 * A name travels rather than a colour, so a personality keeps its identity on both
 * screens without the phone inheriting a hue mixed for the desktop's ground — and so
 * a phone in light mode is not left holding a dark-mode colour.
 */
private fun tintOf(name: String, colors: AnodexColors): Color =
    when (name) {
        "violet" -> colors.accentViolet
        "green" -> colors.accentGreen
        "series-1" -> colors.series1
        "series-2" -> colors.series2
        "series-3" -> colors.series3
        "series-4" -> colors.series4
        else -> colors.accent
    }

@Composable
private fun PersonalityRow(
    personality: Personality,
    tint: Color,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.accentSoft else colors.bgApp)
            .heightIn(min = Touch.minTarget)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        // The tint each personality already carries on the desktop, so the choice is
        // recognisable before the words are read.
        Box(Modifier.size(8.dp).clip(CircleShape).background(tint))

        Column(Modifier.weight(1f)) {
            Text(personality.name, style = type.bodyEmphasis, color = colors.text)

            // A personality somebody wrote themselves need not have a one-liner.
            if (personality.role.isNotBlank()) {
                Text(personality.role, style = type.meta, color = colors.textMuted)
            }
        }

        if (selected) Text("✓", style = type.body, color = colors.accent)
    }
}

@Preview(name = "Settings", showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun PreviewSettings() {
    AnodexTheme(darkTheme = true) {
        SettingsScreen(
            installedVersion = "0.23.0",
            onClose = {},
            personalities = listOf(
                Personality("p1", "Vale", "Direct. Answer first, reasoning after.", "accent"),
                Personality("p2", "Wren", "Warm, and explains the reasoning.", "series-2"),
                Personality("p3", "Cass", "Terse. As few words as will do.", "violet"),
                Personality("p4", "Juno", "Encouraging without the sugar.", "green"),
                Personality("p5", "Rook", "Skeptical. Argues with the premise.", "series-3"),
            ),
            activePersonalityId = "p1",
        )
    }
}
