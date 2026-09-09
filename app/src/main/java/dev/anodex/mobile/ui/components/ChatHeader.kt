package dev.anodex.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch

/**
 * What the machine is doing, for the panel behind the header's status button.
 *
 * Everything here was previously two taps away on the Host screen, and the argument
 * for putting it there was that the model, the context and the project are
 * between-turns concerns. That argument was about a *permanent strip* — a bar of
 * machine facts sitting over a conversation, spending screen on something you rarely
 * need mid-sentence. A panel that appears when asked costs nothing when it is not,
 * so the trade that sent these to Host does not apply to it.
 */
data class HostStatus(
    val hostName: String?,
    /** "Connected", "Reconnecting", "Offline", "Not paired". */
    val connection: String,
    val connected: Boolean,
    val workspaceName: String?,
    val folderPath: String?,
    val modelName: String?,
    val contextUsedTokens: Int = 0,
    val contextTotalTokens: Int = 0,
    val conversationId: String?,
)

/**
 * The conversation's own bar: a way out, what this is, and what it is running in.
 *
 * The title floats in the middle on its own ground rather than sitting flush left,
 * and it carries a second line naming the workspace and the machine. That line is
 * this app's whole subject: which workspace a conversation belongs to decides
 * whether the next thing you send edits real files, and which computer is awake
 * decides whether it goes anywhere at all. On a plain chat the workspace is simply
 * absent, and the absence is the point — that one cannot touch anything.
 *
 * It also answers a question the drawer opened. Conversations are filed under their
 * workspace there; until now nothing said which one you were in once you were
 * inside.
 *
 * The controls keep the surface and the title keeps the page: each button sits on
 * its own soft round ground so it reads as a thing to press, while the pill reads as
 * a label that happens to be legible.
 */
@Composable
fun ChatHeader(
    title: String,
    /** The workspace this conversation runs in, or null for a plain chat. */
    workspaceName: String?,
    hostName: String?,
    connected: Boolean,
    status: HostStatus,
    onOpenDrawer: () -> Unit,
    onCopyId: () -> Unit,
    onArchive: () -> Unit,
    /** Null where there is no workspace beside this chat to open. */
    onOpenFiles: (() -> Unit)?,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    var menuOpen by remember { mutableStateOf(false) }
    var statusOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bgApp)
            .padding(horizontal = Spacing.x3, vertical = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        RoundButton(onClick = onOpenDrawer, contentDescription = "Menu") {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.width(18.dp).height(1.5.dp).clip(Radii.pill).background(colors.textMuted))
                Box(Modifier.width(13.dp).height(1.5.dp).clip(Radii.pill).background(colors.textMuted))
                Box(Modifier.width(18.dp).height(1.5.dp).clip(Radii.pill).background(colors.textMuted))
            }
        }

        // Centred in what is left rather than on the screen, which is what it looks
        // like it is doing anyway: there is one control to the left and two to the
        // right, and a title nudged off-centre to satisfy a ruler nobody is holding
        // would only make the row look mis-set.
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = PILL_MAX)
                    .clip(Radii.pill)
                    .background(colors.bgSurface)
                    .padding(horizontal = Spacing.x4, vertical = Spacing.x2),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = type.bodyEmphasis,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // Nothing to say when the phone is not paired and the chat is plain.
                // An empty second line would leave the pill looking like it had
                // failed to load something.
                if (workspaceName != null || hostName != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
                    ) {
                        if (workspaceName != null) {
                            AnodexIcon(
                                AnodexIcon.FOLDER,
                                size = 11.dp,
                                tint = colors.textFaint,
                                contentDescription = "Workspace",
                            )
                            Text(
                                text = workspaceName,
                                style = type.meta,
                                color = colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .padding(end = Spacing.x1),
                            )
                        }

                        if (hostName != null) {
                            AnodexIcon(
                                AnodexIcon.MONITOR,
                                size = 11.dp,
                                tint = colors.textFaint,
                                contentDescription = "Computer",
                            )
                            Text(
                                text = hostName,
                                style = type.meta,
                                color = colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            // Beside the machine's name, because it is that name's
                            // fact and not the conversation's. It is also the only
                            // thing up here that can change while you are reading.
                            StatusDot(
                                colour = if (connected) colors.success else colors.warn,
                                running = !connected,
                                size = 6.dp,
                            )
                        }
                    }
                }
            }
        }

        Box {
            RoundButton(onClick = { statusOpen = true }, contentDescription = "Computer status") {
                AnodexIcon(AnodexIcon.MONITOR, size = 18.dp, tint = colors.textMuted)
            }

            if (statusOpen) {
                HeaderPopup(onDismiss = { statusOpen = false }) {
                    StatusPanel(status, onCopyId = {
                        statusOpen = false
                        onCopyId()
                    })
                }
            }
        }

        Box {
            RoundButton(onClick = { menuOpen = true }, contentDescription = "More") {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(3) {
                        Box(Modifier.size(3.dp).clip(CircleShape).background(colors.textMuted))
                    }
                }
            }

            if (menuOpen) {
                HeaderPopup(onDismiss = { menuOpen = false }) {
                    if (onOpenFiles != null) {
                        MenuItem("Open workspace files") {
                            menuOpen = false
                            onOpenFiles()
                        }
                    }
                    MenuItem("Copy conversation ID") {
                        menuOpen = false
                        onCopyId()
                    }
                    // No confirmation. Archiving is reversible and the undo arrives
                    // straight after the tap, which is a better question than one
                    // asked in front of every tap including the nine hundred that
                    // were not mistakes.
                    MenuItem("Archive", danger = true) {
                        menuOpen = false
                        onArchive()
                    }
                }
            }
        }
    }
}

/** One of the header's round controls, all of which are the same object. */
@Composable
private fun RoundButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(Touch.minTarget)
            .clip(CircleShape)
            .background(AnodexTheme.colors.bgSurface)
            .clickable(onClick = onClick, onClickLabel = contentDescription),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * The ground both header panels stand on.
 *
 * A `Popup` rather than Material's `DropdownMenu` for the reason every other surface
 * in this app is hand-built: the Material one arrives with its own corner scale,
 * elevation tint and ripple, and would read as a menu borrowed from a different
 * application. `Popup` is a positioning primitive and brings no appearance at all.
 */
@Composable
private fun HeaderPopup(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val colors = AnodexTheme.colors

    // Converted here rather than written as a pixel constant: `Popup` takes pixels,
    // and a number that clears the button on one screen sits on top of it on the
    // next one along.
    val drop = with(LocalDensity.current) { (Touch.minTarget + Spacing.x1).roundToPx() }

    Popup(
        alignment = Alignment.TopEnd,
        // Below the button it belongs to, right edges lined up.
        offset = IntOffset(0, drop),
        onDismissRequest = onDismiss,
        // Focusable so the back button and a tap outside both close it, which is
        // what makes it dismissable without a scrim of its own.
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 200.dp, max = 300.dp)
                .border(1.dp, colors.border, Radii.lg)
                .background(colors.bgElevated, Radii.lg)
                .padding(vertical = Spacing.x2),
        ) {
            content()
        }
    }
}

/** One line of the ⋮ menu. */
@Composable
private fun MenuItem(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        text = label,
        style = AnodexTheme.type.body,
        color = if (danger) AnodexTheme.colors.danger else AnodexTheme.colors.text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Touch.minTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
    )
}

/** What the computer is, where it is pointed, and what it is running. */
@Composable
private fun StatusPanel(status: HostStatus, onCopyId: () -> Unit) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(
        modifier = Modifier.padding(horizontal = Spacing.x4, vertical = Spacing.x2),
        verticalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            StatusDot(
                colour = if (status.connected) colors.success else colors.warn,
                running = !status.connected,
                size = 6.dp,
            )
            Text(
                text = status.hostName?.let { "$it · ${status.connection}" } ?: status.connection,
                style = type.bodyEmphasis,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // The path, not just the name. Two projects called "app" is the normal case
        // on any machine that has been used for a while, and the folder is the only
        // thing that tells them apart.
        StatusRow("Workspace", status.workspaceName ?: "None — this chat cannot touch files")
        status.folderPath?.takeIf { it.isNotBlank() }?.let { StatusRow("Folder", it) }

        status.modelName?.let { model ->
            StatusRow("Model", model)
            if (status.contextTotalTokens > 0) {
                val percent = (status.contextUsedTokens * 100) / status.contextTotalTokens
                StatusRow("Context", "$percent% of ${status.contextTotalTokens / 1000}k used")
            }
        }

        status.conversationId?.let { id ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radii.md)
                    .clickable(onClick = onCopyId, onClickLabel = "Copy conversation ID")
                    .padding(vertical = Spacing.x1),
            ) {
                Text("Conversation · tap to copy", style = type.badge, color = colors.textFaint)
                Text(
                    text = id,
                    style = type.meta,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Column {
        Text(label, style = AnodexTheme.type.badge, color = AnodexTheme.colors.textFaint)
        Text(
            text = value,
            style = AnodexTheme.type.meta,
            color = AnodexTheme.colors.textMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * How wide the title pill is allowed to get.
 *
 * Left to itself it grows to whatever the row has spare, and a long title then runs
 * from one button to the other with no air on either side — which stops reading as
 * something floating in the middle and starts reading as a bar.
 */
private val PILL_MAX = 260.dp

@Preview(name = "Chat header", showBackground = true, backgroundColor = 0xFF0B0B0B)
@Composable
private fun PreviewChatHeader() {
    AnodexTheme(darkTheme = true) {
        ChatHeader(
            title = "Fix the orbit panel jitter",
            workspaceName = "Universe Sandbox",
            hostName = "STUDIO-PC",
            connected = true,
            status = HostStatus(
                hostName = "STUDIO-PC",
                connection = "Connected",
                connected = true,
                workspaceName = "Universe Sandbox",
                folderPath = "C:\\Users\\Owner\\Desktop\\Universe Sandbox",
                modelName = "qwen2.5-coder-32b",
                contextUsedTokens = 18_000,
                contextTotalTokens = 32_000,
                conversationId = "019fb93b-8075-7342-8ea5-b41c3100",
            ),
            onOpenDrawer = {},
            onCopyId = {},
            onArchive = {},
            onOpenFiles = {},
        )
    }
}

@Preview(name = "Chat header · plain chat", showBackground = true, backgroundColor = 0xFF0B0B0B)
@Composable
private fun PreviewPlainChatHeader() {
    AnodexTheme(darkTheme = true) {
        ChatHeader(
            title = "Weekend reading list",
            workspaceName = null,
            hostName = "STUDIO-PC",
            connected = false,
            status = HostStatus(
                hostName = "STUDIO-PC",
                connection = "Reconnecting",
                connected = false,
                workspaceName = null,
                folderPath = null,
                modelName = null,
                conversationId = null,
            ),
            onOpenDrawer = {},
            onCopyId = {},
            onArchive = {},
            onOpenFiles = null,
        )
    }
}
