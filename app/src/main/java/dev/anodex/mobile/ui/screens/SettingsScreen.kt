package dev.anodex.mobile.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.chat.LocalModel
import dev.anodex.mobile.chat.Personality
import dev.anodex.mobile.chat.detailLabel
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.theme.AnodexColors
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch

/**
 * Settings, divided the way the desktop divides them.
 *
 * An index of sections rather than one long scroll. The desktop already made this
 * split and people move between the two, so a setting filed under "AI & Models" on
 * the computer should not be three rows below the version number here.
 *
 * Two sections have nothing to change yet and say so rather than being hidden. A
 * section missing from the phone reads as the app being unfinished; one that says
 * where the setting lives, and why, is an answer. `settings:` and `memory:` are both
 * denied to a paired phone deliberately, so opening them is a decision about the
 * protocol rather than an afternoon of UI.
 *
 * Everything here except the theme belongs to the computer and moves for whoever is
 * sitting at it too, exactly like the active project does.
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
    /** What is already on the computer. Never what could be downloaded. */
    models: List<LocalModel> = emptyList(),
    /** The path of the model actually running, so the list can mark it. */
    activeModelPath: String? = null,
    /** The path being loaded right now, if any. */
    loadingModelPath: String? = null,
    onLoadModel: (String) -> Unit = {},
    /** How this app picks its palette \u2014 the one phone-local setting here. */
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onSelectTheme: (ThemeMode) -> Unit = {},
) {
    val colors = AnodexTheme.colors

    // Which section is open, or null on the index. Saved, so rotating the phone in
    // the middle of choosing a model does not throw you back to the top.
    var section by rememberSaveable { mutableStateOf<SettingsSection?>(null) }

    // Back leaves the section first and the screen second, which is what the arrow
    // in the corner already implies.
    BackHandler(enabled = section != null) { section = null }

    Column(modifier.fillMaxSize().background(colors.bgApp)) {
        Header(
            title = section?.label ?: "Settings",
            onBack = { if (section != null) section = null else onClose() },
        )

        when (section) {
            null -> SettingsIndex(
                hostName = hostName,
                hostStatus = hostStatus,
                installedVersion = installedVersion,
                themeMode = themeMode,
                updateAvailable = newerVersion != null,
                onOpen = { section = it },
            )

            SettingsSection.PROFILE -> AtTheComputer(
                what = "Your name, avatar and account",
                why = "A phone cannot reach the computer's settings. That prefix carries " +
                    "the permission mode, the MCP servers and the model directory, and a " +
                    "client able to write to it could dismantle the protections that let " +
                    "it connect at all.",
            )

            SettingsSection.MEMORY -> AtTheComputer(
                what = "What Anodex remembers about you and your work",
                why = "Memory is denied to a paired phone on purpose. Reading it from away " +
                    "would put the contents of every note on a device that gets left on " +
                    "tables.",
            )

            SettingsSection.APPEARANCE -> AppearanceSection(themeMode, onSelectTheme)

            SettingsSection.AI_MODELS -> AiAndModelsSection(
                personalities = personalities,
                activePersonalityId = activePersonalityId,
                busy = busy,
                onSelectPersonality = onSelectPersonality,
                models = models,
                activeModelPath = activeModelPath,
                loadingModelPath = loadingModelPath,
                onLoadModel = onLoadModel,
            )

            SettingsSection.REMOTE -> RemoteSection(hostName, hostStatus, onOpenHost)

            SettingsSection.ABOUT -> AboutSection(installedVersion, newerVersion)
        }
    }
}

/** The six doors, each saying what is behind it. */
@Composable
private fun SettingsIndex(
    hostName: String?,
    hostStatus: String,
    installedVersion: String,
    themeMode: ThemeMode,
    updateAvailable: Boolean,
    onOpen: (SettingsSection) -> Unit,
) {
    SectionBody {
        Box(Modifier.heightIn(min = Spacing.x2, max = Spacing.x2))

        Group {
            SettingsSection.entries.forEachIndexed { index, entry ->
                if (index > 0) RowDivider()
                SettingsRow(
                    icon = entry.icon,
                    label = entry.label,
                    // The current value where there is one, so the question people
                    // actually open Settings to answer is answered on the index.
                    value = when (entry) {
                        SettingsSection.APPEARANCE -> themeMode.label
                        SettingsSection.REMOTE ->
                            hostName?.let { "$it \u00b7 $hostStatus" } ?: hostStatus
                        SettingsSection.ABOUT ->
                            if (updateAvailable) "Update available" else installedVersion
                        else -> entry.summary
                    },
                    onClick = { onOpen(entry) },
                )
            }
        }
    }
}

/**
 * A section that exists on the computer and is not reachable from here.
 *
 * Shown rather than hidden. A section missing from the phone reads as the app being
 * unfinished; one that says where the setting lives, and why, is an answer.
 */
@Composable
private fun AtTheComputer(what: String, why: String) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    SectionBody(spacing = Spacing.x3) {
        Box(Modifier.heightIn(min = Spacing.x4, max = Spacing.x4))
        Text(what, style = type.bodyEmphasis, color = colors.text)
        Text("Set it at your computer.", style = type.body, color = colors.textMuted)
        Text(why, style = type.meta, color = colors.textFaint)
    }
}

@Composable
private fun AppearanceSection(mode: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    SectionBody {
        SectionLabel("Theme")

        Group {
            ThemeMode.entries.forEachIndexed { index, entry ->
                if (index > 0) RowDivider()
                ChoiceRow(
                    label = entry.label,
                    detail = entry.description,
                    selected = entry == mode,
                    onClick = { onSelect(entry) },
                )
            }
        }

        Footnote(
            "This one is only about this phone. Everything else in Settings is your " +
                "computer\u2019s, and moves for whoever is sitting at it too.",
        )
    }
}

@Composable
private fun AiAndModelsSection(
    personalities: List<Personality>,
    activePersonalityId: String?,
    busy: Boolean,
    onSelectPersonality: (String?) -> Unit,
    models: List<LocalModel>,
    activeModelPath: String?,
    loadingModelPath: String?,
    onLoadModel: (String) -> Unit,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    SectionBody {
        SectionLabel("How Anodex answers")

        Group {
            // Empty before the computer has answered, and after a connection that
            // dropped. Said plainly rather than shown as a card with no rows in it,
            // which reads as a broken setting instead of a pending one.
            if (personalities.isEmpty()) {
                Text(
                    text = "Waiting for your computer\u2026",
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

        if (models.isNotEmpty()) {
            SectionLabel("Model")

            Group {
                models.forEachIndexed { index, model ->
                    if (index > 0) RowDivider()
                    ModelRow(
                        model = model,
                        active = model.path == activeModelPath,
                        loading = model.path == loadingModelPath,
                        // One load at a time. A second while the first is still going
                        // would queue a minutes-long job behind another.
                        enabled = loadingModelPath == null,
                        onClick = { onLoadModel(model.path) },
                    )
                }
            }

            Footnote(
                "Only what is already on your computer. Downloading a new model is done " +
                    "at the machine.",
            )
        }
    }
}

@Composable
private fun RemoteSection(hostName: String?, hostStatus: String, onOpenHost: (() -> Unit)?) {
    SectionBody {
        SectionLabel("Your computer")

        Group {
            SettingsRow(
                icon = AnodexIcon.MONITOR,
                label = hostName ?: "Not paired",
                value = hostStatus,
                onClick = onOpenHost,
            )
        }

        Footnote(
            "The full picture \u2014 model, context, project, and unpairing \u2014 is on the " +
                "computer\u2019s own screen.",
        )
    }
}

@Composable
private fun AboutSection(installedVersion: String, newerVersion: String?) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    SectionBody {
        SectionLabel("About")

        Group {
            SettingsRow(icon = AnodexIcon.INFO, label = "Version", trailing = installedVersion)

            if (newerVersion != null) {
                RowDivider()
                Column(Modifier.padding(Spacing.x4)) {
                    Text(
                        text = "$newerVersion is available",
                        style = type.bodyEmphasis,
                        color = colors.accent,
                    )
                    Text(
                        text = "The banner at the top of the app installs it, and your " +
                            "pairing is kept.",
                        style = type.meta,
                        color = colors.textMuted,
                    )
                }
            }
        }
    }
}

/** The scrolling body every section shares, so they cannot drift apart. */
@Composable
private fun SectionBody(spacing: Dp = 0.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.x4),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        content()
        // Clears the gesture bar, so the last row is reachable rather than sitting
        // under it.
        Box(Modifier.heightIn(min = Spacing.x8, max = Spacing.x8))
    }
}

/** One of a set, with a tick on the one in force. */
@Composable
private fun ChoiceRow(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.accentSoft else Color.Transparent)
            .heightIn(min = Touch.minTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = type.bodyEmphasis, color = colors.text)
            Text(detail, style = type.meta, color = colors.textMuted)
        }

        if (selected) Text("\u2713", style = type.body, color = colors.accent)
    }
}

/**
 * Back on the left, title in the middle.
 *
 * A chevron rather than a boxed "Back" button: the box drew as much weight as the
 * settings under it, and everything else on the phone leaves going back to a plain
 * affordance in the corner.
 *
 * The title names the open section, so the same arrow reads as "up one" rather
 * than "close" — which is what it now does.
 */
@Composable
private fun Header(title: String, onBack: () -> Unit) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Box(Modifier.fillMaxWidth().padding(vertical = Spacing.x2)) {
        Text(
            text = title,
            style = type.bodyEmphasis,
            color = colors.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().align(Alignment.Center),
        )

        Box(
            modifier = Modifier
                .size(Touch.minTarget)
                .clip(CircleShape)
                .clickable(onClick = onBack),
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

/**
 * One model on the computer, and whether it is the one running.
 *
 * Loading is minutes rather than a moment, so the row says "Loading…" for the
 * whole of it. A tap that appears to do nothing for two minutes is indistinguishable
 * from a tap that missed.
 */
@Composable
private fun ModelRow(
    model: LocalModel,
    active: Boolean,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (active) colors.accentSoft else Color.Transparent)
            .heightIn(min = Touch.minTarget)
            .clickable(enabled = enabled && !active, onClick = onClick)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = model.name,
                style = type.bodyEmphasis,
                color = if (enabled || active) colors.text else colors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            val detail = model.detailLabel()
            if (detail.isNotBlank()) {
                Text(detail, style = type.meta, color = colors.textFaint)
            }
        }

        when {
            loading -> Text("Loading…", style = type.meta, color = colors.accent)
            active -> Text("✓", style = type.body, color = colors.accent)
        }
    }
}

@Preview(name = "Settings", showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun PreviewSettings() {
    AnodexTheme(darkTheme = true) {
        SettingsScreen(
            installedVersion = "0.26.0",
            onClose = {},
            personalities = listOf(
                Personality("p1", "Anodex", "The default voice.", "accent"),
                Personality("p2", "Vale", "Direct. Answer first, reasoning after.", "series-2"),
            ),
            activePersonalityId = "p1",
            models = listOf(
                LocalModel("/m/qwen.gguf", "Qwen3 30B A3B", 18_500_000_000, "Q4_K_M"),
            ),
            activeModelPath = "/m/qwen.gguf",
            hostName = "Gort",
            hostStatus = "Connected",
            onOpenHost = {},
            newerVersion = "0.27.0",
        )
    }
}
