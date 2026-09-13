package dev.anodex.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
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
     * The computer's projects — here to *name* the workspaces the conversations are
     * filed under, not as a list of their own.
     *
     * The drawer's second half answers "what am I working on", and the answer has
     * two levels: which workspace, and which conversation inside it. A conversation
     * carries only its project's id, so without this the groups would all be headed
     * "Project".
     */
    projects: List<Project> = emptyList(),
    activeProjectId: String? = null,
    activeConversationId: String?,
    onOpenConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    onOpenAllConversations: () -> Unit,
    onClose: () -> Unit,
    /**
     * Open the conversation index with the search field focused.
     *
     * Every assistant this app was measured against puts search at the top of its
     * drawer. Anodex had it, on the full index, shown only past twelve conversations
     * and reached only by tapping Chat — so for most people it did not exist.
     */
    onSearch: () -> Unit = onOpenAllConversations,
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

    // Which workspaces are open.
    //
    // The one the computer is in, and nothing else. The desktop can afford every
    // group expanded; this panel has room for about eight rows before the footer,
    // and three open workspaces push the chats off the bottom of the phone.
    //
    // Not persisted, deliberately. The drawer is composed when it opens, so each
    // opening starts from where the computer actually is rather than from a shape
    // left behind an hour ago.
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    // fillMaxHeight, not fillMaxSize: the caller decides the width now, because
    // this is a panel sliding over the app rather than a screen replacing it.
    // `bgSurface`, not `bgBase`.
    //
    // This panel slides *over* the conversation, and it was painted with the bottom
    // rung of the ladder — darker than the `bgApp` page underneath it, the only
    // surface in the app below the app's own ground. In the light theme that made it
    // the muddiest colour in the palette. A panel above the page belongs above it on
    // the ladder too, which is most of why this read as borrowed from another app.
    Box(modifier.fillMaxHeight().background(colors.bgSurface)) {
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
                        withStyle(SpanStyle(color = colors.accentInk)) { append("x") }
                    },
                    style = type.title,
                    color = colors.text,
                    // Takes the slack, which nothing did before — so the close control
                    // sat wherever the wordmark happened to end, floating mid-panel
                    // rather than at the edge a thumb reaches for.
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(Touch.minTarget)
                        .clip(Radii.md)
                        .clickable(role = Role.Button, onClick = onSearch),
                    contentAlignment = Alignment.Center,
                ) {
                    AnodexIcon(
                        AnodexIcon.SEARCH,
                        size = 18.dp,
                        tint = colors.textMuted,
                        contentDescription = "Search conversations",
                    )
                }
                Box(
                    Modifier
                        .size(Touch.minTarget)
                        .clip(Radii.md)
                        .clickable(role = Role.Button, onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    // Drawn, not typed. This was the character `✕` set in the body
                    // font — the one glyph in an app whose every other symbol is cut
                    // from the desktop's paths, and it showed: wrong weight, wrong
                    // optical size, and a screen reader reading out "multiplication x".
                    AnodexIcon(
                        AnodexIcon.CLOSE,
                        size = 18.dp,
                        tint = colors.textMuted,
                        contentDescription = "Close the menu",
                    )
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

            Hairline(Modifier.padding(horizontal = Spacing.x4, vertical = Spacing.x4))

            // No "RECENT" heading. What follows is grouped by workspace now, and a
            // recency heading over it named the wrong axis — the desktop's sidebar
            // names the two kinds of thing and lets order carry the rest.
            //
            // Only what a person started, in both sections. A scheduled run and a
            // benchmark script write conversations exactly like a real one, so a
            // list ordered by last write showed the computer's activity rather than
            // the user's — one chat they had actually used, surrounded by eleven
            // they had never opened.
            val sections = drawerSections(conversations, projects, activeProjectId)

            // What the list can actually show, against what exists. The "all
            // conversations" row appears only when those differ, so it is never a
            // link to the same six rows already on screen.
            val shown = sections.workspaces.sumOf {
                minOf(it.conversations.size, WORKSPACE_CHAT_LIMIT)
            } + minOf(sections.chats.size, RECENT_LIMIT)

            // Resolved here rather than inside the list. A LazyColumn's content block
            // is not an ordinary composable scope, and reading the expansion map from
            // in there is the sort of thing that works until it quietly does not.
            val openWorkspaces = sections.workspaces
                .filter { expanded[it.id] ?: (it.id == activeProjectId) }
                .map { it.id }
                .toSet()

            // Dissolves at both ends rather than cutting off square, so a long list
            // reads as scrolled rather than cropped. Every other scrolling surface
            // in the app does this.
            LazyColumn(Modifier.weight(1f).fadingEdges(Spacing.x4, Spacing.x4)) {
                if (sections.workspaces.isNotEmpty()) {
                    item(key = "kind-workspace") {
                        KindLabel("WORKSPACE", sections.workspaces.size)
                    }
                }

                for (workspace in sections.workspaces) {
                    val open = workspace.id in openWorkspaces

                    item(key = "workspace-${workspace.id}") {
                        WorkspaceRow(
                            name = workspace.name,
                            count = workspace.conversations.size,
                            expanded = open,
                            active = workspace.id == activeProjectId,
                            onClick = { expanded[workspace.id] = !open },
                        )
                    }

                    if (!open) continue

                    // Said out loud rather than left blank: a row that opens onto
                    // nothing is indistinguishable from one that failed to load.
                    if (workspace.conversations.isEmpty()) {
                        item(key = "workspace-${workspace.id}-empty") {
                            Text(
                                text = "No chats in this workspace yet",
                                style = type.meta,
                                color = colors.textFaint,
                                modifier = Modifier.padding(
                                    start = INDENT,
                                    end = Spacing.x4,
                                    top = Spacing.x2,
                                    bottom = Spacing.x3,
                                ),
                            )
                        }
                    }

                    items(
                        workspace.conversations.take(WORKSPACE_CHAT_LIMIT),
                        key = { it.id },
                    ) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            active = conversation.id == activeConversationId,
                            indented = true,
                            onClick = { onOpenConversation(conversation.id) },
                        )
                    }

                    if (workspace.conversations.size > WORKSPACE_CHAT_LIMIT) {
                        item(key = "workspace-${workspace.id}-more") {
                            MoreRow(
                                text = "All ${workspace.conversations.size} in " +
                                    workspace.name,
                                indented = true,
                                onClick = onOpenAllConversations,
                            )
                        }
                    }
                }

                if (sections.chats.isNotEmpty()) {
                    item(key = "kind-chats") { KindLabel("CHATS", sections.chats.size) }
                }

                // Plain text, no metadata. Every one of the four apps does this, and
                // they are right: a title is a sentence, and timestamps are noise at
                // the moment you are scanning for something you remember writing.
                items(sections.chats.take(RECENT_LIMIT), key = { it.id }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        active = conversation.id == activeConversationId,
                        indented = false,
                        onClick = { onOpenConversation(conversation.id) },
                    )
                }

                // Recents are a shortcut, not the archive. Without this the older
                // conversations would simply have nowhere to be reached from, which
                // the bottom bar's Chats tab used to provide.
                if (sections.totalMine > shown) {
                    item(key = "all") {
                        MoreRow(
                            text = "All ${sections.totalMine} conversations",
                            indented = false,
                            onClick = onOpenAllConversations,
                        )
                    }
                }

                // Said out loud, because a filtered list and a lost one look identical.
                // It also points at where those runs actually are, rather than leaving
                // somebody to wonder whether the computer threw them away.
                if (sections.machineMade > 0) {
                    item(key = "machine-made") {
                        Text(
                            text = "${sections.machineMade} scheduled and agent runs are " +
                                "kept out of this list. They are in Scheduler and Agents.",
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

            // Pinned above the footer, not floating over the list.
            //
            // It was a pill drawn on top of the conversations, 88dp up from the
            // bottom, and in a panel this narrow it covered most of the width. With
            // nine conversations it sat on one: a title read "Which of my 14 unread
            // ema" with a white pill where the rest of the sentence should have been.
            // Fading the list behind it only made the covered title dimmer, which is
            // not the same as readable.
            //
            // A floating button works over a full-width page where it occupies a
            // corner. Here the sensible shape is a row that belongs to the panel —
            // same thumb position, nothing hidden, and it reads as part of the menu
            // rather than as something dropped on top of it.
            // Lit in the mark's own violet → blue, rather than outlined in grey.
            //
            // This control has now been wrong in both directions. Solid white made it
            // the loudest thing in a panel whose complaint was bulk; a grey outline
            // made it so quiet it read as disabled. The answer is not a third weight
            // of grey — it is colour, and this app already has exactly one gradient
            // that means Anodex: the violet → blue ramp the mark is drawn in.
            //
            // `FacetField` establishes the idiom — the same two colours at around 5%
            // over a dark ground, present without being loud. This is the same move
            // with the ramp carried on the edge, where it reads as a lit rim rather
            // than a filled shape.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.x4, vertical = Spacing.x3)
                    .clip(Radii.pill)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                colors.accentViolet.copy(alpha = 0.14f),
                                colors.accent.copy(alpha = 0.10f),
                            )
                        )
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            Brush.horizontalGradient(
                                listOf(colors.accentViolet, colors.accent)
                            ),
                        ),
                        Radii.pill,
                    )
                    .clickable(onClick = onNewChat)
                    .heightIn(min = Touch.minTarget)
                    .padding(horizontal = Spacing.x5),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.x2, Alignment.CenterHorizontally),
            ) {
                // Violet on the mark, to start the same ramp the rim runs.
                Text("+", style = type.bodyEmphasis, color = colors.accentViolet)
                Text("New chat", style = type.bodyEmphasis, color = colors.text)
            }

            HostFooter(hostName, hostDetail, connected, onOpenHost, onOpenSettings)
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
private fun KindLabel(text: String, count: Int) {
    Text(
        // The count belongs to the heading, the way the desktop writes it. It
        // answers "is anything hidden under here" before the group is opened.
        text = "$text  $count",
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

/**
 * One workspace, and the switch that shows what is filed under it.
 *
 * Tapping it expands rather than opens. On the desktop those are the same click,
 * because selecting a workspace there costs nothing; here it would call
 * `projects:set-active`, which is global — it moves the workspace of whoever is
 * sitting at the computer, and the desktop refuses it outright mid-generation.
 * Making the common gesture, "show me what is in here", carry that is wrong.
 * Opening a workspace stays where it already was: the Workspace row above.
 */
@Composable
private fun WorkspaceRow(
    name: String,
    count: Int,
    expanded: Boolean,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Touch.minTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        AnodexIcon(
            AnodexIcon.FOLDER,
            size = 16.dp,
            // The accent marks the workspace the computer is actually in. Without
            // it, a list of folders says nothing about where a message would land.
            tint = if (active) colors.accentInk else colors.textFaint,
            // There is no chevron any more — the folder is the control, and a second
            // glyph beside it was a disclosure arrow explaining a row that already
            // explains itself the moment it opens. The state still has to be said out
            // loud for anyone who cannot see the indent, so the folder says it.
            contentDescription = if (expanded) "Collapse $name" else "Expand $name",
        )
        Text(
            text = name,
            style = type.bodyEmphasis,
            color = if (active) colors.text else colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Sized to its text rather than to the row, so the count stays beside the
            // name instead of drifting out to an edge with nothing to sit against.
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(count.toString(), style = type.meta, color = colors.textFaint)
    }
}

/**
 * How an open row is marked: a wash of light at the leading edge, not a bar.
 *
 * Copied in intent from the desktop's `ChatRow.module.css`, which says it plainly:
 *
 * > A gentle wash of light at the leading edge — not a bar. The accent sits softly
 * > on the left and clears to plain surface by 45%, so the open chat reads as lit
 * > rather than bracketed.
 *
 * The phone was doing the opposite — a full-width filled slab with an 8dp radius,
 * which is a bracket, and a heavy one. Five of them stacked down a narrow panel is
 * most of what made this menu feel bulky, and none of it looked like the product it
 * belongs to.
 *
 * Full-bleed rather than inset, because a wash that stops short of the edge is a
 * shape again. The clearing point is the desktop's 45%.
 */
@Composable
private fun Modifier.leadingWash(active: Boolean): Modifier {
    if (!active) return this

    val accent = AnodexTheme.colors.accentSoft
    return this.background(
        Brush.horizontalGradient(0f to accent, WASH_CLEARS_AT to Color.Transparent)
    )
}

/** Where the accent has faded out entirely. The desktop's figure, kept identical. */
private const val WASH_CLEARS_AT = 0.45f

/** A conversation, indented when it is filed under a workspace. */
@Composable
private fun ConversationRow(
    conversation: ConversationSummary,
    active: Boolean,
    indented: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = conversation.title,
        style = AnodexTheme.type.body,
        color = if (active) AnodexTheme.colors.text else AnodexTheme.colors.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            // The open conversation was marked by text colour alone, which is a
            // half-step of grey in a list of greys. It gets the same wash as an open
            // destination, so one thing means "this is what you are looking at"
            // throughout the panel.
            .leadingWash(active)
            .heightIn(min = Touch.minTarget)
            .clickable(onClick = onClick)
            .padding(
                start = if (indented) INDENT else Spacing.x4,
                end = Spacing.x4,
                top = Spacing.x3,
                bottom = Spacing.x3,
            ),
    )
}

/** The way out of a shortened list, into the full index. */
@Composable
private fun MoreRow(text: String, indented: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = AnodexTheme.type.label,
        color = AnodexTheme.colors.accentInk,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Touch.minTarget)
            .clickable(onClick = onClick)
            .padding(
                start = if (indented) INDENT else Spacing.x4,
                end = Spacing.x4,
                top = Spacing.x3,
                bottom = Spacing.x3,
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
            .leadingWash(selected)
            .heightIn(min = Touch.minTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.x4),
        verticalAlignment = Alignment.CenterVertically,
        // 12dp, which is what the desktop puts between an icon and its label. The
        // 16dp here was a step wider and read as loose down a column of five.
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        AnodexIcon(destination.icon, size = 19.dp, tint = tint, contentDescription = null)
        Text(destination.label, style = type.body, color = tint, modifier = Modifier.weight(1f))

        if (badge > 0) {
            Box(
                Modifier
                    .heightIn(min = 17.dp)
                    .clip(Radii.pill)
                    // Ink rather than the base amber: the count is drawn in the
                    // page's own ground colour, and that on #F5A623 measures 1.79:1
                    // in the light theme — a badge nobody can read is a badge that
                    // may as well not be there.
                    .background(colors.warnInk)
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
        Hairline()
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
                        .background(if (connected) colors.successInk else colors.textFaint)
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
 *
 * It now counts plain chats only. The workspaces above have their own budget, and a
 * single cap over both would have let one busy workspace eat the whole panel.
 */
private const val RECENT_LIMIT = 6

/**
 * Three, per workspace.
 *
 * The workspaces are a place to recognise the thing you were doing, not a second
 * copy of the index. Three of them plus their headings is already most of what fits
 * above the footer, and the overflow row underneath goes to the full list.
 */
private const val WORKSPACE_CHAT_LIMIT = 3

/**
 * How far a chat sits inside its workspace.
 *
 * The list's own inset, plus the folder icon, plus the gap after it — so a chat
 * starts where its workspace's *name* starts rather than at some indent chosen by
 * eye. The nesting is the only thing saying these chats can edit real files.
 */
private val INDENT = 44.dp

/** A workspace as the drawer lists it: the folder, and the chats filed under it. */
internal data class DrawerWorkspace(
    val id: String,
    val name: String,
    val conversations: List<ConversationSummary>,
)

/** The drawer's lower half, once the conversations have been filed. */
internal data class DrawerSections(
    val workspaces: List<DrawerWorkspace>,
    /** Only the ones belonging to no workspace. */
    val chats: List<ConversationSummary>,
    val totalMine: Int,
    val machineMade: Int,
)

/**
 * Filing the conversations the way the desktop's sidebar does.
 *
 * The drawer used to show one row for the active project and then every conversation
 * under a single CHATS heading, which put a chat that edits real files in the list
 * headed "talking, not working" — while [dev.anodex.mobile.ui.screens.groupConversations],
 * three screens away, filed the same conversation under its workspace. Two lists in
 * one app disagreeing about what a conversation *is*.
 *
 * `projectId` was already carried on the summary for exactly this, and already says
 * so in its own doc comment. This is the drawer finally reading it.
 */
internal fun drawerSections(
    conversations: List<ConversationSummary>,
    projects: List<Project>,
    activeProjectId: String?,
): DrawerSections {
    val mine = conversations.filter { it.isMine }
    val names = projects.associate { it.id to it.name }

    val byProject = mine.filter { it.projectId != null }
        .groupBy { it.projectId!! }
        .mapValues { (_, items) -> items.sortedByDescending { it.updatedAtEpochMs } }

    val ids = buildList {
        // The workspace the computer is in comes first, and is listed even when it
        // holds nothing yet: it is the answer to "where would a message land", which
        // does not stop being worth showing because no chat has been started there.
        // Unless the phone has never heard of it either — then there is nothing to
        // name it with, and a row reading "Project 0" is worse than no row.
        if (activeProjectId != null && names.containsKey(activeProjectId)) add(activeProjectId)
        val first = toSet()
        addAll(
            byProject.entries
                .filter { it.key !in first }
                .sortedByDescending { entry -> entry.value.maxOf { it.updatedAtEpochMs } }
                .map { it.key }
        )
    }

    return DrawerSections(
        workspaces = ids.map { id ->
            DrawerWorkspace(
                id = id,
                // Labelled by name where the phone knows it. A project added on the
                // computer since this list was fetched still gets its own row rather
                // than having its chats fall in with the plain ones, which would say
                // they cannot touch files.
                name = names[id] ?: "Project",
                conversations = byProject[id].orEmpty(),
            )
        },
        chats = mine.filter { it.projectId == null }.sortedByDescending { it.updatedAtEpochMs },
        totalMine = mine.size,
        machineMade = conversations.size - mine.size,
    )
}

@Preview(name = "Drawer", showBackground = true, backgroundColor = 0xFF080808, heightDp = 700)
@Composable
private fun PreviewDrawer() {
    AnodexTheme(darkTheme = true) {
        AppDrawer(
            destination = AppDestination.CHAT,
            onSelect = {},
            conversations = listOf(
                ConversationSummary("1", "Fix the orbit panel jitter", 0, 3, 6, projectId = "p1"),
                ConversationSummary("2", "Why do the scheduler tests fail?", 0, 2, 12, projectId = "p1"),
                ConversationSummary("3", "Weekend reading list", 0, 1, 3),
                ConversationSummary("4", "Rewrite the save format", 0, 4, 9, projectId = "p2"),
            ),
            projects = listOf(
                Project("p1", "Universe Sandbox", "/home/work/universe"),
                Project("p2", "AnodexWeb", "/home/work/web"),
            ),
            activeProjectId = "p1",
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
