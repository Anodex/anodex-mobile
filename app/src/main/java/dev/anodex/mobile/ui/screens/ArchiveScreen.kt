package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.chat.ConversationSummary
import dev.anodex.mobile.chat.Project
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.theme.AnodexTheme
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

    // Held here rather than in the view model: it is a question on screen, and it
    // should not survive leaving the screen that asked it.
    var confirming by remember { mutableStateOf<Archived?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.x4),
    ) {
        // Above whatever loaded, not instead of it. The archive is two independent
        // reads, and the first version of this screen replaced everything with the
        // error — so one failing read hid the other one's perfectly good list, and
        // a screen that was half working looked entirely broken.
        error?.let {
            Text(
                text = it,
                style = type.body,
                color = colors.danger,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.dangerSoft)
                    .padding(Spacing.x3),
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

            else -> {
                if (projects.isNotEmpty()) {
                    Label("Projects", projects.size)
                    projects.forEach { project ->
                        Entry(
                            title = project.name,
                            detail = project.folderPath.takeIf { it.isNotBlank() },
                            icon = AnodexIcon.FOLDER,
                            onRestore = { onRestore(Archived.Workspace(project)) },
                            onDelete = { confirming = Archived.Workspace(project) },
                        )
                    }
                }

                if (chats.isNotEmpty()) {
                    Label("Chats", chats.size)
                    chats.forEach { chat ->
                        Entry(
                            title = chat.title,
                            detail = null,
                            icon = AnodexIcon.CHAT,
                            onRestore = { onRestore(Archived.Chat(chat)) },
                            onDelete = { confirming = Archived.Chat(chat) },
                        )
                    }
                }
            }
        }
    }

    confirming?.let { target ->
        ConfirmDelete(
            target = target,
            onCancel = { confirming = null },
            onConfirm = {
                onDelete(target)
                confirming = null
            },
        )
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
    title: String,
    detail: String?,
    icon: AnodexIcon,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.bgSurface)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3)
            .heightIn(min = Touch.minTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnodexIcon(icon, size = 18.dp, tint = colors.textMuted)
        Spacer(Modifier.width(Spacing.x3))

        Column(Modifier.weight(1f)) {
            Text(
                text = title,
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

        Action("Restore", colors.accent, onRestore)
        Spacer(Modifier.width(Spacing.x2))
        Action("Delete", colors.danger, onDelete)
    }
}

@Composable
private fun Action(label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        text = label,
        style = AnodexTheme.type.meta,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            // The row is already at the minimum target height, so the padding here
            // is what gives each word a tap area of its own rather than a shared one.
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
private fun ConfirmDelete(target: Archived, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        containerColor = colors.bgElevated,
        title = {
            Text(
                text = when (target) {
                    is Archived.Workspace -> "Delete this project?"
                    is Archived.Chat -> "Delete this chat?"
                },
                style = type.bodyEmphasis,
                color = colors.text,
            )
        },
        text = {
            Text(
                text = when (target) {
                    is Archived.Workspace ->
                        "“${target.title}” and every conversation inside it are " +
                            "removed from the computer. Files on disk are left alone. This " +
                            "cannot be undone."

                    is Archived.Chat ->
                        "“${target.title}” is removed from the computer. This " +
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
