package dev.anodex.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.connection.ConnectionState
import dev.anodex.mobile.connection.HostIdentity
import dev.anodex.mobile.connection.ModelStatus
import dev.anodex.mobile.ui.components.AnodexMark
import dev.anodex.mobile.ui.components.ConnectionHeader
import dev.anodex.mobile.ui.screens.OfflineScreen
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AnodexTheme {
                ConnectionStateHarness()
            }
        }
    }
}

/**
 * A temporary harness, and labelled as one.
 *
 * There is no transport yet — the protocol contract and the desktop bridge (HANDOFF_REMOTE_MOBILE
 * §4 and §5) come before it. What exists today is the design system and the connection-state model
 * that every later screen hangs off, and this cycles that model by hand so both can be checked on a
 * real device in both themes.
 *
 * **Delete this when the real state holder lands.** It is scaffolding, not a screen.
 */
@Composable
private fun ConnectionStateHarness() {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    val host = HostIdentity(id = "preview", displayName = "MERLIN-PC")

    val states = remember {
        listOf(
            ConnectionState.Connected(
                host = host,
                model = ModelStatus("Qwen3-30B", contextUsedTokens = 3_100, contextTotalTokens = 8_192),
            ),
            ConnectionState.Connected(
                host = host,
                model = ModelStatus("Qwen3-30B", contextUsedTokens = 7_600, contextTotalTokens = 8_192),
            ),
            ConnectionState.Reconnecting(host, attempt = 2, lastSeenEpochMs = null),
            ConnectionState.Offline(
                host = host,
                lastSeenEpochMs = System.currentTimeMillis() - 14 * 60 * 1000,
                networkChanged = true,
            ),
            ConnectionState.Unpaired,
        )
    }
    var index by remember { mutableStateOf(0) }
    val state = states[index]
    val advance = { index = (index + 1) % states.size }

    Box(modifier = Modifier.fillMaxSize().background(colors.bgApp)) {
        when (state) {
            is ConnectionState.Offline -> OfflineScreen(
                state = state,
                onRetry = advance,
                onOpenPairing = advance,
            )

            else -> Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            ) {
                ConnectionHeader(state)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.border),
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.x6),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AnodexMark(size = 64.dp)
                    Text(
                        text = "Design system harness",
                        style = type.heading,
                        color = colors.text,
                        modifier = Modifier.padding(top = Spacing.x4),
                    )
                    Text(
                        text = "Tap to step through the four connection states. " +
                            "No transport yet — this exists to check the tokens on a real screen.",
                        style = type.body,
                        color = colors.textMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = Spacing.x3),
                    )
                    Box(
                        modifier = Modifier
                            .padding(top = Spacing.x6)
                            .clip(Radii.md)
                            .background(colors.accent)
                            .clickable(onClick = advance)
                            .padding(horizontal = Spacing.x6, vertical = Spacing.x4),
                    ) {
                        Text("Next state", style = type.bodyEmphasis, color = colors.textOnAccent)
                    }
                }
            }
        }
    }
}
