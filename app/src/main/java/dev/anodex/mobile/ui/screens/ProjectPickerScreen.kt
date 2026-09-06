package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import dev.anodex.mobile.chat.Project
import dev.anodex.mobile.ui.components.SecondaryButton
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch

/**
 * Choosing which project the computer is working in.
 *
 * The one screen in this app that changes something for somebody else. There is a
 * single active project and it is shared with whoever is sitting at the computer —
 * switching from here moves their workspace too. The warning says so plainly
 * rather than after the fact, because the alternative is a person at a desk
 * watching their workspace change for no reason they can see.
 *
 * The desktop refuses a switch while it is mid-generation, and that refusal is
 * shown as what it is: "not now", rather than a failure.
 */
@Composable
fun ProjectPickerScreen(
    projects: List<Project>,
    activeProjectId: String?,
    busy: Boolean,
    error: String?,
    onSelect: (String?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(modifier = modifier.fillMaxSize().background(colors.bgApp)) {
        Column(
            modifier = Modifier.padding(Spacing.x4),
            verticalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            Text("Project", style = type.heading, color = colors.text)
            Text(
                text = "This is what Anodex reads and writes. Changing it also changes it for " +
                    "whoever is at the computer.",
                style = type.meta,
                color = colors.textMuted,
            )
        }

        if (error != null) {
            Text(
                text = error,
                style = type.body,
                color = colors.warn,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.x4)
                    .clip(Radii.md)
                    .background(colors.warnSoft)
                    .padding(Spacing.x4),
            )
        }

        if (projects.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No projects on that computer yet. They are created at the desk.",
                    style = type.body,
                    color = colors.textFaint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(Spacing.x6),
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                item {
                    ProjectRow(
                        name = "No project",
                        detail = "Just chat — no files, no workspace",
                        active = activeProjectId == null,
                        enabled = !busy,
                        onClick = { onSelect(null) },
                    )
                }
                items(projects, key = { it.id }) { project ->
                    ProjectRow(
                        name = project.name,
                        detail = project.folderPath,
                        active = project.id == activeProjectId,
                        enabled = !busy,
                        onClick = { onSelect(project.id) },
                    )
                }
            }
        }

        SecondaryButton(
            label = "Close",
            onClick = onClose,
            modifier = Modifier.padding(Spacing.x4),
        )
    }
}

@Composable
private fun ProjectRow(
    name: String,
    detail: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Touch.minTarget)
            .background(if (active) colors.accentSoft else colors.bgApp)
            .clickable(enabled = enabled && !active, onClick = onClick)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
        verticalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        Text(
            text = name,
            style = if (active) type.bodyEmphasis else type.body,
            color = if (enabled || active) colors.text else colors.textFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = type.meta,
                color = colors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
    }
}

@Preview(name = "Projects - dark", showBackground = true, heightDp = 640)
@Composable
private fun PreviewProjects() {
    AnodexTheme(darkTheme = true) {
        ProjectPickerScreen(
            projects = listOf(
                Project("1", "Anodex", "C:\\Users\\Owner\\Desktop\\Anodex4"),
                Project("2", "Anodex Mobile", "C:\\Users\\Owner\\Desktop\\Anodex Mobile"),
            ),
            activeProjectId = "1",
            busy = false,
            error = null,
            onSelect = {},
            onClose = {},
        )
    }
}

@Preview(name = "Projects - refused mid-turn", showBackground = true, heightDp = 640)
@Composable
private fun PreviewBusy() {
    AnodexTheme(darkTheme = false) {
        ProjectPickerScreen(
            projects = listOf(Project("1", "Anodex", "C:\\Users\\Owner\\Desktop\\Anodex4")),
            activeProjectId = "1",
            busy = true,
            error = "Anodex is working on something right now. Wait for it to finish before " +
                "switching project.",
            onSelect = {},
            onClose = {},
        )
    }
}
