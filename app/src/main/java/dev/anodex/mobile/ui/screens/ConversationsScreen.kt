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
import dev.anodex.mobile.chat.ConversationSummary
import dev.anodex.mobile.ui.components.PrimaryButton
import dev.anodex.mobile.ui.components.SearchField
import dev.anodex.mobile.ui.components.matchesQuery
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch

/**
 * The conversations on the computer.
 *
 * A live read, never a cache: the desktop's store is authoritative, so this is
 * empty rather than stale when the machine is unreachable. That is the same
 * bargain the rest of the app makes, and it is why there is no "offline" version
 * of this list to fall back to.
 */
@Composable
fun ConversationsScreen(
    conversations: List<ConversationSummary>,
    loading: Boolean,
    activeId: String?,
    onOpen: (String) -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier,
    nowEpochMs: Long = System.currentTimeMillis(),
    /** The project every new turn will run in. Null means plain chat. */
    activeProjectName: String? = null,
    onChooseProject: (() -> Unit)? = null,
    /** Project id to name, for the group headings and the row tags. */
    projectNames: Map<String, String> = emptyMap(),
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    var query by rememberSaveable { mutableStateOf("") }

    // Matched against the project name too, so "sandbox" finds everything filed
    // there — the way somebody actually remembers a conversation is often by where
    // the work was, not by what the title ended up saying.
    val shown = remember(conversations, query, projectNames) {
        if (query.isBlank()) {
            conversations
        } else {
            conversations.filter { conversation ->
                val project = conversation.projectId?.let { projectNames[it] }.orEmpty()
                matchesQuery("${conversation.title} $project", query)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().background(colors.bgApp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Conversations", style = type.heading, color = colors.text)
            PrimaryButton(label = "New", onClick = onNewChat)
        }

        // Offered once there are enough of them to be worth searching. Below that
        // the field is a control taking up room above a list you can already see
        // all of.
        if (conversations.size >= SEARCH_WORTH_IT) {
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search conversations",
                modifier = Modifier.padding(horizontal = Spacing.x4, vertical = Spacing.x1),
            )
        }

        if (query.isNotBlank() && shown.isEmpty()) {
            Text(
                text = "Nothing matches “$query”.",
                style = type.meta,
                color = colors.textFaint,
                modifier = Modifier.padding(horizontal = Spacing.x4, vertical = Spacing.x4),
            )
        }

        // Which project the *computer* has open — for the workspace and for agent
        // runs, not for the chat below.
        //
        // This used to say what a turn would run against, and the phone made that
        // true by filing every new chat into the active project. That was wrong: a
        // plain chat belongs to no project, only the workspace and agents touch work
        // files, and a chat that quietly acquired one could edit real files because
        // of a setting changed for an unrelated reason.
        if (onChooseProject != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Touch.minTarget)
                    .clickable(onClick = onChooseProject)
                    .padding(horizontal = Spacing.x4, vertical = Spacing.x2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
            ) {
                Text("Computer is working in", style = type.meta, color = colors.textFaint)
                Text(
                    text = activeProjectName ?: "no project",
                    style = type.label,
                    color = if (activeProjectName != null) colors.accent else colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        when {
            loading && conversations.isEmpty() ->
                Centred("Reading from your computer…", colors.textFaint)

            conversations.isEmpty() ->
                Centred("No conversations yet. Start one.", colors.textFaint)

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                // Flat while searching, for the same reason the workspace list is:
                // results are an answer to a question, and four matches split across
                // three project headings and an "Active now" is more structure than
                // the answer has content. Grouping is for browsing.
                val groups = if (query.isBlank()) {
                    groupConversations(shown, activeId, projectNames)
                } else {
                    listOf(ConversationGroup("Matches", shown, isProject = false))
                }

                for (group in groups) {
                    item(key = "group-${group.label}") { GroupLabel(group.label) }

                    items(group.conversations, key = { it.id }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            active = conversation.id == activeId,
                            nowEpochMs = nowEpochMs,
                            // Only inside a project's own group is the tag
                            // redundant, and there the heading already says it.
                            projectTag = if (group.isProject) {
                                null
                            } else {
                                conversation.projectId?.let { projectNames[it] }
                            },
                            onClick = { onOpen(conversation.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: ConversationSummary,
    active: Boolean,
    nowEpochMs: Long,
    onClick: () -> Unit,
    projectTag: String? = null,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Touch.minTarget)
            .background(if (active) colors.bgSurface2 else colors.bgApp)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
        verticalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            if (projectTag != null) ProjectTag(projectTag)

            Text(
                text = conversation.title,
                style = if (active) type.bodyEmphasis else type.body,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = buildString {
                append(relativeLastSeen(conversation.updatedAtEpochMs, nowEpochMs))
                if (conversation.messageCount > 0) {
                    append(" · ")
                    append(conversation.messageCount)
                    append(if (conversation.messageCount == 1) " message" else " messages")
                }
            },
            style = type.meta,
            color = colors.textFaint,
        )
    }
}

/**
 * One heading in the list.
 *
 * Sticky would be the fashionable choice; these groups are short enough that a
 * heading pinned to the top would spend most of its life covering the row under it.
 */
@Composable
private fun GroupLabel(label: String) {
    val colors = AnodexTheme.colors
    Text(
        text = label.uppercase(),
        style = AnodexTheme.type.badge,
        color = colors.textFaint,
        modifier = Modifier.padding(
            start = Spacing.x4,
            end = Spacing.x4,
            top = Spacing.x4,
            bottom = Spacing.x1,
        ),
    )
}

/** Which workspace a conversation belongs to, when the heading does not already say. */
@Composable
private fun ProjectTag(name: String) {
    val colors = AnodexTheme.colors
    Text(
        text = name,
        style = AnodexTheme.type.badge,
        color = colors.accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(Radii.sm)
            .background(colors.accentSoft)
            .padding(horizontal = Spacing.x2, vertical = 1.dp),
    )
}

/** A heading and the conversations under it. */
internal data class ConversationGroup(
    val label: String,
    val conversations: List<ConversationSummary>,
    /** True when the heading is a project name, so its rows need no tag. */
    val isProject: Boolean,
)

/**
 * The list, in the order and shape the design sample calls for.
 *
 * A flat list sorted by time is technically correct and useless in practice: it puts
 * a conversation that is editing real files next to one that is not, with nothing to
 * tell them apart. Three kinds of group, in this order:
 *
 * 1. **Active now** — the one open on the computer, if there is one. It is the thing
 *    the user came back to and it should not have to be found.
 * 2. **One per project**, most recently touched first. These are the conversations
 *    that can change files.
 * 3. **Chats** — everything with no project. Talking, not working.
 *
 * Empty groups are never emitted: a heading with nothing under it reads as something
 * having failed to load.
 */
internal fun groupConversations(
    conversations: List<ConversationSummary>,
    activeId: String?,
    projectNames: Map<String, String>,
): List<ConversationGroup> {
    val groups = mutableListOf<ConversationGroup>()

    val active = conversations.firstOrNull { it.id == activeId }
    if (active != null) {
        groups += ConversationGroup("Active now", listOf(active), isProject = false)
    }

    val rest = conversations.filter { it.id != activeId }

    // Grouped by id, labelled by name. A project the phone has not heard of yet -
    // one added on the computer since this list was fetched - still gets its own
    // group rather than being dropped in with plain chats, which would be a lie.
    val byProject = rest.filter { it.projectId != null }.groupBy { it.projectId!! }

    for ((projectId, items) in byProject.entries.sortedByDescending { entry ->
        entry.value.maxOf { it.updatedAtEpochMs }
    }) {
        groups += ConversationGroup(
            label = projectNames[projectId] ?: "Project",
            conversations = items.sortedByDescending { it.updatedAtEpochMs },
            isProject = true,
        )
    }

    val plain = rest.filter { it.projectId == null }
    if (plain.isNotEmpty()) {
        groups += ConversationGroup(
            label = "Chats",
            conversations = plain.sortedByDescending { it.updatedAtEpochMs },
            isProject = false,
        )
    }

    return groups
}

/**
 * Twelve.
 *
 * Below this the whole list is a scroll away, and a search field is a control
 * sitting above something you can already see all of. Above it, finding one
 * conversation by scrolling stops being reasonable.
 */
private const val SEARCH_WORTH_IT = 12

@Composable
private fun Centred(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = AnodexTheme.type.body,
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(Spacing.x6),
        )
    }
}

private const val PREVIEW_NOW = 1_757_000_000_000L

@Preview(name = "Conversations - dark", showBackground = true, heightDp = 640)
@Composable
private fun PreviewConversations() {
    AnodexTheme(darkTheme = true) {
        ConversationsScreen(
            conversations = listOf(
                ConversationSummary("1", "Scheduler monthly recurrence bug", PREVIEW_NOW - 200_000_000, PREVIEW_NOW - 120_000, 14),
                ConversationSummary("2", "Port the message bubble to Compose", PREVIEW_NOW - 200_000_000, PREVIEW_NOW - 3_600_000, 6),
                ConversationSummary("3", "Untitled", PREVIEW_NOW - 200_000_000, PREVIEW_NOW - 86_400_000, 1),
            ),
            loading = false,
            activeId = "1",
            onOpen = {},
            onNewChat = {},
            nowEpochMs = PREVIEW_NOW,
        )
    }
}

@Preview(name = "Conversations empty - light", showBackground = true, heightDp = 640)
@Composable
private fun PreviewEmpty() {
    AnodexTheme(darkTheme = false) {
        ConversationsScreen(
            conversations = emptyList(),
            loading = false,
            activeId = null,
            onOpen = {},
            onNewChat = {},
            nowEpochMs = PREVIEW_NOW,
        )
    }
}
