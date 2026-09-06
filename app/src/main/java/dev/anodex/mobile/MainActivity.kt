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
import androidx.compose.runtime.LaunchedEffect
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
import dev.anodex.mobile.chat.ChatSession
import dev.anodex.mobile.connection.ConnectionState
import dev.anodex.mobile.connection.HostIdentity
import dev.anodex.mobile.connection.ModelStatus
import dev.anodex.mobile.ui.components.AnodexMark
import dev.anodex.mobile.ui.components.ConnectionHeader
import dev.anodex.mobile.ui.components.PrimaryButton
import dev.anodex.mobile.ui.screens.ChatScreen
import dev.anodex.mobile.ui.screens.ConversationsScreen
import dev.anodex.mobile.ui.screens.NotPairedScreen
import dev.anodex.mobile.ui.screens.ManualPairScreen
import dev.anodex.mobile.ui.screens.OfflineScreen
import dev.anodex.mobile.ui.screens.ProjectPickerScreen
import dev.anodex.mobile.ui.screens.ScanScreen
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * Mirrors the desktop's CONFIRMATION_TIMEOUT_MS.
 *
 * Only ever used to draw a countdown. The desktop owns the deadline and enforces it;
 * if the two ever drift, the card is briefly wrong and nothing else is.
 */
private const val APPROVAL_WINDOW_SECONDS = 5 * 60

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

    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val pairingError by viewModel.pairingError.collectAsStateWithLifecycle()
    var scanning by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    val manualState by viewModel.manualState.collectAsStateWithLifecycle()

    if (typing) {
        BackHandler {
            typing = false
            viewModel.cancelManualPairing()
        }
        ManualPairScreen(
            state = manualState,
            error = pairingError,
            onProbe = viewModel::probeManualHost,
            onConfirm = viewModel::confirmManualFingerprint,
            onCancel = {
                typing = false
                viewModel.cancelManualPairing()
            },
        )
        return
    }

    if (scanning) {
        BackHandler { scanning = false }
        ScanScreen(
            onScanned = { payload ->
                scanning = false
                viewModel.completePairing(payload)
            },
            onCancel = { scanning = false },
            pairingError = pairingError,
        )
        return
    }

    when (val current = state) {
        ConnectionState.Unpaired -> NotPairedScreen(
            onScan = { scanning = true },
            onEnterManually = { typing = true },
            onPreviewDesign = { showingDesignPreview = true },
            error = pairingError,
        )

        is ConnectionState.Offline -> OfflineScreen(
            state = current,
            onRetry = viewModel::retry,
            onOpenPairing = viewModel::unpair,
        )

        else -> ConnectedScaffold(current, chat, viewModel)
    }
}

/** The normal app: connection header, then whichever surface is open beneath it. */
@Composable
private fun ConnectedScaffold(
    state: ConnectionState,
    chat: ChatSession?,
    viewModel: AnodexViewModel,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    var showingConversations by remember { mutableStateOf(false) }
    var choosingProject by remember { mutableStateOf(false) }

    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val projectBusy by viewModel.projectBusy.collectAsStateWithLifecycle()
    val projectError by viewModel.projectError.collectAsStateWithLifecycle()

    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val loadingConversations by viewModel.loadingConversations.collectAsStateWithLifecycle()

    if (choosingProject) {
        BackHandler { choosingProject = false }
        ProjectPickerScreen(
            projects = projects.projects,
            activeProjectId = projects.activeProjectId,
            busy = projectBusy,
            error = projectError,
            onSelect = viewModel::setActiveProject,
            onClose = { choosingProject = false },
            modifier = Modifier.safeDrawingPadding(),
        )
        return
    }

    if (showingConversations) {
        BackHandler { showingConversations = false }
        ConversationsScreen(
            conversations = conversations,
            loading = loadingConversations,
            activeId = chat?.conversationId,
            onOpen = {
                viewModel.openConversation(it)
                showingConversations = false
            },
            onNewChat = {
                viewModel.newConversation()
                showingConversations = false
            },
            modifier = Modifier.safeDrawingPadding(),
            activeProjectName = projects.active?.name,
            onChooseProject = {
                viewModel.refreshProjects()
                choosingProject = true
            },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.bgApp).safeDrawingPadding()) {
        ConnectionHeader(
            state = state,
            onOpenConversations = {
                viewModel.refreshConversations()
                showingConversations = true
            },
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))

        if (chat == null) {
            // Reconnecting: the header already says so, and replacing the transcript with a
            // spinner would throw away what the user was reading over a two-second blip.
            Column(
                modifier = Modifier.fillMaxSize().padding(Spacing.x6),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Reconnecting…", style = type.body, color = colors.textMuted)
            }
            return@Column
        }

        val messages by chat.messages.collectAsStateWithLifecycle()
        val sending by chat.sending.collectAsStateWithLifecycle()
        val error by chat.error.collectAsStateWithLifecycle()
        val approval by chat.approval.collectAsStateWithLifecycle()

        // The desktop declines an unanswered prompt after five minutes so a phone
        // that loses signal cannot wedge a generation. Counting down locally is an
        // estimate of that deadline, not the authority on it - the desktop decides.
        var secondsLeft by remember(approval?.id) { mutableStateOf(APPROVAL_WINDOW_SECONDS) }
        LaunchedEffect(approval?.id) {
            if (approval == null) return@LaunchedEffect
            secondsLeft = APPROVAL_WINDOW_SECONDS
            while (secondsLeft > 0) {
                delay(1_000)
                secondsLeft -= 1
            }
        }

        ChatScreen(
            messages = messages,
            sending = sending,
            error = error,
            onSend = chat::send,
            approval = approval,
            approvalSecondsRemaining = secondsLeft,
            onApprove = { chat.respondToApproval(approved = true) },
            onDeny = { chat.respondToApproval(approved = false) },
        )
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
