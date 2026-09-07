package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.chat.Personality
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.theme.AnodexColors
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch

/**
 * How Anodex answers, and what the app is.
 *
 * Grouped into cards rather than run as one flat list. That is how the platform's own
 * settings screens are built, and how [HostScreen] here already reads — the grouping
 * is what says "these belong together and that one does not", without needing a
 * heading over every second row.
 *
 * The personalities are the computer's own, read from it, including any the user
 * wrote themselves. They are a real setting rather than a cosmetic one — they change
 * the system prompt — so they sit at the top rather than behind an "advanced"
 * disclosure.
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
    /** The machine this phone is driving. Null when it has never been paired. */
    hostName: String? = null,
    hostStatus: String = "Not connected",
    onOpenHost: (() -> Unit)? = null,
    /** The build the computer expects, when this phone is behind it. Null if not. */
    newerVersion: String? = null,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(modifier.fillMaxSize().background(colors.bgApp)) {
        Header(onClose)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.x4),
        ) {
            SectionLabel("How Anodex answers")

            Group {
                // Empty before the computer has answered, and after a connection that
                // dropped. Said plainly rather than shown as a card with no rows in
                // it, which reads as a broken setting instead of a pending one.
                if (personalities.isEmpty()) {
                    Text(
                        text = "Waiting for your computer…",
                        style = type.body,
                        color = colors.textFaint,
                        modifier = Modifier.padding(Spacing.x4),
                    )
                }

                personalities.forEachIndexed { index, personality ->
                    if (index > 0) RowDivider()
                    PersonalityRow(
                        personality = personality,
                        tint = tintOf(personality.tint, colors),
                        selected = personality.id == activePersonalityId,
                        enabled = !busy,
                        onClick = { onSelectPersonality(personality.id) },
                    )
                }
            }

            Footnote("Changing this changes how the computer replies, for both of you.")

            if (onOpenHost != null) {
                SectionLabel("Your computer")

                Group {
                    SettingsRow(
                        icon = AnodexIcon.MONITOR,
                        label = hostName ?: "Not paired",
                        // The value under the label: the platform's way of stating a
                        // setting's current setting without making you open it first.
                        value = hostStatus,
                        onClick = onOpenHost,
                    )
                }
            }

            SectionLabel("About")

            Group {
                SettingsRow(
                    icon = AnodexIcon.SETTINGS,
                    label = "Version",
                    trailing = installedVersion,
                )

                if (newerVersion != null) {
                    RowDivider()
                    // Stated, not acted on. The app cannot install anything yet and
                    // should not pretend it can — a button that turns out to mean "go
                    // and find a file" is worse than a sentence that says so.
                    Column(Modifier.padding(Spacing.x4)) {
                        Text(
                            text = "$newerVersion is available",
                            style = type.bodyEmphasis,
                            color = colors.accent,
                        )
                        Text(
                            text = "Your computer ships with it. Install the newer one " +
                                "over the top — your pairing is kept.",
                            style = type.meta,
                            color = colors.textMuted,
                        )
                    }
                }
            }

            Box(Modifier.heightIn(min = Spacing.x8))
        }
    }
}

/**
 * Back on the left, title in the middle.
 *
 * A chevron rather than a boxed "Back" button: the box drew as much weight as the
 * settings under it, and everything else on the phone leaves going back to a plain
 * affordance in the corner.
 */
@Composable
private fun Header(onClose: () -> Unit) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Box(Modifier.fillMaxWidth().padding(vertical = Spacing.x2)) {
        Text(
            text = "Settings",
            style = type.bodyEmphasis,
            color = colors.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().align(Alignment.Center),
        )

        Box(
            modifier = Modifier
                .size(Touch.minTarget)
                .clip(CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            AnodexIcon(AnodexIcon.CHEVRON_LEFT, tint = colors.text, contentDescription = "Back")
        }
    }
}

/** The quiet all-caps label a group sits under. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = AnodexTheme.type.badge,
        color = AnodexTheme.colors.textFaint,
        modifier = Modifier.padding(
            start = Spacing.x2,
            top = Spacing.x5,
            bottom = Spacing.x2,
        ),
    )
}

/** A rounded card of rows, with the app's own ground showing between groups. */
@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.xl)
            .background(AnodexTheme.colors.bgSurface),
        content = content,
    )
}

/** Inset, so it reads as separating two rows rather than cutting the card in half. */
@Composable
private fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.x4)
            .heightIn(min = 1.dp, max = 1.dp)
            .background(AnodexTheme.colors.border)
    )
}

@Composable
private fun Footnote(text: String) {
    Text(
        text = text,
        style = AnodexTheme.type.meta,
        color = AnodexTheme.colors.textFaint,
        modifier = Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x2),
    )
}

/**
 * An icon, a label, and either a value to read or somewhere to go.
 *
 * A row with an `onClick` gets the chevron; one without does not. That is the whole
 * distinction between the two kinds of row, and it has to be legible before the row
 * is tapped rather than after.
 */
@Composable
private fun SettingsRow(
    icon: AnodexIcon,
    label: String,
    value: String? = null,
    trailing: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = Touch.minTarget)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        AnodexIcon(icon, tint = colors.textMuted)

        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = type.body,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (value != null) Text(value, style = type.meta, color = colors.textFaint)
        }

        if (trailing != null) Text(trailing, style = type.meta, color = colors.textFaint)

        if (onClick != null) {
            AnodexIcon(AnodexIcon.CHEVRON_RIGHT, size = 16.dp, tint = colors.textFaint)
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
            .background(if (selected) colors.accentSoft else Color.Transparent)
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
            hostName = "Gort",
            hostStatus = "Connected",
            onOpenHost = {},
            newerVersion = "0.24.0",
        )
    }
}
