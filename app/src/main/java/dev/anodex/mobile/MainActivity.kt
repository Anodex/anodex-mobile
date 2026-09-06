package dev.anodex.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.anodex.mobile.connection.ConnectionState
import dev.anodex.mobile.connection.HostIdentity
import dev.anodex.mobile.connection.ModelStatus
import dev.anodex.mobile.ui.components.AnodexMark
import dev.anodex.mobile.ui.components.ConnectionHeader
import dev.anodex.mobile.ui.components.PrimaryButton
import dev.anodex.mobile.ui.screens.NotPairedScreen
import dev.anodex.mobile.ui.screens.OfflineScreen
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Spacing

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AnodexTheme {
                AnodexApp()
            }
        }
    }
}

/**
 * The app's root, switching on connection state.
 *
 * Because the phone caches nothing, connection state *is* the top-level state — there is no
 * meaningful screen to show for a machine we cannot reach (handoff §6.1, §10.1). So this is the
 * whole navigation graph for now, and it will stay the outermost layer once chat and the rest of
 * the surfaces arrive beneath it.
 */
@Composable
private fun AnodexApp(viewModel: AnodexViewModel = viewModel(factory = AnodexViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showingDesignPreview by remember { mutableStateOf(false) }

    if (showingDesignPreview) {
        BackHandler { showingDesignPreview = false }
        DesignStateHarness(onExit = { showingDesignPreview = false })
        return
    }

    when (val current = state) {
        ConnectionState.Unpaired -> NotPairedScreen(
            // Scanning arrives with the desktop bridge; until then the screen says so rather than
            // offering a button that cannot work.
            onScan = null,
            onPreviewDesign = { showingDesignPreview = true },
        )

        is ConnectionState.Offline -> OfflineScreen(
            state = current,
            onRetry = viewModel::retry,
            onOpenPairing = viewModel::unpair,
        )

        else -> ConnectedScaffold(current)
    }
}

/** The normal app: connection header, then whichever surface is open beneath it. */
@Composable
private fun ConnectedScaffold(state: ConnectionState) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(modifier = Modifier.fillMaxSize().background(colors.bgApp).safeDrawingPadding()) {
        ConnectionHeader(state)
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))

        Column(
            modifier = Modifier.fillMaxSize().padding(Spacing.x6),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Chat lands here",
                style = type.heading,
                color = colors.text,
            )
            Text(
                text = "The transport comes first: the protocol contract, then the desktop bridge. " +
                    "Everything above this line is already real.",
                style = type.body,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.x3),
            )
        }
    }
}

/**
 * A design harness, reachable from the unpaired screen.
 *
 * It exists because there is no transport yet and therefore no way to reach the connected or
 * reconnecting states on a real device. **Delete it once pairing and the transport are real** —
 * it is scaffolding, not a feature, and it is deliberately behind an explicit tap rather than
 * being the app's front door.
 */
@Composable
private fun DesignStateHarness(onExit: () -> Unit) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    val host = HostIdentity(id = "preview", displayName = "MERLIN-PC")

    val states = remember {
        listOf(
            ConnectionState.Connected(
                host,
                ModelStatus("Qwen3-30B", contextUsedTokens = 3_100, contextTotalTokens = 8_192),
            ),
            ConnectionState.Connected(
                host,
                ModelStatus("Qwen3-30B", contextUsedTokens = 7_600, contextTotalTokens = 8_192),
            ),
            ConnectionState.Reconnecting(host, attempt = 2, lastSeenEpochMs = null),
            ConnectionState.Offline(
                host = host,
                lastSeenEpochMs = System.currentTimeMillis() - 14 * 60 * 1000,
                networkChanged = true,
            ),
        )
    }
    var index by remember { mutableStateOf(0) }
    val advance = { index = (index + 1) % states.size }

    Box(Modifier.fillMaxSize().background(colors.bgApp)) {
        when (val state = states[index]) {
            is ConnectionState.Offline ->
                OfflineScreen(state = state, onRetry = advance, onOpenPairing = onExit)

            else -> Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                ConnectionHeader(state)
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
                Column(
                    modifier = Modifier.fillMaxSize().padding(Spacing.x6),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AnodexMark(size = 56.dp)
                    Text(
                        text = "Design states",
                        style = type.heading,
                        color = colors.text,
                        modifier = Modifier.padding(top = Spacing.x4),
                    )
                    Text(
                        text = "Step through each connection state, then switch your phone between " +
                            "dark and light and do it again.",
                        style = type.body,
                        color = colors.textMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = Spacing.x3),
                    )
                    PrimaryButton(
                        label = "Next state",
                        onClick = advance,
                        modifier = Modifier.padding(top = Spacing.x6),
                    )
                }
            }
        }
    }
}
