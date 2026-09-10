package dev.anodex.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch
import dev.anodex.mobile.update.UpdateState

/**
 * "There is a newer Anodex", where somebody will actually see it.
 *
 * The previous version of this notice lived on the host screen, two taps in, and the
 * result was exactly what you would expect: an update shipped, the desktop advertised
 * it correctly, and the user reported no notification at all. A notice nobody walks
 * past is not a notice.
 *
 * So it sits above the chat, which is the screen the app opens on. It is dismissible,
 * because an unwanted permanent bar is its own kind of bug — but only for this run of
 * the app, since the point is that the phone and the computer should match.
 */
@Composable
fun UpdateBanner(
    state: UpdateState,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** False when Android has not been told this app may install anything. */
    canInstall: Boolean = true,
    onGrantInstall: () -> Unit = {},
    /** What is running now, so the banner says what the jump actually is. */
    installedVersion: String = "",
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    val release = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.Ready -> state.release
        is UpdateState.Failed -> state.release
        UpdateState.Idle -> null
    } ?: return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.x3, vertical = Spacing.x2)
            .clip(Radii.lg)
            .background(colors.accentSoft)
            .padding(horizontal = Spacing.x3, vertical = Spacing.x3),
        verticalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    // Both ends of the jump. "0.35.0 is available" tells you nothing
                    // about whether that is one build ahead or six, and the version
                    // you are on is otherwise three taps away in Settings.
                    text = if (installedVersion.isBlank()) {
                        "Anodex ${release.version} is available"
                    } else {
                        "Anodex ${release.version} is available · you have $installedVersion"
                    },
                    style = type.bodyEmphasis,
                    color = colors.text,
                )
                Text(
                    text = statusLine(state, canInstall),
                    style = type.meta,
                    color = if (state is UpdateState.Failed) colors.dangerInk else colors.textMuted,
                )
            }

            // No dismiss while a download is running: waving away a bar that is the
            // only thing reporting the progress is a way to lose track of it.
            if (state !is UpdateState.Downloading) {
                Box(
                    modifier = Modifier
                        .size(Touch.minTarget)
                        .clip(Radii.md)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", style = type.body, color = colors.textFaint)
                }
            }
        }

        if (state is UpdateState.Downloading) {
            Progress(state.fraction)
        } else {
            PrimaryButton(
                label = when {
                    !canInstall -> "Allow installs"
                    state is UpdateState.Ready -> "Install"
                    state is UpdateState.Failed -> "Try again"
                    else -> "Update"
                },
                // Android will not let a sideloaded app hand over an APK until the
                // user switches that on for it specifically, and there is no prompt
                // for it — only a page to send them to. Said before the download
                // rather than after it, so the wasted step never happens.
                onClick = if (canInstall) onInstall else onGrantInstall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun statusLine(state: UpdateState, canInstall: Boolean): String = when (state) {
    is UpdateState.Failed -> state.message
    is UpdateState.Downloading -> "Downloading…"
    is UpdateState.Ready -> "Downloaded. Android will ask you to confirm."
    else ->
        if (!canInstall) {
            "Android needs your permission to install app updates."
        } else {
            // The one thing people actually want to know before tapping, because the
            // last several updates did not keep it.
            "Your pairing is kept."
        }
}

@Composable
private fun Progress(fraction: Float) {
    val colors = AnodexTheme.colors

    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 4.dp, max = 4.dp)
            .clip(Radii.pill)
            .background(colors.bgSurface2)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .heightIn(min = 4.dp, max = 4.dp)
                .clip(Radii.pill)
                .background(colors.accent)
        )
    }
}
