package dev.anodex.mobile

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.anodex.mobile.agents.AgentRun
import dev.anodex.mobile.chat.ChatMessage
import dev.anodex.mobile.chat.ChatSession
import dev.anodex.mobile.connection.ConnectionState
import dev.anodex.mobile.connection.HostIdentity
import dev.anodex.mobile.connection.ModelStatus
import dev.anodex.mobile.scheduler.ScheduledTask
import dev.anodex.mobile.ui.components.Hairline
import dev.anodex.mobile.ui.components.SCRIM_ALPHA
import dev.anodex.mobile.ui.components.SCRIM_FADE
import dev.anodex.mobile.ui.components.SCRIM_HOLD
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.components.AnodexMark
import dev.anodex.mobile.ui.components.AppDestination
import dev.anodex.mobile.ui.components.AppDrawer
import dev.anodex.mobile.ui.components.UndoBar
import dev.anodex.mobile.ui.components.ChatHeader
import dev.anodex.mobile.ui.components.HostStatus
import dev.anodex.mobile.ui.components.PanelSide
import dev.anodex.mobile.ui.components.SlidingPanel
import dev.anodex.mobile.ui.components.panelEdgeGrab
import dev.anodex.mobile.ui.components.rememberPanelSwipe
import dev.anodex.mobile.ui.components.ConfirmDialog
import dev.anodex.mobile.ui.components.ConnectionHeader
import dev.anodex.mobile.ui.components.PrimaryButton
import dev.anodex.mobile.ui.components.SecondaryButton
import dev.anodex.mobile.ui.components.StatusDot
import dev.anodex.mobile.ui.components.UpdateBanner
import dev.anodex.mobile.ui.screens.AgentsScreen
import dev.anodex.mobile.ui.screens.ChatScreen
import dev.anodex.mobile.ui.screens.ConversationsScreen
import dev.anodex.mobile.ui.screens.EmailPane
import dev.anodex.mobile.ui.screens.FileScreen
import dev.anodex.mobile.ui.screens.HostScreen
import dev.anodex.mobile.ui.screens.ManualPairScreen
import dev.anodex.mobile.ui.screens.NotPairedScreen
import dev.anodex.mobile.ui.screens.OfflineScreen
import dev.anodex.mobile.ui.screens.ProjectPickerScreen
import dev.anodex.mobile.ui.screens.ScanScreen
import dev.anodex.mobile.ui.screens.SchedulerScreen
import dev.anodex.mobile.ui.screens.SettingsScreen
import dev.anodex.mobile.ui.screens.TaskScreen
import dev.anodex.mobile.ui.screens.ThemeMode
import dev.anodex.mobile.ui.screens.WorkspaceScreen
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.AppearanceStore
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch
import kotlinx.coroutines.delay

/**
 * Mirrors the desktop's CONFIRMATION_TIMEOUT_MS.
 *
 * Only ever used to draw a countdown. The desktop owns the deadline and enforces it;
 * if the two ever drift, the card is briefly wrong and nothing else is.
 */
private const val APPROVAL_WINDOW_SECONDS = 5 * 60

/**
 * What the picker offers.
 *
 * The same set the computer will accept, so a file it was always going to refuse
 * cannot be chosen in the first place. Mirrors `ALLOWED_EXTENSIONS` in
 * `uploadStore.ts`; the desktop is still the one that decides, and this is only
 * here so the refusal happens before a transfer rather than after one.
 */
private val ATTACHABLE_TYPES = arrayOf(
    "image/png",
    "image/jpeg",
    "image/gif",
    "image/bmp",
    "text/plain",
    "text/markdown",
    "text/csv",
    "application/json",
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Installed before anything else runs, so a crash during start-up is caught
        // too. Start-up is exactly when the crashes that matter happen: a phone that
        // closes itself the moment it is opened cannot be debugged any other way.
        CrashLog.install(this)
        enableEdgeToEdge()

        val crash = CrashLog.read(this)
        val appearance = AppearanceStore(this)

        setContent {
            // Read here rather than inside the app, because the theme wraps
            // everything including the crash screen. SYSTEM until the store has
            // answered, which is one frame and is also the right default.
            val mode by appearance.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)

            AnodexTheme(
                darkTheme = when (mode) {
                    ThemeMode.DARK -> true
                    ThemeMode.LIGHT -> false
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                },
            ) {
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

    // Everything standing between "paired" and "tells you when your computer needs
    // you", asked for in order, as ordinary system dialogs.
    //
    // Both of these used to be reachable only by knowing they existed and going
    // looking for them, which meant in practice that they were not granted — and a
    // silent app looks identical to a computer with nothing to say. Two taps here
    // and the user never opens a settings screen at all.
    val setupPrompt by viewModel.setupPrompt.collectAsStateWithLifecycle()

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.setupPromptHandled() }

    // The battery dialog is an Activity rather than a permission request, so there
    // is no result to wait on — the answer is read back off the system when this
    // screen returns to the front.
    val backgroundRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.setupPromptHandled() }

    LaunchedEffect(setupPrompt) {
        when (setupPrompt) {
            AnodexViewModel.SetupPrompt.NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // Nothing to ask for below 13; notifications are on unless the
                    // user has switched the app off, which the settings screen says.
                    viewModel.setupPromptHandled()
                }

            AnodexViewModel.SetupPrompt.BACKGROUND ->
                runCatching { backgroundRequest.launch(viewModel.batteryExemptionIntent()) }
                    // Some builds refuse to show it at all. Not worth a message: the
                    // settings screen says what is missing and offers the way in.
                    .onFailure { viewModel.setupPromptHandled() }

            null -> Unit
        }
    }
    val pairingError by viewModel.pairingError.collectAsStateWithLifecycle()
    var scanning by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    // Plain `remember`, not `rememberSaveable`: a rotation should not restore a
    // half-answered question about destroying the pairing. Dismissed is the safe
    // resting state, so losing it is the right way to lose it.
    var replacingPairing by remember { mutableStateOf(false) }
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

        is ConnectionState.Offline -> {
            OfflineScreen(
                state = current,
                onRetry = viewModel::retry,
                onReplacePairing = { replacingPairing = true },
                hint = connectionHint,
            )

            // Asked before unpairing, because the phone cannot undo it. Re-pairing
            // needs a QR code only the desktop can show, so a mis-tap here does not
            // cost a step — it costs however long it takes to get back to the
            // computer, which on this screen is by definition "not now".
            if (replacingPairing) {
                ConfirmDialog(
                    title = "Pair with a different computer?",
                    body = "${current.host.displayName} will stop accepting this phone. " +
                        "You'll need a new pairing code from the computer you want to " +
                        "use, so do this only when you can get to it.",
                    confirmLabel = "Unpair",
                    onConfirm = {
                        replacingPairing = false
                        viewModel.unpair()
                    },
                    onDismiss = { replacingPairing = false },
                )
            }
        }

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

    /** The task being read, by id. Held by id rather than by value so a refresh
     *  while it is open shows the new run rather than the one from when it opened. */
    var openTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var showingAllConversations by rememberSaveable { mutableStateOf(false) }

    /**
     * Whether Workspace is showing the list of projects rather than a project's files.
     *
     * Set when Workspace is chosen from the drawer, cleared when a project is picked.
     * The screen also shows the list unasked when no project is open, which is the
     * same idea arrived at from the other direction.
     */
    var browsingProjects by rememberSaveable { mutableStateOf(false) }

    /**
     * Whether the project's files are showing over the chat.
     *
     * Its own state rather than a destination: this is the workspace *beside* the
     * conversation, not instead of it. Reading a file while talking about it is the
     * whole reason to have it here, and navigating away and back would lose the
     * conversation's place on screen.
     */
    var filesOpen by rememberSaveable { mutableStateOf(false) }
    var showingSettings by rememberSaveable { mutableStateOf(false) }

    val agentRuns by viewModel.agentRuns.collectAsStateWithLifecycle()
    val agentsLoading by viewModel.agentsLoading.collectAsStateWithLifecycle()
    val busyRunId by viewModel.busyRunId.collectAsStateWithLifecycle()

    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val projectBusy by viewModel.projectBusy.collectAsStateWithLifecycle()
    val projectError by viewModel.projectError.collectAsStateWithLifecycle()
    val notificationAccess by viewModel.notificationAccess.collectAsStateWithLifecycle()

    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val archiveNotice by viewModel.archiveNotice.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    val waitingAgents = agentRuns.count { it.status == AgentRun.Status.NEEDS_REVIEW }
    val model = (state as? ConnectionState.Connected)?.model
    val newerVersion by viewModel.newerVersion.collectAsStateWithLifecycle()
    val openFile by viewModel.openFile.collectAsStateWithLifecycle()
    val openFileContent by viewModel.openFileContent.collectAsStateWithLifecycle()
    val unreadEmail by viewModel.unreadEmail.collectAsStateWithLifecycle()
    val conversationsError by viewModel.conversationsError.collectAsStateWithLifecycle()
    val personalityState by viewModel.personalities.collectAsStateWithLifecycle()

    val personalityBusy by viewModel.personalityBusy.collectAsStateWithLifecycle()
    val installedModels by viewModel.installedModels.collectAsStateWithLifecycle()
    val loadingModelPath by viewModel.loadingModelPath.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val workspaceFiles by viewModel.workspaceFiles.collectAsStateWithLifecycle()
    val workspaceLoading by viewModel.workspaceLoading.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val tasksLoading by viewModel.tasksLoading.collectAsStateWithLifecycle()
    val tasksError by viewModel.tasksError.collectAsStateWithLifecycle()
    val workspaceError by viewModel.workspaceError.collectAsStateWithLifecycle()

    val updateState by viewModel.update.collectAsStateWithLifecycle()
    val updateDismissed by viewModel.updateDismissed.collectAsStateWithLifecycle()

    var canInstallUpdates by remember { mutableStateOf(true) }

    /**
     * Re-read every time the app comes back to the front.
     *
     * The permission is granted on a system screen, so the app is always backgrounded
     * at the moment it changes and there is no callback that carries the new value.
     * Keying this on the update state instead was the bug: the state does not change
     * while the user is away, so returning from the settings page left the banner
     * still offering "Allow installs" and tapping it sent them straight back — a loop
     * that only a force-close broke.
     */
    LifecycleResumeEffect(Unit) {
        canInstallUpdates = viewModel.canInstallUpdates()

        // And ask again whether there is a newer build. Checking only at launch meant
        // reopening from recents — which resumes this process rather than starting
        // one — never checked again, so an update published while the app sat in the
        // background stayed invisible until something else forced a reconnect. The
        // view model throttles this; it is safe to call on every resume.
        viewModel.checkForUpdate()
        onPauseOrDispose {}
    }

    /**
     * Carry straight on once the permission is granted.
     *
     * The user tapped "Allow installs" meaning "update the app", not meaning "visit a
     * settings page" — so coming back having granted it should continue the job rather
     * than return them to the same button they just pressed. This page reports no
     * result of its own, so the answer is read back rather than taken from the
     * callback.
     */
    val installPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        canInstallUpdates = viewModel.canInstallUpdates()
        if (canInstallUpdates) viewModel.installUpdate()
    }

    // A cold start always looks, throttle or not: GitHub is the only source that
    // works when the computer is not reachable, which is exactly when somebody is
    // most likely to be wondering whether their app is current.
    LaunchedEffect(Unit) { viewModel.checkForUpdate(force = true) }

    // Fetched when its destination is opened rather than on every connect: a phone
    // that never opens Agents should not be polling the computer for them. The
    // conversation list is refreshed whenever the drawer opens, since that is the
    // only place it is shown.
    // Read when the screen is opened, not on a timer. Both are only interesting at
    // the moment somebody looks at them.
    //
    // Keyed on the connection as well as the destination, because the destination
    // alone was not enough and that was the whole bug. Opening Scheduler before the
    // socket finished coming up read nothing, and nothing ever read again: the
    // screen was already the current one, so no later change re-ran this. A
    // connection that dropped and came back under a screen the user was already
    // looking at failed the same way. Both showed an empty list, which is
    // indistinguishable from having nothing scheduled — and on the builds where
    // these screens had no error state yet, that is exactly what it looked like.
    //
    // Unlike the lists refreshed on connect, these two stay tied to being looked
    // at: a phone that never opens Scheduler still never asks about tasks.
    val connected = state is ConnectionState.Connected
    LaunchedEffect(destination, connected) {
        if (!connected) return@LaunchedEffect
        when (destination) {
            AppDestination.AGENTS -> viewModel.refreshAgentRuns()
            AppDestination.WORKSPACE -> {
                // Both: the files if a project is open, and the list to choose from
                // if one is not. Which of the two the screen shows is decided there.
                viewModel.refreshProjects()
                viewModel.refreshWorkspaceFiles()
            }
            AppDestination.SCHEDULER -> viewModel.refreshTasks()
            else -> Unit
        }
    }
    // Whichever list the drawer is about to show, freshly read. Projects while
    // Workspace is open, conversations otherwise: the drawer's second half answers
    // "what am I working on", and that is a different noun in each half of the app.
    LaunchedEffect(drawerOpen, destination) {
        if (!drawerOpen) return@LaunchedEffect

        if (destination == AppDestination.WORKSPACE) viewModel.refreshProjects()
        else viewModel.refreshConversations()
    }

    // Read when it is opened rather than on a timer, the same rule the Workspace
    // screen follows: a file list is only interesting at the moment somebody looks
    // at it.
    LaunchedEffect(filesOpen) { if (filesOpen) viewModel.refreshWorkspaceFiles() }

    // Back unwinds one step at a time, in the order the user got here.
    BackHandler(
        enabled = drawerOpen || filesOpen || choosingProject || showingHost || showingSettings ||
            showingAllConversations || browsingProjects || destination != AppDestination.CHAT
    ) {
        when {
            drawerOpen -> drawerOpen = false
            filesOpen -> filesOpen = false
            showingSettings -> showingSettings = false
            showingHost -> showingHost = false
            showingAllConversations -> showingAllConversations = false
            browsingProjects -> browsingProjects = false
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

    // Read by id every recomposition, so a refresh that lands while this is open
    // redraws with the new run instead of the snapshot taken when it was tapped.
    val openTask = openTaskId?.let { id -> tasks.firstOrNull { it.id == id } }
    if (openTask != null) {
        val runningId by viewModel.taskRunning.collectAsStateWithLifecycle()

        BackHandler { openTaskId = null }

        TaskScreen(
            task = openTask,
            running = runningId == openTask.id,
            error = tasksError,
            onRunNow = { viewModel.runTaskNow(openTask.id) },
            modifier = Modifier.safeDrawingPadding(),
        )
        return
    }

    if (showingHost) {
        // Collected here rather than passed down: this is the only screen that shows
        // the address list, and threading it through the scaffold's signature for one
        // caller buys nothing.
        val pairedHost by viewModel.paired.collectAsStateWithLifecycle()
        val addressError by viewModel.addressError.collectAsStateWithLifecycle()

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
            knownAddresses = pairedHost?.addresses.orEmpty(),
            port = pairedHost?.port,
            onAddAddress = viewModel::addAddress,
            addressError = addressError,
            onDismissAddressError = viewModel::clearAddressError,
            modifier = Modifier.safeDrawingPadding(),
        )
        return
    }

    if (showingAllConversations) {
        ConversationsScreen(
            conversations = conversations,
            loading = false,
            error = conversationsError,
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
        val memories by viewModel.memories.collectAsStateWithLifecycle()
        val memoryLoading by viewModel.memoryLoading.collectAsStateWithLifecycle()
        val memoryError by viewModel.memoryError.collectAsStateWithLifecycle()

        // Read when Settings opens rather than when the Memory section is reached:
        // the section is chosen inside that screen, and threading a callback back
        // out for one list costs more than the read it would save.
        LaunchedEffect(Unit) { viewModel.refreshMemories() }

        // Looked at again every time Settings opens: a channel can be switched off
        // from the shade while the app sits in the background, and this process
        // would otherwise keep showing the answer it read at launch.
        LaunchedEffect(Unit) { viewModel.refreshNotificationAccess() }
        val settingsContext = LocalContext.current

        SettingsScreen(
            installedVersion = BuildConfig.VERSION_NAME,
            notificationAccess = notificationAccess,
            onOpenNotificationSettings = {
                settingsContext.startActivity(viewModel.notificationSettingsIntent())
            },
            onAllowBackground = {
                settingsContext.startActivity(viewModel.batteryExemptionIntent())
            },
            memories = memories,
            memoryLoading = memoryLoading,
            memoryError = memoryError,
            onForgetMemory = viewModel::forgetMemory,
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
            themeMode = themeMode,
            onSelectTheme = viewModel::setThemeMode,
            models = installedModels,
            activeModelPath = model?.path?.takeIf { it.isNotBlank() },
            loadingModelPath = loadingModelPath,
            onLoadModel = viewModel::loadModel,
            modifier = Modifier.safeDrawingPadding(),
        )
        return
    }

    /**
     * The drawer, as a panel over the app rather than a screen instead of it.
     *
     * It used to return early and replace everything, so opening it tore down the
     * conversation and built it again on the way back — and the app appeared to jump
     * somewhere else to answer a question about where you already were. Sliding it
     * over keeps the chat on screen behind, which is what makes it read as a menu
     * belonging to this page instead of a page of its own.
     *
     * Declared here and drawn at the bottom of the Box below, so it sits above the
     * content in the layer order.
     */
    val drawer: @Composable () -> Unit = {
        AppDrawer(
            destination = destination,
            onSelect = { chosen ->
                // Every section opens its own index. Agents, Email and Scheduler
                // already are lists; Chat and Workspace were the two that dropped you
                // into a single item — or into nothing at all, which is how Workspace
                // became a dead end.
                drawerOpen = false
                when (chosen) {
                    AppDestination.CHAT -> showingAllConversations = true
                    AppDestination.WORKSPACE -> {
                        destination = AppDestination.WORKSPACE
                        browsingProjects = true
                    }
                    else -> destination = chosen
                }
            },
            conversations = conversations,
            projects = projects.projects,
            activeProjectId = projects.activeProjectId,
            // No onOpenProject any more. A workspace row in the drawer expands to
            // show its chats; switching the *active* project is global — it moves
            // the workspace of whoever is sitting at the computer — and that belongs
            // behind the Workspace row above, which opens the picker, rather than
            // behind a tap whose visible job is "show me what is in here".
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
            emailBadge = unreadEmail ?: 0,
            // The width belongs to the panel that slides it in now, so that the two
            // panels cannot drift apart: "as far as the left menu" is the rule for
            // the files side, and a rule stated in two places is a rule that will
            // one day be stated differently.
            modifier = Modifier.safeDrawingPadding(),
        )
    }

    /**
     * Whether the files panel has anything true to show beside this conversation.
     *
     * The desktop answers `workspace:list-files` for the project it currently has
     * open, and for no other — there is no way to ask it for a different one. So the
     * panel is offered only where the conversation on screen *is* that project: a
     * chat filed under a workspace the computer has since moved away from would be
     * shown another project's files under its own name, which is worse than showing
     * nothing at all.
     */
    val filesBeside = destination == AppDestination.CHAT &&
        chat?.projectId != null &&
        chat?.projectId == projects.activeProjectId

    // A conversation that moves out from under the panel takes the panel with it,
    // rather than leaving it to spring open again the next time one qualifies.
    LaunchedEffect(filesBeside) { if (!filesBeside) filesOpen = false }

    // Hoisted out of the panels because the gesture that *opens* one has to live on
    // the page rather than over it. An invisible strip laid across the left edge took
    // the menu button out of reach of a tap — Compose stops hit-testing at the topmost
    // thing under the finger, so an overlay swallows what it covers whether or not it
    // ever consumes anything.
    val drawerSwipe = rememberPanelSwipe(
        open = drawerOpen,
        onOpenChange = {
            drawerOpen = it
            // One at a time. Both open at once is two scrims over one page and a
            // sliver of app between them.
            if (it) filesOpen = false
        },
        side = PanelSide.LEFT,
        widthFraction = DRAWER_WIDTH_FRACTION,
    )

    val filesSwipe = rememberPanelSwipe(
        open = filesOpen && filesBeside,
        onOpenChange = {
            filesOpen = it
            if (it) drawerOpen = false
        },
        side = PanelSide.RIGHT,
        widthFraction = DRAWER_WIDTH_FRACTION,
        edgeGrabEnabled = filesBeside,
    )

    // Inside a conversation the title takes the top line and the host shrinks to its
    // dot: you already know which computer, and what you are reading is the
    // conversation. Everywhere else the host bar is the most useful thing there.
    val conversationTitle = chat?.let { session ->
        session.existingTitle?.takeIf { it.isNotBlank() }
            ?: messagesTitle(session)
    }

    // The workspace this conversation belongs to, not whichever one the computer
    // happens to have open. They are usually the same and the difference is the whole
    // point: a chat filed under a project the desktop has since moved away from still
    // belongs to that project.
    val chatProject = chat?.projectId
        ?.let { id -> projects.projects.firstOrNull { it.id == id } }

    /** Whether the bars hang over the page instead of sitting above it. */
    val floatingChrome = destination == AppDestination.CHAT

    /**
     * What the pill says.
     *
     * "New chat" until the computer has titled it, rather than falling back to the
     * old host bar. A conversation that has not been named yet is still a
     * conversation, and swapping the whole header for a different one the moment you
     * send the first message — a different shape, a different height, in a different
     * place — reads as the app changing screens under you.
     */
    val headerTitle = conversationTitle ?: "New chat"

    // How tall that floating chrome turned out to be, so the transcript underneath
    // knows how far to fade and how much room to leave itself. Measured rather than
    // assumed: the update banner comes and goes, and a long title does not wrap but a
    // workspace name beneath it changes the pill's height.
    var chromeHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.bgApp)
                // Before `safeDrawingPadding`, so the band the gesture watches is the
                // real edge of the screen rather than the edge of the inset content.
                .panelEdgeGrab(drawerSwipe)
                .panelEdgeGrab(filesSwipe)
                .safeDrawingPadding()
        ) {
            // Everywhere but a conversation, the header sits in the flow and the
            // page starts underneath it. In a conversation it hangs over the
            // transcript instead — see the floating column below.
            if (!floatingChrome) {
                ConnectionHeader(
                    state = state,
                    onOpenDrawer = { drawerOpen = true },
                )
                Hairline()
            }

            // Under the header on every screen rather than inside chat: a newer app is
            // not a chat concern, and the previous version of this notice lived two taps
            // in on the host screen, where it went unseen through an entire release.
            if (!floatingChrome && !updateDismissed) {
                UpdateBanner(
                    state = updateState,
                    canInstall = canInstallUpdates,
                    onInstall = viewModel::installUpdate,
                    onGrantInstall = { installPermission.launch(viewModel.installPermissionIntent()) },
                    onDismiss = viewModel::dismissUpdate,
                    installedVersion = BuildConfig.VERSION_NAME,
                )
            }

            Box(Modifier.weight(1f)) {
                when (destination) {
                    AppDestination.CHAT -> ChatPane(
                        chat,
                        viewModel,
                        topInset = if (floatingChrome) chromeHeight else 0.dp,
                        // Named on the empty screen, because which computer is awake is
                        // the one thing no other assistant can put there.
                        hostLine = hostNameOf(state)?.let { "$it is awake and listening" },
                        openers = openersFor(
                            projectName = projects.active?.name,
                            unreadEmail = unreadEmail,
                            lastTask = tasks.firstOrNull { it.lastRunAt != null },
                            waitingAgents = waitingAgents,
                        ),
                    )

                    AppDestination.AGENTS -> {
                        val startingRun by viewModel.startingRun.collectAsStateWithLifecycle()
                        val agentsError by viewModel.agentsError.collectAsStateWithLifecycle()

                        AgentsScreen(
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
                            onStart = { goal, lookOnly ->
                                viewModel.startAgentRun(goal, lookOnly) {}
                            },
                            starting = startingRun,
                            projectName = projects.active?.name,
                            error = agentsError,
                        )
                    }

                    AppDestination.EMAIL -> EmailPane(viewModel)

                    AppDestination.WORKSPACE -> WorkspaceScreen(
                        files = workspaceFiles,
                        loading = workspaceLoading,
                        onOpenFile = viewModel::openWorkspaceFile,
                        projectName = projects.active?.name,
                        error = workspaceError,
                        projects = projects.projects,
                        activeProjectId = projects.activeProjectId,
                        browsing = browsingProjects,
                        onOpenProject = { id ->
                            viewModel.setActiveProject(id)
                            browsingProjects = false
                        },
                        onBrowseProjects = { browsingProjects = true },
                        onNewChatHere = projects.activeProjectId?.let { id ->
                            {
                                viewModel.newConversation(projectId = id)
                                destination = AppDestination.CHAT
                            }
                        },
                    )

                    AppDestination.SCHEDULER -> {
                        val parsedWhen by viewModel.draftWhen.collectAsStateWithLifecycle()
                        val creatingTask by viewModel.creatingTask.collectAsStateWithLifecycle()

                        SchedulerScreen(
                            tasks = tasks,
                            loading = tasksLoading,
                            error = tasksError,
                            onOpenTask = { openTaskId = it },
                            onDraftChanged = viewModel::parseWhen,
                            parsed = parsedWhen,
                            creating = creatingTask,
                            onCreate = { prompt -> viewModel.createTask(prompt) {} },
                        )
                    }
                }
            }
        }

        // The conversation's own bar, hanging over the transcript rather than sitting
        // above it. The chat runs edge to edge underneath and dissolves into both
        // bars, which is the whole effect: the page is plainly continuing up there,
        // rather than stopping at a line.
        if (floatingChrome) {
            // Something for the bar to sit against, and only just.
            //
            // The conversation runs underneath and is meant to show through. But the
            // controls have to be findable against whatever happens to be scrolling
            // behind them, and text at half strength directly under a title is a
            // competition nobody wins. This holds most of the page's colour at the
            // top edge and lets go of it just past the bar.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(chromeHeight + SCRIM_FADE)
                    .background(
                        Brush.verticalGradient(
                            0f to colors.bgApp,
                            SCRIM_HOLD to colors.bgApp.copy(alpha = SCRIM_ALPHA),
                            1f to Color.Transparent,
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .safeDrawingPadding()
                    .onSizeChanged { chromeHeight = with(density) { it.height.toDp() } },
            ) {
                ChatHeader(
                    title = headerTitle,
                    workspaceName = chatProject?.name,
                    hostName = hostNameOf(state),
                    connected = state is ConnectionState.Connected,
                    status = HostStatus(
                        hostName = hostNameOf(state),
                        connection = connectionWordFor(state),
                        connected = state is ConnectionState.Connected,
                        workspaceName = chatProject?.name,
                        folderPath = chatProject?.folderPath,
                        modelName = model?.name,
                        contextUsedTokens = model?.contextUsedTokens ?: 0,
                        contextTotalTokens = model?.contextTotalTokens ?: 0,
                        conversationId = chat?.conversationId,
                    ),
                    onOpenDrawer = { drawerOpen = true },
                    onCopyId = {
                        chat?.conversationId?.let { id ->
                            clipboard.setText(AnnotatedString(id))
                        }
                    },
                    onArchive = {
                        chat?.conversationId?.let(viewModel::archiveConversation)
                    },
                    // Only where the panel would show this conversation's own files.
                    // The same rule the right edge follows, so the menu item and the
                    // gesture cannot disagree about whether there is anything there.
                    onOpenFiles = if (filesBeside) {
                        { filesOpen = true }
                    } else {
                        null
                    },
                )

                if (!updateDismissed) {
                    UpdateBanner(
                        state = updateState,
                        canInstall = canInstallUpdates,
                        onInstall = viewModel::installUpdate,
                        onGrantInstall = {
                            installPermission.launch(viewModel.installPermissionIntent())
                        },
                        onDismiss = viewModel::dismissUpdate,
                        installedVersion = BuildConfig.VERSION_NAME,
                    )
                }
            }
        }

        // Over the page, under the panels. Archiving asks nothing before it
        // happens, so this is where the question gets asked instead.
        archiveNotice?.let { archived ->
            UndoBar(
                text = if (archived.failed) {
                    "Could not archive “${archived.title}”."
                } else {
                    "Archived “${archived.title}”."
                },
                actionLabel = if (archived.failed) null else "Undo",
                onAction = viewModel::restoreArchived,
                onDismiss = viewModel::dismissArchiveNotice,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // The same strip, carrying something that has no undo — a conversation the
        // computer would not hand over, most of all, because that failure is
        // otherwise indistinguishable from a tap that did not register.
        notice?.let { text ->
            UndoBar(
                text = text,
                onDismiss = viewModel::dismissNotice,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // In from the edge it lives on, and back to it, following the finger the
        // whole way. The app behind does not move: these slide over the page rather
        // than pushing it aside. These two *are* overlays, and should be — while a
        // panel is open the page behind it is not meant to be touchable.
        SlidingPanel(drawerSwipe) {
            drawer()
        }

        // The same width as the drawer, deliberately: the two panels are the two
        // halves of the same idea — what am I working on, and what am I working *in*
        // — and one reaching further across than the other would say one of them is
        // the bigger thing.
        SlidingPanel(filesSwipe) {
            WorkspaceScreen(
                files = workspaceFiles,
                loading = workspaceLoading,
                onOpenFile = { path ->
                    // The file takes the whole screen, so the panel it was picked
                    // from should not still be sitting under it when that closes.
                    filesOpen = false
                    viewModel.openWorkspaceFile(path)
                },
                projectName = projects.active?.name,
                error = workspaceError,
                modifier = Modifier.safeDrawingPadding(),
            )
        }
    }
}

/**
 * How far a panel reaches across, leaving the app visible beside it.
 *
 * Both of them: the drawer on the left and the files on the right. "Only as far as
 * the left menu" is the rule for the right-hand one, and the way to keep a rule like
 * that is to have one number rather than two that agree today.
 */
private const val DRAWER_WIDTH_FRACTION = 0.86f

/**
 * Three things worth asking, from what is true on the computer right now.
 *
 * Not a tour of the features. Every assistant opens with a list of what it can do
 * and nobody reads one — the reason is that a capability is not a question, and
 * somebody staring at an empty composer is short of a question rather than short of
 * information.
 *
 * So each of these names something real: the run that is blocked, the mail that is
 * unread, the task that ran while nobody was watching, the project that is open. If
 * none of that is true the screen stays as it was, because inventing an opener for
 * a computer with nothing going on is exactly the generic list this avoids.
 *
 * Ordered by what is most likely to be *waiting on the person reading it*: a
 * blocked run first, because that one is costing time right now.
 */
private fun openersFor(
    projectName: String?,
    /** Null when the mailbox has not been reached, which is not the same as none. */
    unreadEmail: Int?,
    lastTask: ScheduledTask?,
    waitingAgents: Int,
): List<String> = buildList {
    if (waitingAgents > 0) {
        add("What is the agent run waiting on?")
    }
    // Offered only on a count the app actually has. Suggesting "which of my unread
    // emails need a reply" on the strength of a read that failed is an opener that
    // fails the moment it is tapped.
    if (unreadEmail != null && unreadEmail > 0) {
        add(
            if (unreadEmail == 1) {
                "What is the unread email about?"
            } else {
                "Which of my $unreadEmail unread emails need a reply?"
            }
        )
    }
    lastTask?.let { task -> add("How did “${task.name}” go?") }
    projectName?.let { name -> add("What changed in $name recently?") }
}
    .take(MAX_OPENERS)

/** Three. A fourth is a menu, and a menu is the thing this is not. */
private const val MAX_OPENERS = 3

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

/** One word for what the link is doing. */
private fun connectionWordFor(state: ConnectionState): String = when (state) {
    is ConnectionState.Connected -> "Connected"
    is ConnectionState.Reconnecting -> "Reconnecting"
    is ConnectionState.Offline -> "Offline"
    ConnectionState.Unpaired -> "Not paired"
}

/** "Connected · Bench" — what the computer is, and what it is pointed at. */
private fun hostDetailOf(state: ConnectionState, projectName: String?): String {
    val status = connectionWordFor(state)
    return if (projectName != null) "$status · $projectName" else status
}

/** The open conversation, or an honest placeholder while the socket is down. */
@Composable
private fun ChatPane(
    chat: ChatSession?,
    viewModel: AnodexViewModel,
    hostLine: String?,
    openers: List<String> = emptyList(),
    /** How much floating chrome hangs over the top of the conversation. */
    topInset: Dp = 0.dp,
) {
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()

    // The system picker, which is the only way an app sees a file it did not create.
    // Narrowed to what the computer will actually accept, so the refusal happens in
    // the picker rather than after a transfer.
    //
    // `OpenMultipleDocuments`, because the single-file contract was the only reason
    // one at a time was the limit. Everything behind it was already plural: the
    // composer holds a list, the request carries a `userFiles` array, and the
    // desktop reads one. The picker was the pinch point, and it was one word wide.
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> uris.forEach(viewModel::attach) }
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
        openers = openers,
        topInset = topInset,
        messages = messages,
        sending = sending,
        error = error,
        onSend = viewModel::sendMessage,
        onStop = chat::stop,
        approval = approval,
        approvalSecondsRemaining = secondsLeft,
        onApprove = { chat.respondToApproval(approved = true) },
        onDeny = { chat.respondToApproval(approved = false) },
        onOpenFile = viewModel::openWorkspaceFile,
        hostLine = hostLine,
        onRetryMessage = chat::retry,
        pendingAttachments = attachments,
        onAttach = { pickFile.launch(ATTACHABLE_TYPES) },
        onRemoveAttachment = viewModel::removeAttachment,
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
                OfflineScreen(state = state, onRetry = advance, onReplacePairing = onExit)

            else -> Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                ConnectionHeader(state)
                Hairline()
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
