package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.connection.ConnectionState
import dev.anodex.mobile.connection.HostIdentity
import dev.anodex.mobile.connection.ModelStatus
import dev.anodex.mobile.ui.components.DangerButton
import dev.anodex.mobile.ui.components.SecondaryButton
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch

/**
 * The computer, in full.
 *
 * The host bar at the top of every screen is a glance — a dot, a name, a model. This
 * is where the same facts get room to be read: exactly how much context is left in
 * tokens rather than as a two-pixel bar, which project a turn will run against, and
 * the one destructive control in the app.
 *
 * It exists because the phone is not the thing doing the work, and an app that never
 * says so leaves the user guessing which machine they are actually driving.
 */
@Composable
fun HostScreen(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    activeProjectName: String? = null,
    onChooseProject: (() -> Unit)? = null,
    onUnpair: (() -> Unit)? = null,
    /** The build the computer expects, when this phone is behind it. Null if not. */
    newerVersion: String? = null,
    installedVersion: String = "",
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgApp)
            .verticalScroll(rememberScrollState())
            .padding(Spacing.x4),
        verticalArrangement = Arrangement.spacedBy(Spacing.x4),
    ) {
        Text("Your computer", style = type.heading, color = colors.text)

        if (newerVersion != null) {
            // Stated, not acted on. The app cannot install anything and should not
            // pretend it can — a button that turns out to mean "go and find a file"
            // is worse than a sentence that says so.
            Card {
                Text("A newer app is available", style = type.bodyEmphasis, color = colors.text)
                Text(
                    text = "Your computer ships with $newerVersion and this phone is on " +
                        "$installedVersion. Install the newer one over the top — your " +
                        "pairing is kept.",
                    style = type.meta,
                    color = colors.textMuted,
                )
            }
        }

        Card {
            Field("Machine", hostName(state) ?: "Not paired")
            Field("Connection", connectionLine(state))
        }

        val model = (state as? ConnectionState.Connected)?.model
        Card {
            if (model == null) {
                // Distinguished from "not connected" on purpose: a reachable computer
                // with nothing loaded cannot answer either, and the remedy is at the
                // machine rather than on the phone.
                Field("Model", "None loaded")
                Text(
                    text = "Load a model on the computer. Nothing can run until one is.",
                    style = type.meta,
                    color = colors.textFaint,
                )
            } else {
                Field("Model", model.name)
                Field("Context", contextLine(model))

                val fraction = model.contextFraction
                if (fraction != null) {
                    Meter(fraction)
                    if (fraction > 0.85f) {
                        // Said before the conversation starts dropping its own history,
                        // rather than after the model appears to forget something.
                        Text(
                            text = "Nearly full. The oldest turns will start being " +
                                "summarised away.",
                            style = type.meta,
                            color = colors.warn,
                        )
                    }
                }
            }
        }

        Card {
            Field("Working in", activeProjectName ?: "No project — plain chat")
            Text(
                text = if (activeProjectName != null) {
                    "Turns can read and write files in this project."
                } else {
                    // The single most consequential setting on the phone, and invisible
                    // until something is asked that needs a project.
                    "Without a project Anodex is only talking. Pick one to let it edit " +
                        "real files."
                },
                style = type.meta,
                color = colors.textFaint,
            )
            if (onChooseProject != null) {
                SecondaryButton(
                    label = if (activeProjectName != null) "Change project" else "Choose a project",
                    onClick = onChooseProject,
                    modifier = Modifier.padding(top = Spacing.x1),
                )
            }
        }

        if (onUnpair != null) {
            Card {
                Text("Unpair this phone", style = type.bodyEmphasis, color = colors.text)
                Text(
                    text = "Its saved key stops working immediately, and the computer " +
                        "goes back to accepting no one.",
                    style = type.meta,
                    color = colors.textFaint,
                )
                DangerButton(
                    label = "Unpair",
                    onClick = onUnpair,
                    modifier = Modifier.padding(top = Spacing.x1),
                )
            }
        }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    val colors = AnodexTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, Radii.lg)
            .background(colors.bgSurface, Radii.lg)
            .padding(Spacing.x4),
        verticalArrangement = Arrangement.spacedBy(Spacing.x2),
        content = content,
    )
}

/** A label and its value on one line, the label fixed-width so the values line up. */
@Composable
private fun Field(label: String, value: String) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = Touch.minTarget / 2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        Text(label, style = type.meta, color = colors.textFaint)
        Text(
            text = value,
            style = type.label,
            color = colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The context bar, at a size that can actually be read rather than glanced at. */
@Composable
private fun Meter(fraction: Float) {
    val colors = AnodexTheme.colors

    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 4.dp, max = 4.dp)
            .background(colors.border, Radii.pill),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .heightIn(min = 4.dp, max = 4.dp)
                .background(if (fraction > 0.85f) colors.warn else colors.accent, Radii.pill),
        )
    }
}

private fun hostName(state: ConnectionState): String? = when (state) {
    is ConnectionState.Connected -> state.host.displayName
    is ConnectionState.Reconnecting -> state.host.displayName
    is ConnectionState.Offline -> state.host.displayName
    ConnectionState.Unpaired -> null
}

private fun connectionLine(state: ConnectionState): String = when (state) {
    is ConnectionState.Connected -> "Connected"
    is ConnectionState.Reconnecting -> "Reconnecting — attempt ${state.attempt}"
    is ConnectionState.Offline -> "Offline"
    ConnectionState.Unpaired -> "Not paired"
}

/**
 * Tokens, written out.
 *
 * Thousands are abbreviated because the exact figure changes several times a second
 * during a turn, and a number that never settles cannot be read at all.
 */
private fun contextLine(model: ModelStatus): String {
    if (model.contextTotalTokens <= 0) return "Unknown"
    val percent = ((model.contextFraction ?: 0f) * 100).toInt()
    return "${compact(model.contextUsedTokens)} / ${compact(model.contextTotalTokens)} · $percent%"
}

private fun compact(tokens: Int): String =
    if (tokens < 1_000) tokens.toString() else "${"%.1f".format(tokens / 1000f)}K"

@Preview(name = "Host", showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun PreviewHost() {
    AnodexTheme(darkTheme = true) {
        HostScreen(
            state = ConnectionState.Connected(
                HostIdentity("preview", "STUDIO-PC"),
                ModelStatus("Qwen3-Coder-30B", contextUsedTokens = 13_400, contextTotalTokens = 32_768),
            ),
            activeProjectName = "Universe Sandbox",
            onChooseProject = {},
            onUnpair = {},
        )
    }
}
