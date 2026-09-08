package dev.anodex.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.chat.ConversationSummary
import dev.anodex.mobile.chat.Project
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch

/** Where the drawer can take you. */
enum class AppDestination(val label: String, val icon: AnodexIcon) {
    CHAT("Chat", AnodexIcon.CHAT),
    WORKSPACE("Workspace", AnodexIcon.FOLDER),
    AGENTS("Agents", AnodexIcon.BOT),
    EMAIL("Email", AnodexIcon.MAIL),
    SCHEDULER("Scheduler", AnodexIcon.CLOCK),
}

/**
 * The app's navigation, as a drawer.
 *
 * This replaced a bottom tab bar, which every one of the four assistants this was
 * measured against had already abandoned. A permanent strip spends a fixed slice of
 * a small screen on navigation nobody does often, and it competed with the composer
 * for the one edge the thumb actually rests on.
 *
 * A drawer costs one tap to reach and gives all of it back. It also has room for the
 * conversation list, which is the thing people are usually looking for when they
 * navigate at all — a bottom bar had nowhere to put that except its own tab.
 */
@Composable
fun AppDrawer(
    destination: AppDestination,
    onSelect: (AppDestination) -> Unit,
    conversations: List<ConversationSummary>,
    /**
     * The computer's projects, listed instead of conversations while Workspace is
     * the open destination.
     *
     * The drawer's second half answers "what am I working on", and what that means
     * depends on which half of the app you are in: in Chat it is a conversation, in
     * Workspace it is a project. Showing conversations under a file browser is a
     * list of the wrong nouns.
     */
    projects: List<Project> = emptyList(),
    activeProjectId: String? = null,
    onOpenProject: (String) -> Unit = {},
    activeConversationId: String?,
    onOpenConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    onOpenAllConversations: () -> Unit,
    onClose: () -> Unit,
    hostName: String,
    hostDetail: String,
    connected: Boolean,
    onOpenHost: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    agentBadge: Int = 0,
    emailBadge: Int = 0,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Box(modifier.fillMaxSize().background(colors.bgBase)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.x4, top = Spacing.x5, end = Spacing.x4, bottom = Spacing.x4),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
            ) {
                AnodexMark(size = 26.dp)
                Text(
                    // The desktop's title bar renders `Anode<span>x</span>` with the accent
                    // on the last letter. Same wordmark, same token — it should not drift
                    // between the two apps.
                    text = buildAnnotatedString {
                        append("Anode")
                        withStyle(SpanStyle(color = colors.accent)) { append("x") }
                    },
                    style = type.title,
                    color = colors.text,
                )
                Box(
                    Modifier
                        .size(Touch.minTarget)
                        .clip(Radii.md)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", style = type.body, color = colors.textFaint)
                }
            }

            for (entry in AppDestination.entries) {
                DestinationRow(
                    destination = entry,
                    selected = entry == destination,
                    badge = when (entry) {
                        AppDestination.AGENTS -> agentBadge
                        AppDestination.EMAIL -> emailBadge
                        else -> 0
                    },
                    onClick = { onSelect(entry) },
                )
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.x4, vertical = Spacing.x4)
                    .height(1.dp)
                    .background(colors.border)
            )

            Text(
                text = "RECENT",
                style = type.badge,
                color = colors.textFaint,
                modifier = Modifier.padding(horizontal = Spacing.x4, vertical = Spacing.x1),
            )

            // Plain text, no metadata. Every one of the four apps does this, and they are
            // right: a title is a sentence, and timestamps are noise at the moment you are
            // scanning for something you remember writing.
            // Both kinds, because "recent" is about what you were last doing rather
            // than which section it belonged to. The section rows above open the full
            // index of each; this is the shortcut back into the two or three things
            // anybody actually returns to.
            // Only what a person started. A scheduled run and a benchmark script write
            // conversations exactly like a real one, so ordering by last write showed the
            // computer's activity rather than the user's — one chat they had actually
            // used, surrounded by eleven they had never opened.
            val mine = conversations.filter { it.isMine }
            val machineMade = conversations.size - mine.size

            LazyColumn(Modifier.weight(1f)) {
                val openProject = projects.firstOrNull { it.id == activeProjectId }
                if (openProject != null) {
                    // Named, because a project and a conversation sat in one
                    // undifferentiated list and nothing said which was which. A folder
                    // icon carried the whole distinction, and an icon is not a label —
                    // it tells you something is a folder only if you already know that
                    // is the thing being asked.
                    item(key = "kind-workspace") { KindLabel("WORKSPACE") }

                    item(key = "project-${openProject.id}") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Touch.minTarget)
                                .clickable { onOpenProject(openProject.id) }
                                .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
                        ) {
                            AnodexIcon(
                                AnodexIcon.FOLDER,
                                size = 16.dp,
                                tint = colors.textFaint,
                            )
                            Text(
                                text = openProject.name,
                                style = type.body,
                                color = colors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                if (mine.isNotEmpty()) {
                    item(key = "kind-chats") { KindLabel("CHATS") }
                }

                items(mine.take(RECENT_LIMIT), key = { it.id }) { conversation ->
                    Text(
                        text = conversation.title,
                        style = type.body,
                        color = if (conversation.id == activeConversationId) {
                            colors.text
                        } else {
                            colors.textMuted
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Touch.minTarget)
                            .clickable { onOpenConversation(conversation.id) }
                            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
                    )
                }

                // Recents are a shortcut, not the archive. Without this the older
                // conversations would simply have nowhere to be reached from, which
                // the bottom bar's Chats tab used to provide.
                if (mine.size > RECENT_LIMIT) {
                    item(key = "all") {
                        Text(
                            text = "All ${mine.size} conversations",
                            style = type.label,
                            color = colors.accent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Touch.minTarget)
                                .clickable(onClick = onOpenAllConversations)
                                .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
                        )
                    }
                }

                // Said out loud, because a filtered list and a lost one look identical.
                // It also points at where those runs actually are, rather than leaving
                // somebody to wonder whether the computer threw them away.
                if (machineMade > 0) {
                    item(key = "machine-made") {
                        Text(
                            text = "$machineMade scheduled and agent runs are kept out of " +
                                "this list. They are in Scheduler and Agents.",
                            style = type.meta,
                            color = colors.textFaint,
                            modifier = Modifier.padding(
                                horizontal = Spacing.x4,
                                vertical = Spacing.x3,
                            ),
                        )
                    }
                }
            }

            HostFooter(hostName, hostDetail, connected, onOpenHost, onOpenSettings)
        }

        // Bottom-right, where the thumb already is. Both Claude apps put "new" there
        // rather than as a small target in a header, and starting a conversation is
        // the most common reason to open this drawer at all.
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = Spacing.x4, bottom = 88.dp)
                .clip(Radii.pill)
                .background(colors.text)
                .clickable(onClick = onNewChat)
                .padding(horizontal = Spacing.x5, vertical = Spacing.x3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            Text("+", style = type.bodyEmphasis, color = colors.bgBase)
            Text("New chat", style = type.bodyEmphasis, color = colors.bgBase)
        }
    }
}

/**
 * Which kind of thing the rows under it are.
 *
 * Quieter than the section headings above it: this separates two short lists inside
 * one area rather than announcing a new area, and a second heading at full strength
 * would compete with the navigation it sits beneath.
 */
@Composable
private fun KindLabel(text: String) {
    Text(
        text = text,
        style = AnodexTheme.type.badge,
        color = AnodexTheme.colors.textFaint,
        modifier = Modifier.padding(
            start = Spacing.x4,
            end = Spacing.x4,
            top = Spacing.x3,
            bottom = Spacing.x1,
        ),
    )
}

@Composable
private fun DestinationRow(
    destination: AppDestination,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    val tint = if (selected) colors.accent else colors.text

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.x2)
            .clip(Radii.lg)
            .background(if (selected) colors.accentSoft else colors.bgBase)
            .heightIn(min = Touch.minTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x4),
    ) {
        AnodexIcon(destination.icon, size = 19.dp, tint = tint, contentDescription = null)
        Text(destination.label, style = type.body, color = tint, modifier = Modifier.weight(1f))

        if (badge > 0) {
            Box(
                Modifier
                    .heightIn(min = 17.dp)
                    .clip(Radii.pill)
                    .background(colors.warn)
                    .padding(horizontal = Spacing.x2),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (badge > 9) "9+" else badge.toString(),
                    style = type.badge,
                    color = colors.bgBase,
                )
            }
        }
    }
}

/**
 * The computer, where an account would be in anyone else's drawer.
 *
 * Because here it is the account: there is no Anodex login, and the only identity
 * that matters is which machine this phone is driving.
 */
@Composable
private fun HostFooter(
    hostName: String,
    hostDetail: String,
    connected: Boolean,
    onOpenHost: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Touch.minTarget)
                    .clickable(onClick = onOpenHost)
                    .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (connected) colors.success else colors.textFaint)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = hostName,
                        style = type.label,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = hostDetail,
                        style = type.meta,
                        color = colors.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                Modifier
                    .size(Touch.minTarget)
                    .clip(Radii.md)
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                AnodexIcon(
                    AnodexIcon.SETTINGS,
                    size = 18.dp,
                    tint = colors.textFaint,
                    contentDescription = "Settings",
                )
            }
        }
    }
}

/** Enough to recognise a conversation you were in; the full list lives in Chat. */
/**
 * Six.
 *
 * This list is described a few lines up as "the two or three things anybody actually
 * returns to", and then took twelve — most of a phone screen, and more than the
 * sentence claims. Six leaves room for the shortcut to still be a shortcut.
 */
private const val RECENT_LIMIT = 6

@Preview(name = "Drawer", showBackground = true, backgroundColor = 0xFF080808, heightDp = 700)
@Composable
private fun PreviewDrawer() {
    AnodexTheme(darkTheme = true) {
        AppDrawer(
            destination = AppDestination.CHAT,
            onSelect = {},
            conversations = listOf(
                ConversationSummary("1", "Fix the orbit panel jitter", 0, 0, 6),
                ConversationSummary("2", "Why do the scheduler tests fail?", 0, 0, 12),
                ConversationSummary("3", "Weekend reading list", 0, 0, 3),
            ),
            activeConversationId = "1",
            onOpenConversation = {},
            onNewChat = {},
            onOpenAllConversations = {},
            onClose = {},
            hostName = "STUDIO-PC",
            hostDetail = "Connected · Bench",
            connected = true,
            onOpenHost = {},
            onOpenSettings = {},
            agentBadge = 1,
            emailBadge = 3,
        )
    }
}
