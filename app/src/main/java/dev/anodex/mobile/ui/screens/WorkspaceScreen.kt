package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.chat.Project
import dev.anodex.mobile.scheduler.relativeTime
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.components.SearchField
import dev.anodex.mobile.ui.components.matchesQuery
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch
import dev.anodex.mobile.workspace.WorkspaceFile

/**
 * The project's files, newest first.
 *
 * Not a folder navigator. The computer sends a tree, which is right beside an editor
 * and wrong on a six-inch screen — nobody wants to tap through four folders to reach
 * a path they already know. From away the question is "what has changed", so the
 * list is flat, ordered by modification time, and says which of them Anodex touched.
 *
 * That last part is what makes this worth opening rather than a curiosity: it turns
 * a file list into a record of what the computer did while nobody was watching.
 */
@Composable
fun WorkspaceScreen(
    files: List<WorkspaceFile>,
    loading: Boolean,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Null when no project is open, which is a different thing from an empty one. */
    projectName: String? = null,
    /** Why the list is empty, when the reason is not "the project has no files". */
    error: String? = null,
    /** Every project on the computer, so this screen can be its own way in. */
    projects: List<Project> = emptyList(),
    activeProjectId: String? = null,
    onOpenProject: (String) -> Unit = {},
    /** True when Workspace was opened to browse rather than to read a project. */
    browsing: Boolean = false,
    onBrowseProjects: () -> Unit = {},
    /** Start a chat that runs against this project's files. */
    onNewChatHere: (() -> Unit)? = null,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    var query by rememberSaveable { mutableStateOf("") }

    // Matched on the whole path, not just the name: from a phone you are usually
    // looking for "the parser one" or "something under src/sim", and a project has
    // four files called index.
    val shown = remember(files, query) {
        if (query.isBlank()) files else files.filter { matchesQuery(it.path, query) }
    }

    // One pass, kept until the list or the query changes. The list is already
    // newest-first, so a run down it in order is all the grouping needs.
    val groups = remember(shown, query) {
        if (query.isNotBlank()) {
            listOf(FileGroup(band = null, files = shown))
        } else {
            shown.groupBy { timeBand(it.modifiedAt) }
                .map { (band, files) -> FileGroup(band, files) }
        }
    }

    Column(modifier.fillMaxSize().background(colors.bgApp)) {
        // Two ways in, one screen. Opened from the drawer it is the index of
        // projects; opened with one already active it is that project's files. And
        // with no project at all it is the index again — because "No project open"
        // on its own was a dead end, a screen naming the problem while withholding
        // the one thing that would fix it.
        if (browsing || (projectName == null && error == null && !loading)) {
            ProjectPicker(projects, activeProjectId, onOpenProject)
            return@Column
        }

        // The project's name is the way back to the list, since the files you are
        // looking at are the reason you might want a different project. Beside it,
        // the thing there was previously no way to do at all: start work here.
        if (projectName != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = projectName,
                    style = type.label,
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = Touch.minTarget)
                        .clickable(onClick = onBrowseProjects)
                        .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
                )

                if (onNewChatHere != null) {
                    // Named rather than a bare plus. A chat in a project can read and
                    // write real files, so which project it belongs to is the most
                    // important thing about it and belongs in the label.
                    Text(
                        text = "New chat here",
                        style = type.label,
                        color = colors.accent,
                        modifier = Modifier
                            .heightIn(min = Touch.minTarget)
                            .clickable(onClick = onNewChatHere)
                            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
                    )
                }
            }
        }

        if (files.size >= SEARCH_WORTH_IT) {
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search files",
                modifier = Modifier.padding(horizontal = Spacing.x4, vertical = Spacing.x2),
            )
        }

        if (files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.x2),
                    modifier = Modifier.padding(Spacing.x6),
                ) {
                    Text(
                        text = when {
                            loading -> "Reading the project…"
                            error != null -> "Could not read the project"
                            projectName == null -> "No project open"
                            else -> "Nothing in this project yet"
                        },
                        style = type.bodyEmphasis,
                        color = if (error != null) colors.danger else colors.textMuted,
                        textAlign = TextAlign.Center,
                    )

                    if (!loading && error != null) {
                        // An empty project and a failed read looked identical before.
                        Text(
                            text = error,
                            style = type.meta,
                            color = colors.textFaint,
                            textAlign = TextAlign.Center,
                        )
                    }

                    if (!loading && error == null && projectName == null) {
                        // The distinction matters: an empty project and no project at
                        // all look identical in a list of nothing, and the remedy for
                        // one of them is at the computer.
                        Text(
                            text = "Choose one on your computer, or from the host screen.",
                            style = type.meta,
                            color = colors.textFaint,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = Spacing.x2),
        ) {
            if (query.isNotBlank() && shown.isEmpty()) {
                item(key = "no-match") {
                    Text(
                        text = "No file matches “$query”.",
                        style = type.meta,
                        color = colors.textFaint,
                        modifier = Modifier.padding(Spacing.x4),
                    )
                }
            }

            // Grouped by when, not while searching. A search result is an answer to
            // a question, and slicing four matches across three date headings buries
            // them in structure they did not ask for.
            //
            // Unsearched, the headings are the point: this list is newest-first
            // precisely because it doubles as a record of what the computer has been
            // doing unwatched, and "Today" against "Earlier" is that record. A flat
            // column of relative times makes the reader assemble it themselves.
            //
            // Grouped up front rather than by tracking the previous row inside
            // `items`. A LazyColumn composes only what is visible and in whatever
            // order it likes, so a running variable is read when it happens to hold
            // whatever the last *composed* row set — and headings appear, vanish and
            // duplicate as you scroll.
            for (group in groups) {
                if (group.band != null) {
                    item(key = "band-${group.band}") {
                        Text(
                            text = group.band.uppercase(),
                            style = type.badge,
                            color = colors.textFaint,
                            modifier = Modifier.padding(
                                start = Spacing.x4,
                                end = Spacing.x4,
                                top = Spacing.x4,
                                bottom = Spacing.x1,
                            ),
                        )
                    }
                }

                items(group.files, key = { it.path }) { file ->
                    FileRow(file, onClick = { onOpenFile(file.path) })
                }
            }
        }
    }
}

/**
 * Which project to work in, offered where the files would be.
 *
 * The drawer lists these too. Both are worth having: the drawer is the shortcut for
 * somebody who already knows they want to switch, and this is the answer for
 * somebody who opened Workspace and found nothing there.
 */
@Composable
private fun ProjectPicker(
    projects: List<Project>,
    activeProjectId: String?,
    onOpenProject: (String) -> Unit,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    if (projects.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.x2),
                modifier = Modifier.padding(Spacing.x6),
            ) {
                Text(
                    text = "No projects yet",
                    style = type.bodyEmphasis,
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Create one on your computer and it will appear here.",
                    style = type.meta,
                    color = colors.textFaint,
                    textAlign = TextAlign.Center,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = Spacing.x2),
    ) {
        item(key = "prompt") {
            Text(
                text = "Choose a project to work in",
                style = type.meta,
                color = colors.textFaint,
                modifier = Modifier.padding(horizontal = Spacing.x4, vertical = Spacing.x3),
            )
        }

        items(projects, key = { it.id }) { project ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Touch.minTarget)
                    .clickable { onOpenProject(project.id) }
                    .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
            ) {
                AnodexIcon(AnodexIcon.FOLDER, size = 18.dp, tint = colors.textFaint)

                Column(Modifier.weight(1f)) {
                    Text(
                        text = project.name,
                        style = type.body,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = project.folderPath,
                        style = type.meta,
                        color = colors.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (project.id == activeProjectId) {
                    Text("\u2713", style = type.body, color = colors.accent)
                }
            }
        }
    }
}

/** A run of files that were last touched in the same stretch of time. */
private data class FileGroup(val band: String?, val files: List<WorkspaceFile>)

/**
 * Which stretch of time a file was last touched in.
 *
 * Coarse on purpose, and coarser the further back it goes. The question this list
 * answers is "what has been happening", and the useful resolution for that decays:
 * whether something changed today matters, whether it changed on a Tuesday five
 * weeks ago does not.
 *
 * Computed from the difference rather than from calendar days — a phone crossing
 * midnight should not reshuffle the list, and "today" meaning "in the last day" is
 * the reading somebody glancing at it already has.
 */
private fun timeBand(modifiedAt: Long, now: Long = System.currentTimeMillis()): String {
    val age = now - modifiedAt
    return when {
        modifiedAt <= 0L -> "Undated"
        age < DAY -> "Today"
        age < 2 * DAY -> "Yesterday"
        age < 7 * DAY -> "This week"
        age < 30 * DAY -> "This month"
        else -> "Earlier"
    }
}

private const val DAY = 24 * 60 * 60 * 1000L

/**
 * A way in for the tests, since `timeBand` is private and belongs that way.
 *
 * The headings are the one part of this screen with logic worth pinning: the
 * boundaries are arbitrary until they are written down, and the midnight rule is
 * the sort of thing that gets "simplified" into calendar days by somebody who has
 * not watched a list rearrange itself under them.
 */
internal fun timeBandForTest(modifiedAt: Long, now: Long): String = timeBand(modifiedAt, now)

/**
 * Twenty.
 *
 * A project has more files than a chat has conversations, and the list is already
 * newest-first — so the top of it is usually what you came for. The field earns its
 * place a bit later here than it does for conversations.
 */
private const val SEARCH_WORTH_IT = 20

@Composable
private fun FileRow(file: WorkspaceFile, onClick: () -> Unit) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Touch.minTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        AnodexIcon(AnodexIcon.FOLDER, size = 18.dp, tint = colors.textFaint)

        Column(Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = type.body,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            val where = file.folder.ifBlank { "in the project root" }
            val when_ = relativeTime(file.modifiedAt)
            Text(
                text = if (when_ != null) "$where · $when_" else where,
                style = type.meta,
                color = colors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Only marked when Anodex was last to touch it. Marking both would make the
        // column noise; marking the rarer one makes it a signal.
        if (file.editedByAi) {
            Text(
                text = "Anodex",
                style = type.badge,
                color = colors.accent,
                modifier = Modifier
                    .clip(Radii.sm)
                    .background(colors.accentSoft)
                    .padding(horizontal = Spacing.x2, vertical = 2.dp),
            )
        }
    }
}

@Preview(name = "Workspace", showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun PreviewWorkspace() {
    AnodexTheme(darkTheme = true) {
        WorkspaceScreen(
            files = listOf(
                WorkspaceFile(
                    "src/sim/useDragBody.ts", "useDragBody.ts", 4_200,
                    System.currentTimeMillis() - 4 * 60_000, editedByAi = true,
                ),
                WorkspaceFile(
                    "src/sim/orbit.ts", "orbit.ts", 8_800,
                    System.currentTimeMillis() - 3 * 3_600_000, editedByAi = false,
                ),
                WorkspaceFile(
                    "README.md", "README.md", 1_100,
                    System.currentTimeMillis() - 2L * 86_400_000, editedByAi = false,
                ),
            ),
            loading = false,
            onOpenFile = {},
            projectName = "Universe Sandbox",
        )
    }
}
