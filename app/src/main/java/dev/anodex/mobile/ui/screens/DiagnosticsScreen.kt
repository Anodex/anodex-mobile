package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Spacing

/**
 * What went wrong, and a way to say so.
 *
 * The two things a person needs when the app misbehaves are the text of the failure
 * and somewhere to send it. Both existed and neither was reachable: a crash was shown
 * once on the next launch and then deleted, and the connection's own explanation
 * lived on a screen you only see while it is failing.
 *
 * Nothing here is measured on the phone for the sake of this screen. It shows what
 * the app already knew and used to throw away.
 */
@Composable
fun DiagnosticsScreen(
    hostName: String?,
    connectionStatus: String,
    /** The computer's own explanation, when it gave one. */
    connectionHint: String?,
    lastCrash: String?,
    modifier: Modifier = Modifier,
    onCopyCrash: () -> Unit = {},
    onReportCrash: () -> Unit = {},
    onForgetCrash: () -> Unit = {},
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.x5),
    ) {
        Column {
            Text("Connection", style = type.bodyEmphasis, color = colors.text)
            Field("Computer", hostName ?: "Not paired")
            Field("Status", connectionStatus)
            // Only when there is one. An empty row labelled "Reason" reads as a
            // missing value rather than as nothing being wrong.
            connectionHint?.takeIf { it.isNotBlank() }?.let { Field("Reason", it) }
        }

        Column {
            Text("Last crash", style = type.bodyEmphasis, color = colors.text)

            if (lastCrash == null) {
                Text(
                    text = "None recorded. The app has not crashed since it was installed.",
                    style = type.body,
                    color = colors.textMuted,
                    modifier = Modifier.padding(top = Spacing.x2),
                )
                return@Column
            }

            Text(
                text = "Kept so it can be reported. Check it for anything private before " +
                    "posting — a stack trace can carry a file path or an address.",
                style = type.meta,
                color = colors.textMuted,
                modifier = Modifier.padding(top = Spacing.x2, bottom = Spacing.x3),
            )

            // Monospaced and scrolling sideways rather than wrapped: a stack trace
            // rewrapped to a phone's width stops looking like a stack trace, and the
            // frame names at the end of each line are the part worth reading.
            Text(
                text = lastCrash.trim(),
                style = type.mono,
                color = colors.textMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.bgSurface)
                    .horizontalScroll(rememberScrollState())
                    .padding(Spacing.x3),
            )

            Row(
                modifier = Modifier.padding(top = Spacing.x3),
                horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
            ) {
                Action("Report on GitHub", colors.accent, onReportCrash)
                Action("Copy", colors.textMuted, onCopyCrash)
                Action("Forget", colors.danger, onForgetCrash)
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(Modifier.fillMaxWidth().padding(top = Spacing.x2)) {
        Text(label, style = type.body, color = colors.textMuted, modifier = Modifier.weight(0.35f))
        Text(value, style = type.body, color = colors.text, modifier = Modifier.weight(0.65f))
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
            .padding(horizontal = Spacing.x3, vertical = Spacing.x3),
    )
}
