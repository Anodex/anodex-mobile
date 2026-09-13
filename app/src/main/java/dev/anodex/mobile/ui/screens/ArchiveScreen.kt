package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.chat.ConversationSummary
import dev.anodex.mobile.chat.Project
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.components.SearchField
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch

/** One thing in the archive, whichever kind it is. */
sealed interface Archived {
    val id: String
    val title: String

    data class Chat(val summary: ConversationSummary) : Archived {
        override val id: String get() = summary.id
        override val title: String get() = summary.title
    }

    data class Workspace(val project: Project) : Archived {
        override val id: String get() = project.id
        override val title: String get() = project.name
    }
}

/**
 * What has been put away, and the only place it can be thrown out.
 *
 * Archiving is reversible and needs no ceremony, which is why it is a swipe
 * elsewhere in this app. Deleting is not, so it lives here and only here: a
 * destructive action reached by accident from a list somebody was scrolling is a
 * different kind of mistake from one reached deliberately from a screen called
 * Archive.
 */
@Composable
fun ArchiveScreen(
    chats: List<ConversationSummary>,
    projects: List<Project>,
    loading: Boolean,
    modifier: Modifier = Modifier,
    error: String? = null,
    onRestore: (Archived) -> Unit = {},
    onDelete: (Archived) -> Unit = {},
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    // All three held here rather than in the view model: they are questions on this
    // screen, and none should survive leaving it. A selection that outlived the screen
    // would be a pending deletion nobody can see.
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirming by remember { mutableStateOf<List<Archived>?>(null) }

    val visibleProjects = remember(projects, query) { projects.filter { it.name.matches(query) } }
    val visibleChats = remember(chats, query) { chats.filter { it.title.matches(query) } }
    val visible = remember(visibleProjects, visibleChats) {
        visibleProjects.map { Archived.Workspace(it) as Archived } +
            visibleChats.map { Archived.Chat(it) as Archived }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.x4),
    ) {
        // Above whatever loaded, not instead of it. The archive is two independent
        // reads, and one failing should not hide the other's perfectly good list.
        error?.let {
            Text(
                text = it,
                style = type.body,
                color = colors.danger,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radii.lg)
                    .background(colors.dangerSoft)
                    .padding(Spacing.x3),
            )
        }

        // Only once there is enough here to be worth searching. A search box above
        // three rows is furniture.
        if (chats.size + projects.size >= SEARCH_WORTH_IT) {
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search the archive",
            )
        }

        if (selected.isNotEmpty()) {
            SelectionBar(
                count = selected.size,
                onClear = { selected = emptySet() },
                onDelete = { confirming = visible.filter { it.id in selected } },
            )
        }

        when {
            loading && chats.isEmpty() && projects.isEmpty() ->
                Text("Reading the archive…", style = type.body, color = colors.textMuted)

            // Only when the lists are genuinely empty. Under a failed read, "nothing
            // archived" is a claim the app cannot make — it does not know.
            chats.isEmpty() && projects.isEmpty() && error == null -> Text(
                "Nothing archived. Chats and projects you archive on either device end up here.",
                style = type.body,
                color = colors.textMuted,
            )

            chats.isEmpty() && projects.isEmpty() -> Unit

            visible.isEmpty() -> Text(
                "Nothing matching “$query”.",
                style = type.body,
                color = colors.textMuted,
            )

            else -> {
                if (visibleProjects.isNotEmpty()) {
                    Label("Projects", visibleProjects.size)
                    visibleProjects.forEach { project ->
                        val item = Archived.Workspace(project)
                        Entry(
                            item = item,
                            detail = project.folderPath.takeIf { it.isNotBlank() },
                            icon = AnodexIcon.FOLDER,
                            selecting = selected.isNotEmpty(),
                            checked = item.id in selected,
                            onToggle = { selected = selected.toggle(item.id) },
                            onRestore = { onRestore(item) },
                            onDelete = { confirming = listOf(item) },
                        )
                    }
                }

                if (visibleChats.isNotEmpty()) {
                    Label("Chats", visibleChats.size)
                    visibleChats.forEach { chat ->
                        val item = Archived.Chat(chat)
                        Entry(
                            item = item,
                            detail = null,
                            icon = AnodexIcon.CHAT,
                            selecting = selected.isNotEmpty(),
                            checked = item.id in selected,
                            onToggle = { selected = selected.toggle(item.id) },
                            onRestore = { onRestore(item) },
                            onDelete = { confirming = listOf(item) },
                        )
                    }
                }
            }
        }
    }

    confirming?.let { targets ->
        ConfirmDelete(
            targets = targets,
            onCancel = { confirming = null },
            onConfirm = {
                targets.forEach(onDelete)
                selected = emptySet()
                confirming = null
            },
        )
    }
}

/** Case-insensitive, and an empty query matches everything rather than nothing. */
private fun String.matches(query: String): Boolean =
    query.isBlank() || contains(query.trim(), ignoreCase = true)

private fun Set<String>.toggle(id: String): Set<String> =
    if (id in this) this - id else this + id

/**
 * What is selected, and the one thing that can be done to all of it.
 *
 * Restore is deliberately absent. Putting a dozen things back at once is harmless and
 * nobody asks for it; deleting a dozen one confirmation at a time is what made this
 * screen unusable with a real archive in it.
 */
@Composable
private fun SelectionBar(count: Int, onClear: () -> Unit, onDelete: () -> Unit) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.lg)
            .background(colors.bgSurface)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (count == 1) "1 selected" else "$count selected",
            style = type.bodyEmphasis,
            color = colors.text,
            modifier = Modifier.weight(1f),
        )
        Action("Cancel", colors.textMuted, onClear)
        Spacer(Modifier.width(Spacing.x2))
        Action("Delete", colors.danger, onDelete)
    }
}

@Composable
private fun Label(text: String, count: Int) {
    Text(
        text = "${text.uppercase()}  $count",
        style = AnodexTheme.type.label,
        color = AnodexTheme.colors.textFaint,
        modifier = Modifier.padding(top = Spacing.x2),
    )
}

@Composable
private fun Entry(
    item: Archived,
    detail: String?,
    icon: AnodexIcon,
    selecting: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.lg)
            .background(if (checked) colors.accentSoft else colors.bgSurface)
            // Tapping a row does nothing until a selection exists, so nothing can be
            // selected by accident on the way past. The icon starts one.
            .clickable(
                onClickLabel = if (selecting) "Select" else null,
                onClick = { if (selecting) onToggle() },
            )
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3)
            .heightIn(min = Touch.minTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(Touch.minTarget)
                .clip(CircleShape)
                .clickable(onClickLabel = "Select", onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            AnodexIcon(
                if (checked) AnodexIcon.CHECK else icon,
                size = 18.dp,
                tint = if (checked) colors.accentInk else colors.textMuted,
            )
        }
        Spacer(Modifier.width(Spacing.x2))

        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = type.body,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Text(
                    text = it,
                    style = type.meta,
                    color = colors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Hidden while selecting. Two ways to delete the same row on screen at once is
        // how somebody deletes one thing while meaning to delete twelve.
        if (!selecting) {
            Action("Restore", colors.accent, onRestore)
            Spacer(Modifier.width(Spacing.x2))
            Action("Delete", colors.danger, onDelete)
        }
    }
}

@Composable
private fun Action(label: String, tint: Color, onClick: () -> Unit) {
    Text(
        text = label,
        style = AnodexTheme.type.meta,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            // The row is already at the minimum target height, so this padding is what
            // gives each word a tap area of its own rather than a shared one.
            .padding(horizontal = Spacing.x3, vertical = Spacing.x3),
    )
}

/**
 * The question asked before something stops existing.
 *
 * Phrased as what is lost rather than "are you sure", because "are you sure" is
 * answered yes by reflex and says nothing about what is about to happen. A project
 * takes its conversations with it, and that is the part somebody would not guess.
 */
@Composable
private fun ConfirmDelete(targets: List<Archived>, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    val projects = targets.count { it is Archived.Workspace }
    val single = targets.singleOrNull()

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = colors.bgElevated,
        title = {
            Text(
                text = when {
                    single is Archived.Workspace -> "Delete this project?"
                    single != null -> "Delete this chat?"
                    else -> "Delete ${targets.size} things?"
                },
                style = type.bodyEmphasis,
                color = colors.text,
            )
        },
        text = {
            Text(
                text = when {
                    single is Archived.Workspace ->
                        "“${single.title}” and every conversation inside it are " +
                            "removed from the computer. Files on disk are left alone. This " +
                            "cannot be undone."

                    single != null ->
                        "“${single.title}” is removed from the computer. This " +
                            "cannot be undone."

                    // The project count is called out separately because a project is
                    // not one item. Deleting three of them may be deleting a hundred
                    // conversations, and a flat total would hide that.
                    projects > 0 ->
                        "${targets.size} things are removed from the computer, including " +
                            "$projects ${if (projects == 1) "project" else "projects"} and " +
                            "every conversation inside them. Files on disk are left alone. " +
                            "This cannot be undone."

                    else ->
                        "${targets.size} conversations are removed from the computer. This " +
                            "cannot be undone."
                },
                style = type.body,
                color = colors.textMuted,
            )
        },
        confirmButton = {
            Text(
                text = "Delete",
                style = type.bodyEmphasis,
                color = colors.danger,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onConfirm)
                    .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
            )
        },
        dismissButton = {
            Text(
                text = "Keep",
                style = type.body,
                color = colors.textMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
            )
        },
    )
}

/** Below this, a search box is furniture rather than help. */
private const val SEARCH_WORTH_IT = 8
