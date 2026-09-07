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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.anodex.mobile.chat.ChatMessage
import dev.anodex.mobile.chat.ChatSession
import dev.anodex.mobile.connection.ConnectionState
import dev.anodex.mobile.connection.HostIdentity
import dev.anodex.mobile.connection.ModelStatus
import dev.anodex.mobile.ui.components.AnodexMark
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.components.AppDestination
import dev.anodex.mobile.ui.components.AppDrawer
import dev.anodex.mobile.ui.components.ConnectionHeader
import dev.anodex.mobile.ui.components.PrimaryButton
import dev.anodex.mobile.ui.components.SecondaryButton
import dev.anodex.mobile.agents.AgentRun
import dev.anodex.mobile.ui.screens.AgentsScreen
import dev.anodex.mobile.ui.screens.ChatScreen
import dev.anodex.mobile.ui.screens.ConversationsScreen
import dev.anodex.mobile.ui.screens.ComingSoonScreen
import dev.anodex.mobile.ui.screens.EmailPane
import dev.anodex.mobile.ui.screens.FileScreen
import dev.anodex.mobile.ui.components.UpdateBanner
import dev.anodex.mobile.ui.screens.HostScreen
import dev.anodex.mobile.ui.screens.NotPairedScreen
import dev.anodex.mobile.ui.screens.ManualPairScreen
import dev.anodex.mobile.ui.screens.OfflineScreen
import dev.anodex.mobile.ui.screens.ProjectPickerScreen
import dev.anodex.mobile.ui.screens.ScanScreen
import dev.anodex.mobile.ui.screens.SettingsScreen
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Touch
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
 * The normal app: a host bar, whatever surface is open, and a drawer behind it.
 *
 * This replaced a bottom tab bar. Every assistant worth measuring against has
 * abandoned one — a permanent strip spends a fixed slice of a small screen on
 * navigation nobody does often, and it competed with the composer for the single
 * edge a thumb actually rests on. A drawer costs one tap and gives all of that back,
 * and it has room for the conversation list, which is what people are usually
 * looking for when they navigate at all.
 */
@Composable
private fun ConnectedScaffold(
    state: ConnectionState,
    chat: ChatSession?,
    viewModel: AnodexViewModel,
) {
    val colors = AnodexTheme.colors
    var destination by rememberSaveable { mutableStateOf(AppDestination.CHAT) }
    var drawerOpen by rememberSaveable { mutableStateOf(false) }

    // Within Chat: the conversation is the root and the list is reached from the
    // drawer, so there is no list/detail stack to unwind here any more.
    var choosingProject by rememberSaveable { mutableStateOf(false) }
    var showingHost by rememberSaveable { mutableStateOf(false) }
    var showingAllConversations by rememberSaveable { mutableStateOf(false) }
    var showingSettings by rememberSaveable { mutableStateOf(false) }

    val agentRuns by viewModel.agentRuns.collectAsStateWithLifecycle()
    val agentsLoading by viewModel.agentsLoading.collectAsStateWithLifecycle()
    val busyRunId by viewModel.busyRunId.collectAsStateWithLifecycle()

    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val projectBusy by viewModel.projectBusy.collectAsStateWithLifecycle()
    val projectError by viewModel.projectError.collectAsStateWithLifecycle()

    val conversations by viewModel.conversations.collectAsStateWithLifecycle()

    val waitingAgents = agentRuns.count { it.status == AgentRun.Status.NEEDS_REVIEW }
    val model = (state as? ConnectionState.Connected)?.model
    val newerVersion by viewModel.newerVersion.collectAsStateWithLifecycle()
    val openFile by viewModel.openFile.collectAsStateWithLifecycle()
    val openFileContent by viewModel.openFileContent.collectAsStateWithLifecycle()
    val unreadEmail by viewModel.unreadEmail.collectAsStateWithLifecycle()
    val personalityState by viewModel.personalities.collectAsStateWithLifecycle()
    val personalityBusy by viewModel.personalityBusy.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val updateState by viewModel.update.collectAsStateWithLifecycle()
    val updateDismissed by viewModel.updateDismissed.collectAsStateWithLifecycle()

    // Re-read rather than remembered once: granting it happens in system settings, so
    // the app is backgrounded at the moment it changes and comes back to a stale
    // answer otherwise. Keyed on the state so the return from that page re-checks.
    var canInstallUpdates by remember { mutableStateOf(true) }
    LaunchedEffect(updateState) { canInstallUpdates = viewModel.canInstallUpdates() }

    // Asked once at launch, before anything is connected — GitHub is the only source
    // that works when the computer is not reachable, which is exactly when somebody is
    // most likely to be wondering whether their app is current.
    LaunchedEffect(Unit) { viewModel.checkForUpdate() }

    // Fetched when its destination is opened rather than on every connect: a phone
    // that never opens Agents should not be polling the computer for them. The
    // conversation list is refreshed whenever the drawer opens, since that is the
    // only place it is shown.
    LaunchedEffect(destination) {
        if (destination == AppDestination.AGENTS) viewModel.refreshAgentRuns()
    }
    LaunchedEffect(drawerOpen) {
        if (drawerOpen) viewModel.refreshConversations()
    }

    // Back unwinds one step at a time, in the order the user got here.
    BackHandler(
        enabled = drawerOpen || choosingProject || showingHost || showingSettings ||
            showingAllConversations || destination != AppDestination.CHAT
    ) {
        when {
            drawerOpen -> drawerOpen = false
            showingSettings -> showingSettings = false
            showingHost -> showingHost = false
            showingAllConversations -> showingAllConversations = false
            choosingProject -> choosingProject = false
            else -> destination = AppDestination.CHAT
        }
    }

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

    if (showingHost) {
        HostScreen(
            state = state,
            newerVersion = newerVersion,
            installedVersion = BuildConfig.VERSION_NAME,
            activeProjectName = projects.active?.name,
            onChooseProject = {
                viewModel.refreshProjects()
                choosingProject = true
            },
            onUnpair = viewModel::unpair,
            modifier = Modifier.safeDrawingPadding(),
        )
        return
    }

    if (showingAllConversations) {
        ConversationsScreen(
            conversations = conversations,
            loading = false,
            activeId = chat?.conversationId,
            onOpen = {
                viewModel.openConversation(it)
                showingAllConversations = false
                destination = AppDestination.CHAT
            },
            onNewChat = {
                viewModel.newConversation()
                showingAllConversations = false
                destination = AppDestination.CHAT
            },
            projectNames = remember(projects) { projects.projects.associate { it.id to it.name } },
            modifier = Modifier.safeDrawingPadding(),
        )
        return
    }

    if (showingSettings) {
        SettingsScreen(
            installedVersion = BuildConfig.VERSION_NAME,
            onClose = { showingSettings = false },
            personalities = personalityState.personalities,
            activePersonalityId = personalityState.active,
            busy = personalityBusy,
            onSelectPersonality = viewModel::setPersonality,
            hostName = hostNameOf(state),
            hostStatus = hostDetailOf(state, projects.active?.name),
            onOpenHost = {
                showingSettings = false
                showingHost = true
            },
            newerVersion = newerVersion,
            modifier = Modifier.safeDrawingPadding(),
        )
        return
    }

    if (drawerOpen) {
        AppDrawer(
            destination = destination,
            onSelect = {
                destination = it
                drawerOpen = false
            },
            conversations = conversations,
            activeConversationId = chat?.conversationId,
            onOpenConversation = {
                viewModel.openConversation(it)
                destination = AppDestination.CHAT
                drawerOpen = false
            },
            onNewChat = {
                viewModel.newConversation()
                destination = AppDestination.CHAT
                drawerOpen = false
            },
            onOpenAllConversations = {
                drawerOpen = false
                showingAllConversations = true
            },
            onClose = { drawerOpen = false },
            hostName = hostNameOf(state) ?: "Not paired",
            hostDetail = hostDetailOf(state, projects.active?.name),
            connected = state is ConnectionState.Connected,
            onOpenHost = {
                drawerOpen = false
                showingHost = true
            },
            onOpenSettings = {
                drawerOpen = false
                showingSettings = true
            },
            agentBadge = waitingAgents,
            emailBadge = unreadEmail,
            modifier = Modifier.safeDrawingPadding(),
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.bgApp).safeDrawingPadding()) {
        // Inside a conversation the title takes the top line and the host shrinks to
        // its dot: you already know which computer, and what you are reading is the
        // conversation. Everywhere else the host bar is the most useful thing there.
        val conversationTitle = chat?.let { session ->
            session.existingTitle?.takeIf { it.isNotBlank() }
                ?: messagesTitle(session)
        }

        if (destination == AppDestination.CHAT && conversationTitle != null) {
            ChatHeader(
                title = conversationTitle,
                connected = state is ConnectionState.Connected,
                onOpenDrawer = { drawerOpen = true },
            )
        } else {
            ConnectionHeader(
                state = state,
                onOpenDrawer = { drawerOpen = true },
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))

        // Under the header on every screen rather than inside chat: a newer app is
        // not a chat concern, and the previous version of this notice lived two taps
        // in on the host screen, where it went unseen through an entire release.
        if (!updateDismissed) {
            UpdateBanner(
                state = updateState,
                canInstall = canInstallUpdates,
                onInstall = viewModel::installUpdate,
                onGrantInstall = {
                    canInstallUpdates = false
                    context.startActivity(viewModel.installPermissionIntent())
                },
                onDismiss = viewModel::dismissUpdate,
            )
        }

        Box(Modifier.weight(1f)) {
            when (destination) {
                AppDestination.CHAT -> ChatPane(
                    chat,
                    viewModel,
                    model,
                    // Named on the empty screen, because which computer is awake is
                    // the one thing no other assistant can put there.
                    hostLine = hostNameOf(state)?.let { "$it is awake and listening" },
                )

                AppDestination.AGENTS -> AgentsScreen(
                    runs = agentRuns,
                    loading = agentsLoading,
                    busyRunId = busyRunId,
                    onApprove = viewModel::approvePlan,
                    onReject = viewModel::rejectPlan,
                    onStop = viewModel::stopAgentRun,
                    onOpenConversation = {
                        viewModel.openConversation(it)
                        destination = AppDestination.CHAT
                    },
                )

                AppDestination.EMAIL -> EmailPane(viewModel)

                AppDestination.WORKSPACE -> ComingSoonScreen(
                    title = "Workspace",
                    icon = AnodexIcon.FOLDER,
                    description = "The files in the project your computer has open — to read " +
                        "from here, and to hand to a turn.",
                )

                AppDestination.SCHEDULER -> ComingSoonScreen(
                    title = "Scheduler",
                    icon = AnodexIcon.CLOCK,
                    description = "What your computer is set to do on its own, and when it " +
                        "last did it.",
                )
            }
        }
    }
}

/**
 * The conversation's own bar: a way out, what this is, and whether the computer is
 * still there.
 *
 * The status dot survives from the host bar because it is the one thing that can
 * change under you mid-conversation. Everything else about the machine — the model,
 * the context, the project — is a between-turns concern and moved to Host.
 */
@Composable
private fun ChatHeader(title: String, connected: Boolean, onOpenDrawer: () -> Unit) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bgSurface)
            .padding(horizontal = Spacing.x2, vertical = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        Box(
            modifier = Modifier
                .size(Touch.minTarget)
                .clip(Radii.md)
                .clickable(onClick = onOpenDrawer),
            contentAlignment = Alignment.Center,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.width(18.dp).height(1.5.dp).clip(Radii.pill).background(colors.textMuted))
                Box(Modifier.width(13.dp).height(1.5.dp).clip(Radii.pill).background(colors.textMuted))
                Box(Modifier.width(18.dp).height(1.5.dp).clip(Radii.pill).background(colors.textMuted))
            }
        }

        Text(
            text = title,
            style = type.bodyEmphasis,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Box(
            Modifier
                .padding(end = Spacing.x3)
                .size(6.dp)
                .clip(CircleShape)
                .background(if (connected) colors.success else colors.warn)
        )
    }
}

/** The first thing the user said, when the computer has not titled it yet. */
private fun messagesTitle(session: ChatSession): String? =
    session.messages.value
        .firstOrNull { it.role == ChatMessage.Role.USER && it.text.isNotBlank() }
        ?.text
        ?.lineSequence()
        ?.firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.take(60)

private fun hostNameOf(state: ConnectionState): String? = when (state) {
    is ConnectionState.Connected -> state.host.displayName
    is ConnectionState.Reconnecting -> state.host.displayName
    is ConnectionState.Offline -> state.host.displayName
    ConnectionState.Unpaired -> null
}

/** "Connected · Bench" — what the computer is, and what it is pointed at. */
private fun hostDetailOf(state: ConnectionState, projectName: String?): String {
    val status = when (state) {
        is ConnectionState.Connected -> "Connected"
        is ConnectionState.Reconnecting -> "Reconnecting"
        is ConnectionState.Offline -> "Offline"
        ConnectionState.Unpaired -> "Not paired"
    }
    return if (projectName != null) "$status · $projectName" else status
}

/** The open conversation, or an honest placeholder while the socket is down. */
@Composable
private fun ChatPane(
    chat: ChatSession?,
    viewModel: AnodexViewModel,
    model: ModelStatus?,
    hostLine: String?,
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
        hostLine = hostLine,
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
    val host = HostIdentity(id = "preview", displayName = "STUDIO-PC")

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
