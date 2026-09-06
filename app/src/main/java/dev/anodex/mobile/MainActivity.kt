package dev.anodex.mobile

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.anodex.mobile.chat.ChatSession
import dev.anodex.mobile.connection.ConnectionState
import dev.anodex.mobile.connection.HostIdentity
import dev.anodex.mobile.connection.ModelStatus
import dev.anodex.mobile.ui.components.AnodexMark
import dev.anodex.mobile.ui.components.AppTab
import dev.anodex.mobile.ui.components.BottomTabs
import dev.anodex.mobile.ui.components.ConnectionHeader
import dev.anodex.mobile.ui.components.PrimaryButton
import dev.anodex.mobile.ui.components.SecondaryButton
import dev.anodex.mobile.agents.AgentRun
import dev.anodex.mobile.ui.screens.AgentsScreen
import dev.anodex.mobile.ui.screens.ChatScreen
import dev.anodex.mobile.ui.screens.ConversationsScreen
import dev.anodex.mobile.ui.screens.EmailPane
import dev.anodex.mobile.ui.screens.FileScreen
import dev.anodex.mobile.ui.screens.HostScreen
import dev.anodex.mobile.ui.screens.NotPairedScreen
import dev.anodex.mobile.ui.screens.ManualPairScreen
import dev.anodex.mobile.ui.screens.OfflineScreen
import dev.anodex.mobile.ui.screens.ProjectPickerScreen
import dev.anodex.mobile.ui.screens.ScanScreen
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
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
        // Installed before anything else runs, so a crash during start-up is caught
        // too. Start-up is exactly when the crashes that matter happen: a phone that
        // closes itself the moment it is opened cannot be debugged any other way.
        CrashLog.install(this)
        enableEdgeToEdge()

        val crash = CrashLog.read(this)

        setContent {
            AnodexTheme {
                if (crash != null) {
                    CrashReportScreen(
                        report = crash,
                        onDismiss = {
                            CrashLog.clear(this)
                            recreate()
                        },
                    )
                } else {
                    AnodexApp()
                }
            }
        }
    }
}

/**
 * What happened last time, in front of the person who can tell somebody.
 *
 * Shown once, then cleared. It is deliberately the first thing after a crash rather
 * than something buried in a settings page: a crash loop gives the user no path to a
 * settings page, and the report is worthless if it is never read.
 */
@Composable
private fun CrashReportScreen(report: String, onDismiss: () -> Unit) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bgApp)
            .safeDrawingPadding()
            .padding(Spacing.x4),
        verticalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        Text("Anodex closed unexpectedly", style = type.heading, color = colors.text)
        Text(
            text = "This is what went wrong. Copy it and send it on \u2014 it is the only " +
                "record of the crash.",
            style = type.body,
            color = colors.textMuted,
        )

        Text(
            text = report,
            style = type.mono,
            color = colors.text,
            softWrap = false,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(Radii.md)
                .background(colors.bgSurface2)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(Spacing.x3),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
            PrimaryButton(
                label = "Copy",
                onClick = { clipboard.setText(AnnotatedString(report)) },
            )
            SecondaryButton(label = "Continue", onClick = onDismiss)
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
    val connectionHint by viewModel.connectionHint.collectAsStateWithLifecycle()

    // Asked for the first time something arrived that could not be shown, rather
    // than at launch. A permission prompt makes sense when there is a concrete
    // thing it would have told you about, and reads as arbitrary before that.
    val needsNotificationPermission by
        viewModel.needsNotificationPermission.collectAsStateWithLifecycle()
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.notificationPermissionHandled() }

    LaunchedEffect(needsNotificationPermission) {
        if (needsNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (needsNotificationPermission) {
            viewModel.notificationPermissionHandled()
        }
    }
    val pairingError by viewModel.pairingError.collectAsStateWithLifecycle()
    var scanning by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    val manualState by viewModel.manualState.collectAsStateWithLifecycle()

    // Pairing succeeds on a background coroutine, so the screen that started it has
    // to stand down when it does. Without this the app sat on the pairing screen
    // after a *successful* pair — connected, working, and looking like nothing had
    // happened, until it was force-closed and reopened.
    val paired by viewModel.paired.collectAsStateWithLifecycle()
    LaunchedEffect(paired) {
        if (paired != null) {
            typing = false
            scanning = false
        }
    }

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
            hint = connectionHint,
        )

        else -> ConnectedScaffold(current, chat, viewModel)
    }
}

/**
 * The normal app: the host bar, the open surface, and the tab bar under it.
 *
 * Every surface used to be a full-screen takeover reached from a button on some
 * other screen and left with the back gesture, so "where am I" was something the
 * user had to hold in their head, and agent runs sat two taps deep behind the
 * conversation list. The four destinations are fixed and always visible now,
 * which is what the design sample asks for and what a phone app is expected to do.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectedScaffold(
    state: ConnectionState,
    chat: ChatSession?,
    viewModel: AnodexViewModel,
) {
    val colors = AnodexTheme.colors
    var tab by rememberSaveable { mutableStateOf(AppTab.CHATS) }

    // Within Chats: the list is the root and a conversation is a drill-down, so
    // back goes list-wards rather than straight out of the app.
    var readingChat by rememberSaveable { mutableStateOf(true) }
    var choosingProject by rememberSaveable { mutableStateOf(false) }

    val agentRuns by viewModel.agentRuns.collectAsStateWithLifecycle()
    val agentsLoading by viewModel.agentsLoading.collectAsStateWithLifecycle()
    val busyRunId by viewModel.busyRunId.collectAsStateWithLifecycle()

    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val projectBusy by viewModel.projectBusy.collectAsStateWithLifecycle()
    val projectError by viewModel.projectError.collectAsStateWithLifecycle()

    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val loadingConversations by viewModel.loadingConversations.collectAsStateWithLifecycle()

    val waitingAgents = agentRuns.count { it.status == AgentRun.Status.NEEDS_REVIEW }
    val model = (state as? ConnectionState.Connected)?.model
    val openFile by viewModel.openFile.collectAsStateWithLifecycle()
    val openFileContent by viewModel.openFileContent.collectAsStateWithLifecycle()
    val projectNames = remember(projects) {
        projects.projects.associate { it.id to it.name }
    }
    val unreadEmail by viewModel.unreadEmail.collectAsStateWithLifecycle()

    // Fetched when its tab is opened rather than on every connect: a phone that
    // never opens Agents should not be polling the desktop for them.
    LaunchedEffect(tab) {
        when (tab) {
            AppTab.CHATS -> viewModel.refreshConversations()
            AppTab.AGENTS -> viewModel.refreshAgentRuns()
            else -> Unit
        }
    }

    // Back unwinds one step at a time, in the order the user got here: out of the
    // project sheet, out of a conversation, then back to Chats from another tab.
    // Only then does it leave the app.
    BackHandler(enabled = choosingProject || tab != AppTab.CHATS || !readingChat) {
        when {
            choosingProject -> choosingProject = false
            tab != AppTab.CHATS -> tab = AppTab.CHATS
            else -> readingChat = true
        }
    }

    // Above the tabs rather than inside one: a file is reached from a tool row in
    // the transcript, and returning to that transcript is the only thing anybody
    // wants to do next.
    if (openFile != null) {
        BackHandler { viewModel.closeWorkspaceFile() }
        FileScreen(
            path = openFile.orEmpty(),
            content = openFileContent,
            loading = openFileContent == null,
            onClose = viewModel::closeWorkspaceFile,
            modifier = Modifier.safeDrawingPadding(),
        )
        return
    }

    if (choosingProject) {
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

    Column(modifier = Modifier.fillMaxSize().background(colors.bgApp).safeDrawingPadding()) {
        ConnectionHeader(state = state)
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))

        Box(Modifier.weight(1f)) {
            when (tab) {
                AppTab.CHATS ->
                    if (readingChat) {
                        ChatPane(chat, viewModel, model)
                    } else {
                        ConversationsScreen(
                            conversations = conversations,
                            loading = loadingConversations,
                            activeId = chat?.conversationId,
                            onOpen = {
                                viewModel.openConversation(it)
                                readingChat = true
                            },
                            onNewChat = {
                                viewModel.newConversation()
                                readingChat = true
                            },
                            activeProjectName = projects.active?.name,
                            onChooseProject = {
                                viewModel.refreshProjects()
                                choosingProject = true
                            },
                            projectNames = projectNames,
                        )
                    }

                AppTab.AGENTS -> AgentsScreen(
                    runs = agentRuns,
                    loading = agentsLoading,
                    busyRunId = busyRunId,
                    onApprove = viewModel::approvePlan,
                    onReject = viewModel::rejectPlan,
                    onStop = viewModel::stopAgentRun,
                    onOpenConversation = {
                        viewModel.openConversation(it)
                        tab = AppTab.CHATS
                        readingChat = true
                    },
                )

                AppTab.EMAIL -> EmailPane(viewModel)

                AppTab.HOST -> HostScreen(
                    state = state,
                    activeProjectName = projects.active?.name,
                    onChooseProject = {
                        viewModel.refreshProjects()
                        choosingProject = true
                    },
                    onUnpair = viewModel::unpair,
                )
            }
        }

        // Hidden while the keyboard is up. Four destinations stacked on top of a
        // software keyboard is most of a small phone's remaining height spent on
        // navigation the user is demonstrably not doing right now.
        if (!WindowInsets.isImeVisible) {
            BottomTabs(
                selected = tab,
                onSelect = { next ->
                    // Tapping Chats while already reading one goes back to the list,
                    // which is the standard "tap the active tab to go up" gesture.
                    if (next == tab && next == AppTab.CHATS) readingChat = false
                    tab = next
                },
                agentBadge = waitingAgents,
                emailBadge = unreadEmail,
            )
        }
    }
}

/** The open conversation, or an honest placeholder while the socket is down. */
@Composable
private fun ChatPane(
    chat: ChatSession?,
    viewModel: AnodexViewModel,
    model: ModelStatus?,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    if (chat == null) {
        // Reconnecting: the host bar already says so, and replacing the transcript
        // with a spinner would throw away what the user was reading over a
        // two-second blip.
        Column(
            modifier = Modifier.fillMaxSize().padding(Spacing.x6),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Reconnecting\u2026", style = type.body, color = colors.textMuted)
        }
        return
    }

    val messages by chat.messages.collectAsStateWithLifecycle()
    val sending by chat.sending.collectAsStateWithLifecycle()
    val error by chat.error.collectAsStateWithLifecycle()
    val approval by chat.approval.collectAsStateWithLifecycle()

    // The notification and the card represent the same pending question. When the
    // card goes - answered here, answered at the computer, or expired - the
    // notification must go too, or it taps into nothing.
    LaunchedEffect(approval) {
        if (approval == null) viewModel.clearApprovalNotification()
    }

    // The desktop declines an unanswered prompt after five minutes so a phone that
    // loses signal cannot wedge a generation. Counting down locally is an estimate
    // of that deadline, not the authority on it - the desktop decides.
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
        onStop = chat::stop,
        approval = approval,
        approvalSecondsRemaining = secondsLeft,
        onApprove = { chat.respondToApproval(approved = true) },
        onDeny = { chat.respondToApproval(approved = false) },
        model = model,
        onOpenFile = viewModel::openWorkspaceFile,
    )
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
