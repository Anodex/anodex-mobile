package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.connection.ConnectionState
import dev.anodex.mobile.connection.HostIdentity
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch

/**
 * The screen shown when the desktop has been unreachable for longer than the grace period.
 *
 * Because the phone caches nothing, this is the screen a user sees most often after the chat
 * itself — so it is designed rather than defaulted to a spinner. Three jobs, in order:
 *
 * 1. **Say which machine, plainly.** Not "connection lost"; the name of the computer.
 * 2. **Say why, when that is knowable.** A changed network is the actual cause most of the time,
 *    and naming it turns a generic failure into something the user can act on. Note the wording:
 *    it says the network *changed*, not what it changed to — reading the SSID would cost a
 *    location permission this app deliberately does not ask for (§6.1).
 * 3. **Not be a dead end.** Retry, and a way into pairing, both reachable without a connection.
 */
@Composable
fun OfflineScreen(
    state: ConnectionState.Offline,
    onRetry: () -> Unit,
    onOpenPairing: () -> Unit,
    modifier: Modifier = Modifier,
    nowEpochMs: Long = System.currentTimeMillis(),
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgApp)
            .safeDrawingPadding()
            .padding(horizontal = Spacing.x6),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(colors.danger),
        )

        Text(
            text = "${state.host.displayName} is offline.",
            style = type.title,
            color = colors.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.x5),
        )

        val lastSeen = state.lastSeenEpochMs?.let { relativeLastSeen(it, nowEpochMs) }
        if (lastSeen != null) {
            Text(
                text = "Last seen $lastSeen.",
                style = type.body,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.x2),
            )
        }

        if (state.networkChanged) {
            Text(
                text = "You're on a different network than the one you paired on. " +
                    "Anodex only connects over your local network.",
                style = type.body,
                color = colors.warn,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = Spacing.x5)
                    .fillMaxWidth()
                    .clip(Radii.lg)
                    .background(colors.warnSoft)
                    .padding(Spacing.x4),
            )
        }

        Text(
            text = "Your work stays on your computer, so there's nothing to show until it's " +
                "reachable again. Anodex reconnects on its own the moment it is.",
            style = type.meta,
            color = colors.textFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.x5),
        )

        Row(
            modifier = Modifier.padding(top = Spacing.x8),
            horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
        ) {
            SecondaryAction(label = "Pairing", onClick = onOpenPairing)
            PrimaryAction(label = "Retry", onClick = onRetry)
        }
    }
}

@Composable
private fun PrimaryAction(label: String, onClick: () -> Unit) {
    val colors = AnodexTheme.colors
    Box(
        modifier = Modifier
            .heightIn(min = Touch.minTarget)
            .clip(Radii.md)
            .background(colors.accent)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.x6),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = AnodexTheme.type.bodyEmphasis, color = colors.textOnAccent)
    }
}

@Composable
private fun SecondaryAction(label: String, onClick: () -> Unit) {
    val colors = AnodexTheme.colors
    Box(
        modifier = Modifier
            .heightIn(min = Touch.minTarget)
            .clip(Radii.md)
            .background(colors.bgSurface2)
            .border(1.dp, colors.border, Radii.md)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.x6),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = AnodexTheme.type.bodyEmphasis, color = colors.text)
    }
}

/**
 * "14 minutes ago", "3 hours ago".
 *
 * Coarse on purpose. The user is deciding whether the machine went to sleep or has been off since
 * this morning, and a precise timestamp answers a question nobody asked.
 */
internal fun relativeLastSeen(lastSeenEpochMs: Long, nowEpochMs: Long): String {
    val seconds = ((nowEpochMs - lastSeenEpochMs) / 1000).coerceAtLeast(0)
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        seconds < 60 -> "moments ago"
        minutes == 1L -> "a minute ago"
        minutes < 60 -> "$minutes minutes ago"
        hours == 1L -> "an hour ago"
        hours < 24 -> "$hours hours ago"
        days == 1L -> "yesterday"
        else -> "$days days ago"
    }
}

// --- previews ----------------------------------------------------------------------------------

private val PreviewHost = HostIdentity(id = "h1", displayName = "MERLIN-PC")
private const val PREVIEW_NOW = 1_757_000_000_000L

@Preview(name = "Offline, network changed — dark", showBackground = true, heightDp = 720)
@Composable
private fun PreviewOfflineNetworkChanged() {
    AnodexTheme(darkTheme = true) {
        OfflineScreen(
            state = ConnectionState.Offline(
                host = PreviewHost,
                lastSeenEpochMs = PREVIEW_NOW - 14 * 60 * 1000,
                networkChanged = true,
            ),
            onRetry = {},
            onOpenPairing = {},
            nowEpochMs = PREVIEW_NOW,
        )
    }
}

@Preview(name = "Offline, same network — light", showBackground = true, heightDp = 720)
@Composable
private fun PreviewOfflineSameNetwork() {
    AnodexTheme(darkTheme = false) {
        OfflineScreen(
            state = ConnectionState.Offline(
                host = PreviewHost,
                lastSeenEpochMs = PREVIEW_NOW - 3 * 60 * 60 * 1000,
                networkChanged = false,
            ),
            onRetry = {},
            onOpenPairing = {},
            nowEpochMs = PREVIEW_NOW,
        )
    }
}
