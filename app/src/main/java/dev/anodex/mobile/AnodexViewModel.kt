package dev.anodex.mobile

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.anodex.mobile.agents.AgentRun
import dev.anodex.mobile.agents.Agents
import dev.anodex.mobile.agents.RunTurn
import dev.anodex.mobile.agents.parseAgentRuns
import dev.anodex.mobile.chat.ChatMessage
import dev.anodex.mobile.chat.ChatSession
import dev.anodex.mobile.chat.PendingApprovals
import dev.anodex.mobile.chat.holdsMoreThanComputer
import dev.anodex.mobile.chat.parseToolApproval
import dev.anodex.mobile.chat.waitingApprovals
import dev.anodex.mobile.chat.ContextUsage
import dev.anodex.mobile.chat.ConversationSummary
import dev.anodex.mobile.chat.Conversations
import dev.anodex.mobile.chat.LocalModel
import dev.anodex.mobile.chat.MessageMatch
import dev.anodex.mobile.chat.MessagePersona
import dev.anodex.mobile.chat.Models
import dev.anodex.mobile.chat.Personalities
import dev.anodex.mobile.chat.PersonalityPictures
import dev.anodex.mobile.chat.PersonalityState
import dev.anodex.mobile.chat.Project
import dev.anodex.mobile.chat.Projects
import dev.anodex.mobile.chat.ProjectsState
import dev.anodex.mobile.chat.UploadState
import dev.anodex.mobile.chat.Uploads
import dev.anodex.mobile.chat.contextUsageFrom
import dev.anodex.mobile.chat.parseProjectsState
import dev.anodex.mobile.chat.replyPreview
import dev.anodex.mobile.chat.titleFromFirstTurn
import dev.anodex.mobile.connection.AttemptFailure
import dev.anodex.mobile.connection.ConnectionController
import dev.anodex.mobile.connection.ConnectionService
import dev.anodex.mobile.connection.ConnectionState
import dev.anodex.mobile.connection.HostIdentity
import dev.anodex.mobile.connection.ModelStatus
import dev.anodex.mobile.connection.NetworkMonitor
import dev.anodex.mobile.connection.PairedHostRef
import dev.anodex.mobile.connection.Reachability
import dev.anodex.mobile.connection.RemoteFarewell
import dev.anodex.mobile.connection.connectionDetail
import dev.anodex.mobile.connection.diagnoseConnectionFailure
import dev.anodex.mobile.connection.isNoLongerPaired
import dev.anodex.mobile.connection.isUpdateAvailable
import dev.anodex.mobile.connection.localIPv4Addresses
import dev.anodex.mobile.connection.mostTellingFailure
import dev.anodex.mobile.connection.processHoldFor
import dev.anodex.mobile.email.Email
import dev.anodex.mobile.email.EmailNote
import dev.anodex.mobile.email.EmailThread
import dev.anodex.mobile.memory.Memory
import dev.anodex.mobile.memory.MemoryEntry
import dev.anodex.mobile.notify.NotificationAccess
import dev.anodex.mobile.notify.NotificationKind
import dev.anodex.mobile.notify.Notifications
import dev.anodex.mobile.notify.ReplyBridge
import dev.anodex.mobile.notify.RunActionBridge
import dev.anodex.mobile.pairing.CertificateProbe
import dev.anodex.mobile.pairing.PairedHost
import dev.anodex.mobile.pairing.PairedHostStore
import dev.anodex.mobile.pairing.PairingPayload
import dev.anodex.mobile.pairing.humanFingerprintOf
import dev.anodex.mobile.profile.ProfileReader
import dev.anodex.mobile.profile.UsageProfile
import dev.anodex.mobile.profile.UserProfile
import dev.anodex.mobile.scheduler.ParsedWhen
import dev.anodex.mobile.scheduler.ScheduledTask
import dev.anodex.mobile.scheduler.Scheduler
import dev.anodex.mobile.scheduler.parseTasks
import dev.anodex.mobile.transport.AnodexSocket
import dev.anodex.mobile.transport.ServerFrame
import dev.anodex.mobile.transport.unwrap
import dev.anodex.mobile.ui.screens.Archived
import dev.anodex.mobile.ui.screens.ManualPairState
import dev.anodex.mobile.ui.screens.ThemeMode
import dev.anodex.mobile.ui.theme.AppearanceStore
import dev.anodex.mobile.ui.theme.DEFAULT_THEME_MODE
import dev.anodex.mobile.ui.theme.FontScale
import dev.anodex.mobile.ui.theme.MotionPreference
import dev.anodex.mobile.ui.theme.UiFont
import dev.anodex.mobile.update.UpdateCheck
import dev.anodex.mobile.update.UpdateState
import dev.anodex.mobile.update.Updater
import dev.anodex.mobile.widget.WidgetConnection
import dev.anodex.mobile.widget.WidgetRecent
import dev.anodex.mobile.widget.WidgetState
import dev.anodex.mobile.workspace.FileContent
import dev.anodex.mobile.workspace.Workspace
import dev.anodex.mobile.workspace.WorkspaceFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Holds the app together: the stored pairing, the connection controller, and the network monitor.
 *
 * Deliberately thin. The decisions live in `reduceConnection` (what a fact means) and
 * `ConnectionController` (when to act) precisely so that they can be unit-tested without Android;
 * pulling logic up into here would put it back out of reach. This class wires, it does not decide.
 */
private const val CHANNEL_NOTIFICATION = "remote:notification"

/** The computer's own list, pushed whenever a run is created, turns, or finishes. */
private const val CHANNEL_AGENT_RUNS = "agent:runs-changed"

/** Same, for scheduled tasks: created, edited, run, deleted. */
private const val CHANNEL_TASKS_CHANGED = "scheduler:tasks-changed"

/** The engine: which model is loaded and how full its context is. */
private const val CHANNEL_MODEL_STATE = "models:state-changed"

/** Which projects exist on the computer, and which one it has open. */
private const val CHANNEL_PROJECTS_CHANGED = "projects:changed"

/**
 * A conversation on the computer has turns this phone has not got.
 *
 * The payload is the conversation's id and nothing else, because the change could be
 * a whole turn or a renamed title and the phone should not guess which.
 */
private const val CHANNEL_CONVERSATIONS_CHANGED = "conversations:changed"
private const val CHANNEL_DEVICES_CHANGED = "devices:changed"

/**
 * What the assistant is carrying forward has changed on the computer.
 *
 * The payload is the scope that changed. The phone re-reads the whole list rather
 * than trying to apply it, because the list is small and a scope key says that
 * something moved without saying what.
 */
private const val CHANNEL_MEMORY_CHANGED = "memory:changed"

/** Telling the computer whether to send tokens as it generates them. */
private const val CHANNEL_SET_LIVE_TOKENS = "chat:set-live-tokens"
private const val CHANNEL_SET_LIVE_THINKING = "chat:set-live-thinking"

class AnodexViewModel(application: Application) : AndroidViewModel(application) {

    private val store = PairedHostStore(application)
    private val networkMonitor = NetworkMonitor(application)
    private val notifications = Notifications(application).apply { ensureChannels() }

    /** Approvals the computer is waiting on, for whichever chat opens next. */
    private val pendingApprovals = PendingApprovals()



    private val _notificationAccess = MutableStateFlow(notifications.access())

    /**
     * What the system will actually let through, for the settings screen to show.
     *
     * Re-read rather than remembered, because it changes outside the app: the user
     * can switch a channel off from the shade, or the whole app off in system
     * settings, and the first this process hears of it is the next time it looks.
     */
    val notificationAccess: StateFlow<NotificationAccess> = _notificationAccess.asStateFlow()

    /** Look again — called when a screen that shows this comes back to the front. */
    fun refreshNotificationAccess() {
        _notificationAccess.value = notifications.access()
    }

    fun notificationSettingsIntent(): Intent = notifications.settingsIntent()

    fun batteryExemptionIntent(): Intent = notifications.batteryExemptionIntent()

    /**
     * What the app still needs before it can do the thing it is for, if anything.
     *
     * Two system permissions stand between "paired" and "tells you when your
     * computer needs you", and both are ordinary Android dialogs the app can raise
     * itself. Before this they were reachable only by knowing they existed and
     * going looking — which meant, in practice, that they were not granted, and the
     * app was silent for reasons it never explained.
     *
     * Asked in order, one at a time, and only while something is actually missing:
     * once both are granted this never fires again. Not persisted, so at worst it
     * asks once per launch — and Android stops delivering the notification request
     * after two refusals anyway, at which point [notificationAccess] and the
     * settings screen are the way back.
     */
    enum class SetupPrompt {
        /** Android 13+ needs this before a single notification can be shown. */
        NOTIFICATIONS,

        /**
         * Battery optimisation. Not optional in practice: every notification arrives
         * over the live link, and an unexempted app is stopped in the background —
         * on some manufacturers within seconds of leaving it.
         */
        BACKGROUND,
    }

    private val _setupPrompt = MutableStateFlow<SetupPrompt?>(null)
    val setupPrompt: StateFlow<SetupPrompt?> = _setupPrompt.asStateFlow()

    /** One prompt has been answered — offer the next, or stop. */
    fun setupPromptHandled() {
        _setupPrompt.value = null
        advanceSetup()
    }

    private var askedForBackground = false

    private fun advanceSetup() {
        refreshNotificationAccess()
        _setupPrompt.value = when {
            !notifications.canNotify() -> SetupPrompt.NOTIFICATIONS

            // Only after notifications are actually on. Somebody who declined those
            // has said what they want, and following it with a second dialog about
            // battery would be asking the same question again in different words.
            !notifications.isExemptFromBatteryOptimisation() && !askedForBackground -> {
                askedForBackground = true
                SetupPrompt.BACKGROUND
            }

            else -> null
        }
    }


    private val _paired = MutableStateFlow<PairedHost?>(null)

    /** The paired desktop, once loaded. Null means unpaired — show the pairing flow. */
    val paired: StateFlow<PairedHost?> = _paired.asStateFlow()

    private val _chat = MutableStateFlow<ChatSession?>(null)

    /** The live conversation, or null while disconnected. Never a cache - see ChatSession. */
    val chat: StateFlow<ChatSession?> = _chat.asStateFlow()

    /**
     * Put [session] on screen, and close the one it replaces.
     *
     * Closed rather than dropped: a session listens to the socket until it is told to
     * stop, and every one ever opened used to go on reading every token.
     */
    private fun showChat(session: ChatSession?) {
        val previous = _chat.value
        _chat.value = session
        if (previous != null && previous !== session) previous.close()
    }

    private val _pairingError = MutableStateFlow<String?>(null)
    val pairingError: StateFlow<String?> = _pairingError.asStateFlow()

    private val _connectionHint = MutableStateFlow<String?>(null)

    /**
     * Why the last connection attempt could not have worked, when that is knowable.
     *
     * Shown on the offline screen. Null while connected, or when the phone has
     * nothing useful to add beyond "it did not answer".
     */
    val connectionHint: StateFlow<String?> = _connectionHint.asStateFlow()

    private val _noLongerPaired = MutableStateFlow(false)

    /**
     * The computer has said it no longer holds this phone's key.
     *
     * The offline screen turns into a way back rather than a wait: the headline said
     * the computer was offline, and Retry was the main button, while the computer was
     * awake and would refuse every retry.
     */
    val noLongerPaired: StateFlow<Boolean> = _noLongerPaired.asStateFlow()

    private val _conversations = MutableStateFlow<List<ConversationSummary>>(emptyList())

    /** What is on the computer. A live read, never a cache - empty when unreachable. */
    val conversations: StateFlow<List<ConversationSummary>> = _conversations.asStateFlow()

    private val _loadingConversations = MutableStateFlow(false)
    val loadingConversations: StateFlow<Boolean> = _loadingConversations.asStateFlow()

    /** Reads the desktop's conversation store. Null until a socket is open. */
    private var conversationReader: Conversations? = null

    private var devicesClient: dev.anodex.mobile.devices.Devices? = null

    private val _pairedDevices = MutableStateFlow<List<dev.anodex.mobile.devices.PairedDeviceInfo>?>(null)

    /**
     * Every device paired with the computer, or null until read — and still null
     * against a computer too old to say.
     */
    val pairedDevices: StateFlow<List<dev.anodex.mobile.devices.PairedDeviceInfo>?> = _pairedDevices.asStateFlow()

    fun refreshPairedDevices() {
        val client = devicesClient ?: return
        viewModelScope.launch {
            runCatching { client.list() }.onSuccess { _pairedDevices.value = it }
        }
    }

    fun renamePairedDevice(deviceId: String, name: String) {
        val client = devicesClient ?: return
        viewModelScope.launch {
            runCatching { client.rename(deviceId, name) }.onSuccess { _pairedDevices.value = it }
        }
    }

    /**
     * Unpair a device. For this phone, the computer drops the connection straight after
     * answering, and the app goes back to the pairing screen as it would for an unpair
     * done at the computer.
     */
    fun unpairDevice(deviceId: String) {
        val client = devicesClient ?: return
        viewModelScope.launch {
            runCatching { client.unpair(deviceId) }.onSuccess { _pairedDevices.value = it }
        }
    }

    /** Pictures read back from the computer, by conversation, message and position. */
    private val pictureCache = object : android.util.LruCache<String, ByteArray>(PICTURE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    /**
     * The picture attached to a message on the computer, for the transcript.
     *
     * Kept once read, so scrolling a chat back and forth does not fetch the same
     * picture over the connection every time it comes into view.
     */
    suspend fun attachmentPreview(conversationId: String, messageId: String, index: Int): ByteArray? {
        val key = "$conversationId/$messageId/$index"
        pictureCache.get(key)?.let { return it }
        val reader = conversationReader ?: return null
        val bytes = runCatching { reader.attachmentPreview(conversationId, messageId, index) }.getOrNull() ?: return null
        pictureCache.put(key, bytes)
        return bytes
    }

    private var projectClient: Projects? = null
    private var agentClient: Agents? = null

    private val _agentRuns = MutableStateFlow<List<AgentRun>>(emptyList())

    /** Agent runs on the computer, anything waiting on a human first. */
    val agentRuns: StateFlow<List<AgentRun>> = _agentRuns.asStateFlow()

    private val _agentsLoading = MutableStateFlow(false)
    val agentsLoading: StateFlow<Boolean> = _agentsLoading.asStateFlow()

    private val _busyRunId = MutableStateFlow<String?>(null)
    val busyRunId: StateFlow<String?> = _busyRunId.asStateFlow()

    private var emailClient: Email? = null

    private val _emailThreads = MutableStateFlow<List<EmailThread>>(emptyList())

    /** The desktop's inbox, most recent first. Never cached across a disconnect. */
    val emailThreads: StateFlow<List<EmailThread>> = _emailThreads.asStateFlow()

    private val _emailLoading = MutableStateFlow(false)
    val emailLoading: StateFlow<Boolean> = _emailLoading.asStateFlow()

    /**
     * Whether the computer has an account connected at all.
     *
     * Null until asked. An empty inbox and an inbox that does not exist look
     * identical in a list of threads, and they need completely different words.
     */
    private val _emailConfigured = MutableStateFlow<Boolean?>(null)
    val emailConfigured: StateFlow<Boolean?> = _emailConfigured.asStateFlow()

    /**
     * Why the mailbox could not be read, when that is the reason it looks empty.
     *
     * Needed because `emailConfigured` is a three-state answer — unknown, no
     * account, an account — and a failure is none of the three. Folding it into
     * "no account" produced the most confident wrong sentence in the app: *No email
     * account is connected on your computer*, said to somebody whose account is
     * connected and whose phone simply could not ask.
     */
    private val _emailError = MutableStateFlow<String?>(null)
    val emailError: StateFlow<String?> = _emailError.asStateFlow()

    private val _openThread = MutableStateFlow<List<EmailNote>?>(null)

    /** The thread being read, or null while the list is showing. */
    val openThread: StateFlow<List<EmailNote>?> = _openThread.asStateFlow()

    private val _threadLoading = MutableStateFlow(false)
    val threadLoading: StateFlow<Boolean> = _threadLoading.asStateFlow()

    private var workspace: Workspace? = null

    private val _workspaceFiles = MutableStateFlow<List<WorkspaceFile>>(emptyList())

    /** The project's files, newest first. A live read, never a cache. */
    val workspaceFiles: StateFlow<List<WorkspaceFile>> = _workspaceFiles.asStateFlow()

    private val _workspaceLoading = MutableStateFlow(false)
    val workspaceLoading: StateFlow<Boolean> = _workspaceLoading.asStateFlow()

    /**
     * Why the list is empty, when the reason is not "there is nothing".
     *
     * Swallowing this was the bug: a refused channel, a dropped socket and a genuinely
     * empty project all rendered as the same blank screen, so there was no way to tell
     * a working feature with nothing to show from a broken one.
     */
    private val _workspaceError = MutableStateFlow<String?>(null)
    val workspaceError: StateFlow<String?> = _workspaceError.asStateFlow()

    /**
     * Re-read the project's files.
     *
     * Called when the screen is opened rather than on a timer: a file list is only
     * interesting at the moment somebody looks at it, and polling a project of any
     * size from a phone would spend battery answering a question nobody asked.
     */
    fun refreshWorkspaceFiles() {
        val client = workspace
        if (client == null) {
            _workspaceError.value = "Not connected to your computer."
            return
        }

        _workspaceLoading.value = true
        _workspaceError.value = null

        viewModelScope.launch {
            runCatching { client.listFiles() }
                .onSuccess { _workspaceFiles.value = it }
                .onFailure {
                    _workspaceFiles.value = emptyList()
                    _workspaceError.value = it.message ?: "Your computer would not answer."
                }
            _workspaceLoading.value = false
        }
    }

    private var memoryClient: Memory? = null
    private var profileReader: ProfileReader? = null

    /**
     * Whether Settings is open.
     *
     * Settings, rather than the Memory section within it, because that is the
     * granularity the app already has — the list is read when Settings opens and
     * the section is chosen inside that screen without telling anyone.
     *
     * It exists so a push arriving while somebody is in a chat does not spend a
     * round trip refreshing a list nobody can see. Memory is read fresh whenever
     * Settings opens, so nothing is missed by staying quiet until then.
     */
    private val _settingsOpen = MutableStateFlow(false)

    fun onSettingsOpened() {
        _settingsOpen.value = true
    }

    fun onSettingsClosed() {
        _settingsOpen.value = false
    }

    private val _user = MutableStateFlow<UserProfile?>(null)

    /** Whose Anodex this is. Read only — the name and avatar are set at the computer. */
    val user: StateFlow<UserProfile?> = _user.asStateFlow()

    private val _usage = MutableStateFlow<UsageProfile?>(null)

    /** Lifetime activity, as the computer has counted it. */
    val usage: StateFlow<UsageProfile?> = _usage.asStateFlow()

    private val _profileLoading = MutableStateFlow(false)
    val profileLoading: StateFlow<Boolean> = _profileLoading.asStateFlow()

    private val _profileError = MutableStateFlow<String?>(null)
    val profileError: StateFlow<String?> = _profileError.asStateFlow()

    /**
     * Read the profile and the usage numbers.
     *
     * Two independent reads rather than one, and a failure of either is reported
     * rather than folded into an empty screen: "nothing recorded yet" and "could not
     * reach the computer" look identical once both render as blank, and only one of
     * them is the user's fault to fix.
     */
    fun refreshProfile() {
        val reader = profileReader ?: return
        _profileLoading.value = true
        viewModelScope.launch {
            val user = runCatching { reader.user() }
            val usage = runCatching { reader.usage() }
            _profileLoading.value = false

            user.getOrNull()?.let { _user.value = it }
            usage.getOrNull()?.let { _usage.value = it }

            // A read that succeeds and returns nothing is a failure too. The profile
            // shipped exactly that way: the call worked, the parse quietly produced
            // null, and the screen showed an em dash with no error to explain it.
            // `isFailure` alone would let it through again.
            _profileError.value = when {
                usage.isFailure -> usage.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                    ?.let { "Could not read your activity: $it" }
                    ?: "Could not read your activity."
                usage.getOrNull() == null -> "Could not read your activity."
                user.isFailure || _user.value == null -> "Could not read your profile."
                else -> null
            }
        }
    }

    private val _archivedChats = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val archivedChats: StateFlow<List<ConversationSummary>> = _archivedChats.asStateFlow()

    private val _archivedProjects = MutableStateFlow<List<Project>>(emptyList())
    val archivedProjects: StateFlow<List<Project>> = _archivedProjects.asStateFlow()

    private val _archiveLoading = MutableStateFlow(false)
    val archiveLoading: StateFlow<Boolean> = _archiveLoading.asStateFlow()

    private val _archiveError = MutableStateFlow<String?>(null)
    val archiveError: StateFlow<String?> = _archiveError.asStateFlow()

    fun refreshArchive() {
        val conversations = conversationReader
        val projects = projectClient
        if (conversations == null || projects == null) return

        _archiveLoading.value = true
        viewModelScope.launch {
            val chats = runCatching { conversations.listArchived() }
            val workspaces = runCatching { projects.listArchived() }
            _archiveLoading.value = false

            chats.getOrNull()?.let { _archivedChats.value = it }
            workspaces.getOrNull()?.let { _archivedProjects.value = it }
            // Named separately, because "chats did not load" and "projects did not
            // load" send somebody to different places, and the screen shows whichever
            // list did arrive underneath this.
            _archiveError.value = when {
                chats.isFailure && workspaces.isFailure ->
                    "Could not read the archive from the computer."
                chats.isFailure -> "Could not read archived chats."
                workspaces.isFailure -> "Could not read archived projects."
                else -> null
            }
        }
    }

    /** Put something back. Reversible, so it happens without asking. */
    fun restoreArchived(item: Archived) {
        val conversations = conversationReader
        val projects = projectClient
        viewModelScope.launch {
            runCatching {
                when (item) {
                    is Archived.Chat -> conversations?.restore(item.id)
                    is Archived.Workspace -> projects?.restore(item.id)
                }
            }
            // Re-read rather than removing the row locally: restoring a project moves
            // its conversations too, and guessing at that here would leave the two
            // lists disagreeing with the computer.
            refreshArchive()
            refreshConversations()
        }
    }

    /**
     * Remove something for good.
     *
     * The screen asks first. This does not ask again — a second confirmation in a
     * different layer is how a destructive path ends up with two half-checks and no
     * whole one.
     */
    fun deleteArchived(item: Archived) {
        val conversations = conversationReader
        val projects = projectClient
        viewModelScope.launch {
            val done = runCatching {
                when (item) {
                    is Archived.Chat -> conversations?.deletePermanently(item.id)
                    is Archived.Workspace -> projects?.deletePermanently(item.id)
                }
            }
            if (done.isFailure) {
                _archiveError.value = "Could not delete that. Nothing was removed."
            }
            refreshArchive()
            refreshConversations()
        }
    }

    private val _memories = MutableStateFlow<List<MemoryEntry>>(emptyList())

    /** What the computer remembers. Read only — nothing here can write one. */
    val memories: StateFlow<List<MemoryEntry>> = _memories.asStateFlow()

    private val _memoryLoading = MutableStateFlow(false)
    val memoryLoading: StateFlow<Boolean> = _memoryLoading.asStateFlow()

    private val _memoryError = MutableStateFlow<String?>(null)
    val memoryError: StateFlow<String?> = _memoryError.asStateFlow()

    /** Re-read what the computer remembers, for the project currently open. */
    fun refreshMemories() {
        val client = memoryClient
        if (client == null) {
            _memoryError.value = "Not connected to your computer."
            return
        }

        _memoryLoading.value = true
        _memoryError.value = null

        viewModelScope.launch {
            runCatching { client.list(_projects.value.activeProjectId) }
                .onSuccess { _memories.value = it }
                .onFailure {
                    _memories.value = emptyList()
                    _memoryError.value = it.message ?: "Your computer would not answer."
                }
            _memoryLoading.value = false
        }
    }

    /**
     * Forget one line.
     *
     * Removed from the list straight away rather than after a re-read: the request
     * either succeeds or reports, and leaving a line the user has just told the app
     * to forget sitting on screen while a round trip completes reads as the tap
     * having done nothing.
     */
    fun forgetMemory(entry: MemoryEntry) {
        val client = memoryClient ?: return
        val before = _memories.value
        _memories.value = before.filterNot { it.id == entry.id }

        viewModelScope.launch {
            runCatching { client.forget(entry) }
                .onFailure {
                    // Put it back. A memory that is still on the computer must not
                    // look gone on the phone.
                    _memories.value = before
                    _memoryError.value = it.message ?: "Your computer would not forget it."
                }
        }
    }

    private var schedulerClient: Scheduler? = null

    private val _tasks = MutableStateFlow<List<ScheduledTask>>(emptyList())

    /** What the computer runs on its own. */
    val tasks: StateFlow<List<ScheduledTask>> = _tasks.asStateFlow()

    private val _tasksLoading = MutableStateFlow(false)
    val tasksLoading: StateFlow<Boolean> = _tasksLoading.asStateFlow()

    /** Why the list is empty, when the reason is not "nothing is scheduled". */
    private val _tasksError = MutableStateFlow<String?>(null)
    val tasksError: StateFlow<String?> = _tasksError.asStateFlow()

    private val _draftWhen = MutableStateFlow<ParsedWhen?>(null)

    /** What the computer made of the phrase being typed, or null if not yet anything. */
    val draftWhen: StateFlow<ParsedWhen?> = _draftWhen.asStateFlow()

    private val _creatingTask = MutableStateFlow(false)
    val creatingTask: StateFlow<Boolean> = _creatingTask.asStateFlow()

    private var parseJob: Job? = null

    /**
     * Ask the computer what a typed phrase means, as it is typed.
     *
     * Debounced and single-flighted: this runs on a keystroke, and without
     * cancelling the previous one a fast typist gets several answers back in
     * whatever order the network returns them — so the preview would settle on
     * whichever *older* phrase happened to arrive last.
     */
    fun parseWhen(text: String) {
        parseJob?.cancel()

        val client = schedulerClient
        if (client == null || text.isBlank()) {
            _draftWhen.value = null
            return
        }

        parseJob = viewModelScope.launch {
            delay(PARSE_DEBOUNCE_MS)
            _draftWhen.value = runCatching { client.parseWhen(text) }.getOrNull()
        }
    }

    /**
     * Create a task from what was typed.
     *
     * The recurrence is the one the computer returned for this exact phrase, handed
     * back untouched. Refuses rather than guessing when nothing was understood: a
     * task created with an invented schedule runs at a time nobody chose, and
     * nothing about it looks wrong afterwards.
     */
    fun createTask(prompt: String, onDone: () -> Unit) {
        val client = schedulerClient
        val parsed = _draftWhen.value

        if (client == null) {
            _tasksError.value = "Not connected to your computer."
            return
        }
        if (parsed == null) {
            _tasksError.value = "Say when it should run — “every weekday at 7am”."
            return
        }

        _creatingTask.value = true
        _tasksError.value = null

        viewModelScope.launch {
            runCatching {
                client.create(
                    prompt = prompt.trim(),
                    // Left to the computer, which already derives one from the prompt
                    // and words it the same way as every task made at the desk.
                    name = null,
                    recurrence = parsed.recurrence,
                    projectId = null,
                )
            }
                .onSuccess {
                    _draftWhen.value = null
                    onDone()
                }
                .onFailure {
                    _tasksError.value = it.message ?: "Your computer would not take it."
                }
            _creatingTask.value = false
            refreshTasks()
        }
    }

    private val _taskRunning = MutableStateFlow<String?>(null)

    /** The task a run was started for from here, while it is still going. */
    val taskRunning: StateFlow<String?> = _taskRunning.asStateFlow()

    /**
     * Start a task now, whatever its schedule says.
     *
     * The list is re-read afterwards rather than patched locally: the run writes a
     * new entry, a duration and an outcome on the computer, and inventing a local
     * version of any of that would be a second copy of the truth that is wrong the
     * moment the run ends differently than expected.
     */
    fun runTaskNow(id: String) {
        val client = schedulerClient
        if (client == null) {
            _tasksError.value = "Not connected to your computer."
            return
        }

        _taskRunning.value = id
        _tasksError.value = null

        viewModelScope.launch {
            runCatching { client.runNow(id) }
                .onFailure {
                    _tasksError.value = it.message ?: "Your computer would not run it."
                }
            _taskRunning.value = null
            refreshTasks()
        }
    }

    fun refreshTasks() {
        val client = schedulerClient
        if (client == null) {
            _tasksError.value = "Not connected to your computer."
            return
        }

        _tasksLoading.value = true
        _tasksError.value = null

        viewModelScope.launch {
            runCatching { client.list() }
                .onSuccess { _tasks.value = it }
                .onFailure {
                    _tasks.value = emptyList()
                    _tasksError.value = it.message ?: "Your computer would not answer."
                }
            _tasksLoading.value = false
        }
    }

    private var uploads: Uploads? = null

    private val _attachments = MutableStateFlow<List<UploadState>>(emptyList())

    /** Files attached to the message being written, and how each is getting on. */
    val attachments: StateFlow<List<UploadState>> = _attachments.asStateFlow()

    /**
     * Start sending a file the user picked.
     *
     * Uploaded the moment it is chosen rather than when the message is sent, so the
     * waiting happens while they are still typing instead of after they have asked
     * for something. By the time send is pressed the bytes are usually already there.
     */
    private val photoShrinker = dev.anodex.mobile.chat.PhotoShrinker(application)

    fun attach(uri: android.net.Uri) {
        val client = uploads ?: return

        viewModelScope.launch {
            // A large photo is made smaller first: sent as the camera wrote it, a shot
            // is several megabytes over mobile data. See `PhotoShrinker`.
            val sendable = photoShrinker.shrinkIfLarge(uri)
            // Same reason as `openWorkspaceFile`: a suspend call over a socket that
            // may already be dying. `send` below reports through `Result`; this one
            // had nothing.
            val file = runCatching { client.describe(sendable) }.getOrNull() ?: return@launch

            // Matched on what is actually being sent, which for a shrunk photo is the
            // smaller copy rather than the picture that was picked.
            fun update(state: UploadState) {
                _attachments.value = _attachments.value.map {
                    if (fileOf(it).uri == file.uri) state else it
                }
            }

            _attachments.value = _attachments.value + UploadState.Sending(file, 0f)

            client.send(file) { fraction -> update(UploadState.Sending(file, fraction)) }
                .onSuccess { update(UploadState.Done(file, it)) }
                .onFailure { update(UploadState.Failed(file, it.message ?: "That didn't send.")) }
        }
    }

    /**
     * Take a file back off the message.
     *
     * Tells the computer to forget the bytes when they already arrived. Without that
     * the upload directory keeps everything anybody ever changed their mind about.
     */
    fun removeAttachment(state: UploadState) {
        _attachments.value = _attachments.value.filterNot { fileOf(it).uri == fileOf(state).uri }

        val done = state as? UploadState.Done ?: return
        viewModelScope.launch { uploads?.discard(done.uploaded.path) }
    }

    private fun fileOf(state: UploadState) = when (state) {
        is UploadState.Sending -> state.file
        is UploadState.Done -> state.file
        is UploadState.Failed -> state.file
    }

    /**
     * Send the message, with whatever finished uploading attached.
     *
     * The rule the design rests on: the computer never sees a message carrying an
     * attachment until that attachment is whole. So anything still in flight or
     * failed is simply not part of this message — it stays in the composer, and the
     * text goes without it rather than the whole thing being blocked.
     */
    fun sendMessage(text: String) {
        val session = _chat.value ?: return
        val ready = _attachments.value.filterIsInstance<UploadState.Done>()
        val leftBehind = _attachments.value.filterIsInstance<UploadState.Sending>()

        session.send(text, ready.map { it.uploaded })

        // Only the ones that went. Anything still uploading is still the user's.
        _attachments.value = _attachments.value.filterNot { it is UploadState.Done }

        // And said, rather than left to be noticed.
        //
        // Sending without an unfinished attachment is the right call — the rule the
        // whole design rests on is that the computer never sees a message carrying
        // an attachment until that attachment is whole, and blocking the text on a
        // slow uplink would be worse. But it was happening silently: you attach a
        // photo, type a line, send, and the message goes without the photo while
        // the photo sits in the composer looking like it is still queued for it.
        //
        // From the outside that is indistinguishable from the attachment having been
        // ignored, which is exactly how it gets reported.
        if (leftBehind.isNotEmpty()) {
            val names = leftBehind.joinToString(", ") { it.file.name }
            _notice.value = if (leftBehind.size == 1) {
                "Sent without $names — it had not finished uploading. It is still in " +
                    "the composer; send again when it has."
            } else {
                "Sent without $names — they had not finished uploading. They are still " +
                    "in the composer."
            }
        }
    }


    private var personalityClient: Personalities? = null

    private var modelClient: Models? = null

    private val _installedModels = MutableStateFlow<List<LocalModel>>(emptyList())

    /** What is already on the computer, ready to load. Never what could be downloaded. */
    val installedModels: StateFlow<List<LocalModel>> = _installedModels.asStateFlow()

    /** The path being loaded, while it loads. Null when nothing is. */
    private val _loadingModelPath = MutableStateFlow<String?>(null)
    val loadingModelPath: StateFlow<String?> = _loadingModelPath.asStateFlow()

    private fun refreshModels() {
        val client = modelClient ?: return
        viewModelScope.launch {
            // Keeps the list it had on a failure. An empty model picker reads as "you
            // have no models installed", which is a claim about the computer's disk
            // made on the strength of a request that did not arrive.
            runCatching { client.list() }.onSuccess { _installedModels.value = it }
        }
    }

    /**
     * Load a model that is already on the computer.
     *
     * Minutes, not seconds, and it moves the machine out from under anyone sitting
     * at it — so the row stays visibly busy for the whole load rather than appearing
     * to do nothing. The phone sends only the path: context size and GPU layers are
     * tuned per machine in the desktop's settings, which it cannot read, so it says
     * nothing and lets the computer fill them in.
     */
    fun loadModel(path: String) {
        val client = modelClient ?: return
        if (_loadingModelPath.value != null) return
        _loadingModelPath.value = path

        viewModelScope.launch {
            runCatching { client.load(path) }
                .onFailure { _projectError.value = it.message ?: "That model didn't load." }
            _loadingModelPath.value = null

            // The header reads the engine, so it has to be re-asked: the model it was
            // showing is not the one running any more.
            socket?.let { open ->
                controller.onModelUpdated(runCatching { readModelState(open) }.getOrNull())
            }
        }
    }

    private val _personalities = MutableStateFlow(PersonalityState(null, emptyList()))

    /** How Anodex is set to answer, and what else it could be. */
    val personalities: StateFlow<PersonalityState> = _personalities.asStateFlow()

    private val pictures = PersonalityPictures(java.io.File(application.cacheDir, "personality-pictures"))

    /** Pictures for the user's own personalities, by id. Built-ins draw their shipped art. */
    val personalityPictures: StateFlow<Map<String, ImageBitmap>> = pictures.byId

    /**
     * Fetch whatever pictures the phone lacks for the list it now has.
     *
     * After the list rather than inside it, so a slow picture never holds up the
     * chooser, and guarded: a computer too old to have `personality:image` refuses
     * the channel, which must cost faces, not the connection.
     */
    private fun syncPersonalityPictures(state: PersonalityState) {
        val client = personalityClient ?: return
        viewModelScope.launch {
            runCatching { pictures.sync(state.personalities) { id -> client.picture(id) } }
        }
    }

    private val _personalityBusy = MutableStateFlow(false)
    val personalityBusy: StateFlow<Boolean> = _personalityBusy.asStateFlow()

    /**
     * The personality in force right now, as a reply should be stamped with it.
     *
     * Null before the computer has answered — better an unlabelled reply than one
     * labelled with a guess, since the label exists precisely so somebody can tell
     * which personality said a thing.
     */
    private fun currentPersona(): MessagePersona? {
        val state = _personalities.value
        val active = state.personalities.firstOrNull { it.id == state.active } ?: return null
        return MessagePersona(active.id, active.name, active.tint)
    }

    /**
     * Re-read the personalities, and fetch any picture the phone lacks.
     *
     * On connect, and now also when the app comes back and when Settings opens. The
     * desktop does not announce a personality edited at the computer, so a phone that
     * stayed connected kept the list from whenever it connected: a personality made on
     * the desktop did not appear here until the phone reconnected, and one deleted
     * there could still be chosen here.
     */
    fun refreshPersonalities() {
        val client = personalityClient ?: return
        viewModelScope.launch {
            // Never a reason to fail a connection — but "the list is empty" and
            // "the list did not arrive" are different things, and blanking it said
            // the first when it meant the second. Keeps what it had; a stale
            // personality list is honest in a way an empty one is not, because
            // those personalities do still exist on the computer.
            //
            // The error is dropped rather than shown: this is the one read in the
            // app with nowhere to put it, and a personality list that is one refresh
            // out of date is not worth a line on the settings screen.
            _personalities.value = runCatching { client.state() }
                .orKeep(_personalities.value, "Could not read the personalities.")
                .value
            syncPersonalityPictures(_personalities.value)
        }
    }

    /**
     * Change how Anodex answers.
     *
     * Global, like the active project: it moves for whoever is at the computer too.
     * The new state comes back from the computer rather than being assumed here, so
     * a refusal leaves the phone showing what is actually in force instead of a
     * selection that never took.
     */
    fun setPersonality(id: String?) {
        val client = personalityClient ?: return
        _personalityBusy.value = true

        viewModelScope.launch {
            runCatching { client.setActive(id) }
                .onSuccess { _personalities.value = it }
                .onFailure { _projectError.value = it.message ?: "That didn't work." }
            _personalityBusy.value = false
        }
    }

    private val _openFile = MutableStateFlow<String?>(null)

    /** The file being read, or null when nothing is open. */
    val openFile: StateFlow<String?> = _openFile.asStateFlow()

    private val _openFileContent = MutableStateFlow<FileContent?>(null)
    val openFileContent: StateFlow<FileContent?> = _openFileContent.asStateFlow()

    /**
     * Open one of the project's files, as it is on the computer right now.
     *
     * Always a live read. A file the phone showed ten minutes ago may have been
     * rewritten twice since, and stale source presented as current is worse than
     * none.
     */
    fun openWorkspaceFile(relativePath: String) {
        _openFile.value = relativePath
        _openFileContent.value = null

        val client = workspace
        if (client == null) {
            _openFileContent.value = FileContent.Failed("Not connected to your computer.")
            return
        }

        viewModelScope.launch {
            // Guarded, because the socket can die mid-read and `read` is a call
            // awaiting a reply that will now never come. `failPending` resumes every
            // in-flight call with the failure, so an unguarded `launch` here does not
            // fail the read — it takes the process down.
            //
            // This is the crash reported on 2026-09-10: a ping timeout after fifteen
            // good ones, on a desktop that stopped answering, while a file was open.
            // The branch three lines above already knew how to say "not connected";
            // the read path simply never reached it.
            _openFileContent.value = runCatching { client.read(relativePath) }
                .getOrElse { FileContent.Failed(it.message ?: "Lost the connection mid-read.") }
        }
    }

    fun closeWorkspaceFile() {
        _openFile.value = null
        _openFileContent.value = null
    }

    /**
     * Move the open file to the computer's Recycle Bin.
     *
     * The only thing the phone does to a project that is not reading it, and the
     * screen asks first. The desktop's handler uses `shell.trashItem`, so the
     * file lands somewhere a person can get it back from — which is why this is
     * a confirmation and not a locked door.
     *
     * Closes the reader on the way out. Leaving a file on screen after deleting
     * it shows content that is no longer at that path, which is the same
     * dishonesty [openWorkspaceFile] refuses a cache for.
     */
    fun deleteWorkspaceFile(relativePath: String) {
        val client = workspace
        if (client == null) {
            _workspaceError.value = "Not connected to your computer."
            return
        }

        closeWorkspaceFile()

        viewModelScope.launch {
            // Guarded for the same reason the read is: the socket can die while
            // the call is in flight, and `failPending` would otherwise take the
            // process down rather than the request.
            val failure = runCatching { client.trash(relativePath) }
                .getOrElse { it.message ?: "Lost the connection mid-delete." }
            if (failure != null) {
                _workspaceError.value = failure
                return@launch
            }
            // The listing still has the file in it until it is asked again.
            refreshWorkspaceFiles()
        }
    }

    private val _newerVersion = MutableStateFlow<String?>(null)

    /**
     * The phone build the desktop expects, when this one is behind it.
     *
     * Null means nothing to say — either the versions match, this build is ahead, or
     * the desktop is old enough not to send one. All three are silent on purpose.
     */
    val newerVersion: StateFlow<String?> = _newerVersion.asStateFlow()

    private val updater = Updater(application)

    private val appearance = AppearanceStore(application)

    /**
     * How this app picks its palette.
     *
     * The only setting here that belongs to the phone rather than the computer.
     * Everything else in Settings moves for whoever is at the desk too; this one is
     * about the screen in your hand at midnight.
     */
    val themeMode: StateFlow<ThemeMode> = appearance.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_THEME_MODE)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { appearance.setThemeMode(mode) }
    }

    /** How large the interface is set, and in what face. Phone-local, like the theme. */
    val fontScale: StateFlow<FontScale> = appearance.fontScale
        .stateIn(viewModelScope, SharingStarted.Eagerly, FontScale.MEDIUM)

    fun setFontScale(scale: FontScale) {
        viewModelScope.launch { appearance.setFontScale(scale) }
    }

    val uiFont: StateFlow<UiFont> = appearance.uiFont
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiFont.SYSTEM)

    fun setUiFont(font: UiFont) {
        viewModelScope.launch { appearance.setUiFont(font) }
    }

    val keepAwake: StateFlow<Boolean> = appearance.keepAwake
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setKeepAwake(enabled: Boolean) {
        viewModelScope.launch { appearance.setKeepAwake(enabled) }
    }

    val motion: StateFlow<MotionPreference> = appearance.motion
        .stateIn(viewModelScope, SharingStarted.Eagerly, MotionPreference.SYSTEM)

    fun setMotion(preference: MotionPreference) {
        viewModelScope.launch { appearance.setMotion(preference) }
    }

    val haptics: StateFlow<Boolean> = appearance.haptics
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setHaptics(enabled: Boolean) {
        viewModelScope.launch { appearance.setHaptics(enabled) }
    }

    val streamOnMetered: StateFlow<Boolean> = appearance.streamOnMetered
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setStreamOnMetered(enabled: Boolean) {
        viewModelScope.launch {
            appearance.setStreamOnMetered(enabled)
            tellComputerAboutTokens()
        }
    }

    /**
     * Tell the computer whether to send tokens as it generates them.
     *
     * Sent whenever the answer changes and whenever a socket is established, because
     * the preference lives on the desktop for the life of that connection and a
     * reconnect starts it back at "send everything".
     *
     * Failure is ignored on purpose. An older desktop has no such channel, and the
     * result of it refusing is the behaviour that existed before this setting did.
     */
    private fun tellComputerAboutTokens() {
        val open = socket ?: return
        val wanted = streamOnMetered.value || !networkMonitor.onMeteredNetwork()
        viewModelScope.launch {
            runCatching { open.invoke(CHANNEL_SET_LIVE_TOKENS, listOf(JsonPrimitive(wanted))) }
        }
    }

    /** Whether a reply's thinking is open on screen while the reply is still being written. */
    @Volatile private var liveThinkingWanted = false

    /**
     * Somebody opened, or closed, the thinking of a reply still being written.
     *
     * The computer sends thinking only while it is open: it is often most of what a
     * reasoning model writes, and read far less often than the reply.
     */
    fun setLiveThinking(wanted: Boolean) {
        if (liveThinkingWanted == wanted) return
        liveThinkingWanted = wanted
        tellComputerAboutThinking()
    }

    /**
     * Tell the computer whether to send thinking as it is written. Said on every new
     * socket, like [tellComputerAboutTokens], and ignored by a computer too old to ask:
     * that one sends thinking regardless, which the chat shows as it arrives.
     */
    private fun tellComputerAboutThinking() {
        val open = socket ?: return
        val wanted = liveThinkingWanted
        viewModelScope.launch {
            runCatching { open.invoke(CHANNEL_SET_LIVE_THINKING, listOf(JsonPrimitive(wanted))) }
        }
    }

    /**
     * Whether a reply is arriving right now.
     *
     * Watches the messages rather than [ChatSession.sending], because since the
     * desktop started broadcasting a running turn, a reply can be arriving here that
     * this phone did not send — which is exactly the case where somebody is watching
     * and the screen must not sleep.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val replyArriving: StateFlow<Boolean> = _chat
        .flatMapLatest { session ->
            session?.messages?.map { turns -> turns.any { it.streaming } } ?: flowOf(false)
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * What a *manual* check found.
     *
     * Separate from [update], which drives the banner, because the two answer
     * different questions. The banner exists to interrupt somebody who was not
     * asking; this exists because somebody tapped a button and is owed a reply.
     *
     * Without it a manual check that finds nothing is indistinguishable from a
     * manual check that did nothing — `checkForUpdate` returns silently when there
     * is no newer release, which is right for the automatic path and reads as a
     * broken button on the manual one.
     */
    private val _updateCheck = MutableStateFlow<UpdateCheck>(UpdateCheck.Idle)
    val updateCheck: StateFlow<UpdateCheck> = _updateCheck.asStateFlow()

    /**
     * Ask now, and say what came back.
     *
     * Ignores the interval that throttles the automatic check: a person who taps
     * this has a reason, and "we looked recently" is not an answer to it.
     */
    fun checkForUpdateNow() {
        if (_updateCheck.value is UpdateCheck.Checking) return
        _updateCheck.value = UpdateCheck.Checking

        viewModelScope.launch {
            val found = runCatching { updater.check(BuildConfig.VERSION_NAME) }
            lastUpdateCheck = System.currentTimeMillis()

            _updateCheck.value = found.fold(
                onSuccess = { release ->
                    if (release == null) {
                        UpdateCheck.UpToDate
                    } else {
                        // Feed the banner too, so accepting from Settings and
                        // accepting from the banner are the same code path.
                        _update.value = UpdateState.Available(release)
                        UpdateCheck.Found(release.version)
                    }
                },
                onFailure = { failure ->
                    UpdateCheck.Failed(
                        failure.message?.takeIf { it.isNotBlank() } ?: "Could not reach GitHub."
                    )
                },
            )
        }
    }

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)

    /** Finding, fetching and handing over a newer build of this app. */
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    /** Whether the user has waved the banner away for this run of the app. */
    private val _updateDismissed = MutableStateFlow(false)
    val updateDismissed: StateFlow<Boolean> = _updateDismissed.asStateFlow()

    private var lastUpdateCheck = 0L

    /**
     * Ask GitHub whether there is a newer build.
     *
     * Three triggers, because they know different things: the desktop knows what it
     * was tested against, GitHub knows what actually exists, and only GitHub is
     * reachable before the phone has connected to anything.
     *
     * The third is every return to the foreground, and it is here because of a real
     * gap. Checking once per process meant reopening from recents — which resumes the
     * existing process rather than starting one — never checked again, so an update
     * published while the app sat in the background stayed invisible until the
     * desktop was restarted and the handshake happened to mention it.
     *
     * Throttled, because that trigger fires often and the GitHub API allows sixty
     * anonymous requests an hour per address. A new build is not urgent to the minute.
     *
     * Silent when there is nothing to say. Nobody asked for this, so a failed check is
     * not worth a message.
     */
    fun checkForUpdate(force: Boolean = false) {
        if (_update.value is UpdateState.Downloading || _update.value is UpdateState.Ready) return

        val now = System.currentTimeMillis()
        if (!force && now - lastUpdateCheck < UPDATE_CHECK_INTERVAL_MS) return
        lastUpdateCheck = now

        viewModelScope.launch {
            val release = updater.check(BuildConfig.VERSION_NAME) ?: return@launch
            _update.value = UpdateState.Available(release)
        }
    }

    /**
     * Download it, verify it, and hand it to Android's installer.
     *
     * The app cannot install anything itself — it can only ask, and the user then sees
     * Android's own update screen. That is the ceiling for an app distributed outside
     * a store, and it is still worth doing: the alternative is finding a GitHub page
     * on a phone and driving a browser download by hand.
     */
    fun installUpdate() {
        val release = when (val state = _update.value) {
            is UpdateState.Available -> state.release
            is UpdateState.Failed -> state.release ?: return
            // Already fetched. Ask again rather than downloading it twice — the user
            // may have declined Android's screen the first time.
            is UpdateState.Ready -> return updater.install(state.file)
            else -> return
        }

        _update.value = UpdateState.Downloading(release, 0f)

        viewModelScope.launch {
            updater.download(release) { fraction ->
                _update.value = UpdateState.Downloading(release, fraction)
            }.onSuccess { file ->
                _update.value = UpdateState.Ready(release, file)
                updater.install(file)
            }.onFailure { error ->
                _update.value = UpdateState.Failed(release, error.message ?: "The update failed.")
            }
        }
    }

    /** Whether this phone will let the app hand an APK to the installer at all. */
    fun canInstallUpdates(): Boolean = updater.canRequestInstall()

    fun installPermissionIntent() = updater.installPermissionIntent()

    fun dismissUpdate() {
        _updateDismissed.value = true
    }

    private val _contextUsage = MutableStateFlow<ContextUsage?>(null)

    /**
     * How full the open conversation's context is, or null when nobody has said.
     *
     * Null is the ordinary state and not a fault: there is no reading before a
     * conversation has any messages, none when no model is loaded, and none while
     * the computer is unreachable. The ring draws nothing at all for null, which is
     * the honest rendering of "not known" and distinguishable from a context that
     * really is empty.
     *
     * Read from the computer rather than worked out here — see [ContextUsage] for
     * why a phone cannot compute this one for itself.
     */
    val contextUsage: StateFlow<ContextUsage?> = _contextUsage.asStateFlow()

    /**
     * Ask the computer how full a conversation's context is.
     *
     * Cheap enough to call on every turn boundary: it is a projection over a
     * conversation the computer already has in memory, not a generation.
     */
    /**
     * The open conversation, each time it stops changing.
     *
     * Null while a turn is in flight, and null when there is no session at all.
     *
     * `flatMapLatest` because the session is replaced on reconnect and whenever a
     * different conversation is opened; without it the collector would go on watching
     * a session nobody is looking at and report its context as the one on screen.
     *
     * Emitting only on a settled turn is what makes this affordable. Mid-stream the
     * reply is half-written, so a reading taken then describes a conversation that no
     * longer exists by the time the answer arrives — and asking per token would be a
     * round trip to the computer thirty times a second.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun settledTurns(): Flow<String?> =
        _chat
            .flatMapLatest { session ->
                if (session == null) {
                    flowOf(null)
                } else {
                    combine(session.messages, session.sending) { messages, sending ->
                        // The count is in the key so a finished turn re-measures, but
                        // the id is what the caller needs.
                        if (sending) null else session.conversationId to messages.size
                    }
                }
            }
            .distinctUntilChanged()
            .map { it?.first }

    fun refreshContextUsage(conversationId: String?) {
        val open = socket
        if (open == null || conversationId == null) {
            _contextUsage.value = null
            return
        }

        viewModelScope.launch {
            // `.unwrap()` because this handler answers with `ok(value)`. Without it the
            // parser is handed the envelope, finds no `usedTokens` on it, and reports
            // nothing — which looks exactly like a channel that does not work. It did,
            // for a whole round of believing this was fixed.
            val reading = runCatching {
                open.invoke("chat:context-usage", listOf(JsonPrimitive(conversationId))).unwrap()
            }.getOrNull()

            // Kept only while it is still about what the screen is showing. A reply
            // that lands after the user has opened a different conversation is a
            // measurement of the one they left.
            if (_chat.value?.conversationId != conversationId) return@launch
            _contextUsage.value = contextUsageFrom(reading, conversationId)
        }
    }

    private val _unreadEmail = MutableStateFlow<Int?>(null)

    /**
     * Unread threads, for the tab badge. **Null means the count is not known.**
     *
     * Fetched on connect rather than when the Email tab is opened, because a badge
     * that only appears once you have already looked is telling you something you
     * necessarily already know.
     *
     * Nullable rather than defaulting to zero, which is what it used to do. Zero is
     * a claim — "there is nothing waiting for you" — and a request that never
     * arrived is not evidence for it. That claim was reaching two places at once:
     * the drawer's badge, and the opener on an empty chat screen offering to sweep
     * the mail. Not knowing shows neither, which is the truthful version of both.
     */
    val unreadEmail: StateFlow<Int?> = _unreadEmail.asStateFlow()

    /**
     * Re-read the count. On connect, and whenever the badge is about to be seen.
     *
     * Connect alone was not enough. A connection outlives a trip to another app, so
     * the drawer said 4 for as long as the socket held while the inbox itself said
     * 2 — the badge only caught up once the Email screen was opened, which is the
     * one moment it has nothing left to tell you.
     *
     * A failed re-read keeps the count it had. Resume is exactly when the socket is
     * most likely to be mid-reconnect, and a badge that blinks out every time the
     * app comes back is not more truthful than one that is a minute old.
     */
    fun refreshUnreadEmail() {
        val client = emailClient ?: return
        viewModelScope.launch {
            _unreadEmail.value = runCatching<Int?> { client.unreadCount() }
                .orKeep(_unreadEmail.value, whenItFails = "")
                .value
        }
    }

    fun refreshEmail() {
        val client = emailClient ?: return
        viewModelScope.launch {
            _emailLoading.value = true
            // Asked first, so an empty result can be reported as "no account" rather
            // than as "no mail" -- the two look identical in a list and mean opposite
            // things to somebody waiting on a message.
            _emailError.value = null

            runCatching { client.isConfigured() }
                .onSuccess { _emailConfigured.value = it }
                .onFailure {
                    // Left as it was rather than set to false. Whether an account
                    // exists is a fact about the computer, and failing to ask is not
                    // evidence either way.
                    _emailError.value = it.message ?: "Your computer would not answer."
                }

            runCatching { client.threads() }
                .onSuccess { _emailThreads.value = it }
                .onFailure {
                    _emailError.value = it.message ?: "Your computer would not answer."
                }

            _emailLoading.value = false
            // Re-read after listing, so acting on mail at the computer is reflected
            // here rather than leaving a badge that outlives what it counted.
            refreshUnreadEmail()
        }
    }

    fun openEmailThread(thread: EmailThread) {
        val client = emailClient ?: return
        viewModelScope.launch {
            _threadLoading.value = true
            runCatching {
                client.messages(thread.id, thread.accountId.takeIf { it.isNotBlank() })
            }
                .onSuccess { _openThread.value = it }
                .onFailure {
                    // An empty thread would read as a message with no content, which
                    // is a thing that cannot happen and so gets believed.
                    _openThread.value = emptyList()
                    _emailError.value = it.message ?: "That thread would not open."
                }
            _threadLoading.value = false
        }
    }

    fun closeEmailThread() {
        _openThread.value = null
    }

    private val _agentsError = MutableStateFlow<String?>(null)

    /** Why a run could not be started, or the list could not be read. */
    val agentsError: StateFlow<String?> = _agentsError.asStateFlow()

    private val _startingRun = MutableStateFlow(false)
    val startingRun: StateFlow<Boolean> = _startingRun.asStateFlow()

    /**
     * Set a build going on the computer, from wherever you are.
     *
     * Runs against whichever project is open, because a run with no project has no
     * files to work on — and picking one from here would be choosing where real
     * edits land from a screen that cannot show the folder.
     *
     * The computer forces the plan gate on for anything started remotely, so this
     * does not begin work: it begins a plan, which then waits on the Agents screen
     * for a yes. That is the whole reason it is safe to offer from a phone.
     */
    fun startAgentRun(goal: String, lookOnly: Boolean, onStarted: () -> Unit) {
        val client = agentClient
        val projectId = _projects.value.activeProjectId

        if (client == null) {
            _agentsError.value = "Not connected to your computer."
            return
        }
        if (projectId == null) {
            _agentsError.value = "Open a project first — a run needs files to work on."
            return
        }
        if (goal.isBlank()) return

        _startingRun.value = true
        _agentsError.value = null

        viewModelScope.launch {
            runCatching { client.start(goal.trim(), projectId, lookOnly) }
                .onSuccess { onStarted() }
                .onFailure {
                    _agentsError.value = it.message ?: "Your computer would not start it."
                }
            _startingRun.value = false
            refreshAgentRuns()
        }
    }

    fun refreshAgentRuns() {
        val client = agentClient
        if (client == null) {
            _agentsError.value = "Not connected to your computer."
            return
        }

        viewModelScope.launch {
            _agentsLoading.value = true
            _agentsError.value = null
            runCatching { client.list() }
                .onSuccess { _agentRuns.value = it }
                .onFailure {
                    // Was `getOrDefault(emptyList())`, which turned every failure
                    // into "no agent runs" — a computer that could not be reached
                    // and one with nothing running looked exactly alike.
                    _agentRuns.value = emptyList()
                    _agentsError.value = it.message ?: "Your computer would not answer."
                }
            _agentsLoading.value = false
        }
    }

    /**
     * Answer or end a run.
     *
     * Re-reads the list afterwards rather than guessing the new state: approving a
     * plan makes the desktop start work, and what the run becomes is its business
     * to report, not the phone's to predict.
     */
    /** Approve or Reject pressed on a plan's notification. See [RunActionReceiver]. */
    private val runActionHandler: (String, Boolean) -> Unit = { runId, approve ->
        if (approve) approvePlan(runId) else rejectPlan(runId)
    }

    /** A reply typed into an "answer ready" notification. See [ReplyReceiver]. */
    private val replyHandler: (String, String) -> Boolean = { conversationId, text ->
        replyFromNotification(conversationId, text)
    }

    init {
        RunActionBridge.handler = runActionHandler
        ReplyBridge.handler = replyHandler
    }

    /**
     * Send a reply typed into a notification, in that notification's conversation.
     *
     * The conversation on screen takes it directly. Any other is opened first, the way
     * tapping the notification would, and the reply goes once it is loaded. False when
     * there is no connection to send it through.
     */
    fun replyFromNotification(conversationId: String, text: String): Boolean {
        val current = _chat.value
        if (current != null && current.conversationId == conversationId) {
            if (current.sending.value) return false
            current.send(text)
            return true
        }
        if (socket == null || conversationReader == null) return false
        openConversation(conversationId) { opened -> opened.send(text) }
        return true
    }

    private fun actOnRun(runId: String, action: suspend (Agents) -> Unit) {
        val client = agentClient ?: return
        _busyRunId.value = runId
        viewModelScope.launch {
            runCatching { action(client) }
            _busyRunId.value = null
            refreshAgentRuns()
        }
    }

    private val _openRunId = MutableStateFlow<String?>(null)

    /** The run being followed on its own page, or null for the list. */
    val openRunId: StateFlow<String?> = _openRunId.asStateFlow()

    private val _runTurns = MutableStateFlow(RunTurnsState())

    /** That run's turns, as the computer's run page shows them. */
    val runTurns: StateFlow<RunTurnsState> = _runTurns.asStateFlow()

    /**
     * Follow one run: its plan, meters and every turn, updating as it works.
     *
     * The run itself comes live already — the computer pushes the whole list on every
     * change. The turns are read on open and again on each of those pushes, so a new
     * turn appears on the page within a moment of the computer recording it.
     */
    fun openRun(runId: String) {
        _openRunId.value = runId
        _runTurns.value = RunTurnsState(loading = true)
        refreshRunTurns()
    }

    fun closeRun() {
        _openRunId.value = null
        _runTurns.value = RunTurnsState()
    }

    private fun refreshRunTurns() {
        val runId = _openRunId.value ?: return
        val client = agentClient ?: return
        viewModelScope.launch {
            runCatching { client.turns(runId) }
                .onSuccess { turns ->
                    if (_openRunId.value == runId) _runTurns.value = RunTurnsState(turns = turns)
                }
                // Keeps the turns it had. A read that failed mid-run is not a run that
                // lost its history, and blanking the page would say it was.
                .onFailure { failure ->
                    if (_openRunId.value == runId) {
                        _runTurns.value = _runTurns.value.copy(
                            loading = false,
                            error = failure.message ?: "Could not read this run's turns.",
                        )
                    }
                }
        }
    }

    fun approvePlan(runId: String) = actOnRun(runId) { it.approvePlan(runId) }

    fun rejectPlan(runId: String) = actOnRun(runId) { it.rejectPlan(runId) }

    fun stopAgentRun(runId: String) = actOnRun(runId) { it.stop(runId) }

    private val _projects = MutableStateFlow(ProjectsState(emptyList(), null))

    /** What projects exist on the computer, and which one is open. */
    val projects: StateFlow<ProjectsState> = _projects.asStateFlow()

    private val _projectBusy = MutableStateFlow(false)
    val projectBusy: StateFlow<Boolean> = _projectBusy.asStateFlow()

    private val _projectError = MutableStateFlow<String?>(null)
    val projectError: StateFlow<String?> = _projectError.asStateFlow()

    fun refreshProjects() {
        val client = projectClient ?: return
        viewModelScope.launch {
            // Keeps whatever it had and says what went wrong, instead of replacing
            // the list with an empty one — an empty project list is the sentence "no
            // projects on that computer yet", which is a claim about the machine's
            // disk made on the strength of a request that did not arrive.
            val read = runCatching { client.state() }
                .orKeep(_projects.value, "Could not read your projects.")
            _projects.value = read.value
            _projectError.value = read.error
        }
    }

    /**
     * Change the project the computer is working in.
     *
     * Global, not a phone-local preference: this moves the workspace for whoever is
     * sitting at the desk too. The desktop refuses while it is mid-generation, and
     * that refusal is surfaced as "not now" rather than swallowed - the difference
     * between a wait and a failure is the whole message.
     *
     * Deliberately does **not** re-file the open chat. A plain chat belongs to no
     * project: only the workspace and agent runs touch work files, and a chat that
     * quietly acquired one would be able to edit real files because of a setting the
     * user changed for an unrelated reason.
     */
    fun setActiveProject(projectId: String?) {
        val client = projectClient ?: return
        _projectError.value = null
        _projectBusy.value = true

        viewModelScope.launch {
            try {
                _projects.value = client.setActive(projectId)

                // The files belong to the project, so changing one changes the other.
                // Without this, choosing a project from the Workspace screen left it
                // showing the old project's files — or nothing, which is worse,
                // because it looks like the choice did not take.
                refreshWorkspaceFiles()
            } catch (e: Exception) {
                _projectError.value = e.message ?: "That didn't work."
            } finally {
                _projectBusy.value = false
            }
        }
    }

    /**
     * Re-read the conversation list from the computer.
     *
     * Keeps what it already had when a read fails, rather than replacing it with an
     * empty list. The drawer is the app's navigation; blanking it on a dropped
     * connection loses the way back to everything, and says nothing about why.
     *
     * A stale list is honest here in a way an empty one is not: those conversations
     * do still exist on the computer, and the connection banner already says the
     * link is down.
     */
    private val _conversationsError = MutableStateFlow<String?>(null)

    /**
     * Why the conversation list is empty, when the reason is not "you have none".
     *
     * The one screen in the app that had no way to say this, and the one it matters
     * on most: Conversations is where the app opens. A first read that failed left
     * it showing "No conversations yet. Start one." — an invitation, phrased as a
     * fact about the user's history, produced by a request that never landed.
     *
     * Only meaningful alongside an empty list. A failed *refresh* that still has
     * conversations to show keeps showing them; the list is stale, not wrong.
     */
    val conversationsError: StateFlow<String?> = _conversationsError.asStateFlow()

    fun refreshConversations() {
        val reader = conversationReader ?: return
        viewModelScope.launch {
            _loadingConversations.value = true
            val read = runCatching { reader.list() }
                .orKeep(_conversations.value, "Could not read your conversations.")
            _conversations.value = read.value
            _conversationsError.value = read.error
            rememberRecentsForWidget(read.value)
            _loadingConversations.value = false
        }
    }

    private val _notice = MutableStateFlow<String?>(null)

    /**
     * Something the user needs told, with nothing to undo.
     *
     * Its own flow rather than a variant of [archiveNotice], because the two are
     * different events that happen to be shown in the same strip: one offers a way
     * back, and this one only reports.
     */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** The strip has said its piece. */
    fun dismissNotice() {
        _notice.value = null
    }

    private val _archiveNotice = MutableStateFlow<ArchiveNotice?>(null)

    /**
     * What was just archived and can still be put back, or the news that it failed.
     *
     * Cleared by the bar that shows it. Archiving asks nothing before it happens,
     * which is only defensible because this exists: the undo is the confirmation,
     * arriving after the tap instead of in front of it.
     */
    val archiveNotice: StateFlow<ArchiveNotice?> = _archiveNotice.asStateFlow()

    /**
     * Archive one conversation, and offer it back.
     *
     * Removed from the list here rather than after a re-read, for the same reason
     * forgetting a memory is: leaving a conversation the user has just archived
     * sitting on screen while a round trip completes reads as the tap having done
     * nothing. A failure puts it back and says so.
     */
    fun archiveConversation(conversationId: String) {
        val reader = conversationReader ?: return
        val summary = _conversations.value.firstOrNull { it.id == conversationId }
        val title = summary?.title ?: "Conversation"

        val before = _conversations.value
        _conversations.value = before.filterNot { it.id == conversationId }

        // A conversation cannot be read after it is archived, so leaving it open
        // would be a transcript of something the app has just said is gone. A fresh
        // empty chat is where archiving from the desktop leaves you too, and nothing
        // is written until the first message.
        if (_chat.value?.conversationId == conversationId) newConversation()

        viewModelScope.launch {
            runCatching { reader.archive(conversationId) }
                .onSuccess { _archiveNotice.value = ArchiveNotice(conversationId, title) }
                .onFailure {
                    _conversations.value = before
                    _archiveNotice.value = ArchiveNotice(conversationId, title, failed = true)
                }
        }
    }

    /** Put back the one just archived. */
    fun restoreArchived() {
        val reader = conversationReader ?: return
        val notice = _archiveNotice.value ?: return
        _archiveNotice.value = null

        viewModelScope.launch {
            runCatching { reader.restore(notice.conversationId) }
            // Re-read rather than re-inserting the summary this held: the computer
            // is the authority on what exists, and it has just been told twice.
            refreshConversations()
        }
    }

    /** The bar has said its piece. */
    fun dismissArchiveNotice() {
        _archiveNotice.value = null
    }

    /**
     * Open a conversation that already exists on the computer.
     *
     * Its turns are fetched rather than remembered, because the phone keeps none -
     * so this is a network call, and it is empty rather than stale when the desktop
     * cannot be reached.
     */
    /**
     * Search what was said in conversations, on the computer.
     *
     * Empty on failure, and deliberately so here: this adds matches to a title search
     * the screen has already answered locally. A computer that cannot search bodies —
     * an older build, or a dropped connection — leaves that answer standing rather
     * than replacing it with an error about a feature the person did not ask for.
     */
    suspend fun searchMessages(query: String): List<MessageMatch> {
        val reader = conversationReader ?: return emptyList()
        return runCatching { reader.search(query) }.getOrDefault(emptyList())
    }

    fun openConversation(
        conversationId: String,
        /**
         * What this phone was holding of it, for a conversation the computer may not have
         * saved yet. See [resumeAfterReconnect].
         */
        heldHere: OfflineChat? = null,
        /** Run with the conversation once it is open — a notification reply sends from here. */
        then: ((ChatSession) -> Unit)? = null,
    ) {
        notifications.cancel(Notifications.replyNotificationId(conversationId))
        val reader = conversationReader ?: return
        val open = socket ?: return
        viewModelScope.launch {
            // Not `getOrDefault(emptyList())`, which is how a conversation gets
            // destroyed. A failed read became an empty transcript bound to a real
            // conversation id, and the next message saved that one turn over
            // everything the computer had — the desktop's store writes what it is
            // given. A conversation that could not be read is left closed instead.
            //
            // Said out loud, too. Leaving it closed and silent is safe and looks
            // exactly like a broken app: the drawer shuts, nothing opens, and there is
            // nothing on screen to suggest the tap was even received.
            val found = runCatching { reader.open(conversationId) }.getOrNull()
            // Not on the computer yet is not the same as gone. A connection that drops
            // during a chat's first reply comes back before the computer has saved that
            // chat — it saves a phone's turn when the reply finishes — and reopening it
            // said "It is not on your computer any more" over an empty new chat while the
            // reply was still being written. It is shown from what this phone held
            // instead, and the computer's copy replaces it when the turn is saved.
            //
            // The computer now writes the question as the turn starts, so it can also have
            // the conversation and still be behind this phone: held here is the reply.
            val heldIsAhead = heldHere != null && heldHere.messages.isNotEmpty() &&
                (found == null || holdsMoreThanComputer(found.messages.map { it.id }, heldHere.messages.map { it.id }))
            if (heldIsAhead && heldHere != null) {
                val session = ChatSession(
                    socket = open,
                    scope = viewModelScope,
                    activePersona = ::currentPersona,
                    onAnswered = ::replyReady,
                    conversationId = conversationId,
                    initialMessages = heldHere.messages,
                    initialMessagesSaved = false,
                    projectId = heldHere.projectId,
                    initialApproval = pendingApprovals.forConversation(conversationId),
                    onApprovalAnswered = pendingApprovals::answered,
                )
                showChat(session)
                then?.invoke(session)
                return@launch
            }
            // A turn waiting on an approval in a conversation the computer has not written
            // yet (a computer older than 0.9.12 writes it only when the reply finishes). It
            // opens empty with the approval on it, rather than as "not on your computer
            // any more" with a turn stopped behind it.
            val waiting = pendingApprovals.forConversation(conversationId)
            if (found == null && waiting != null) {
                val session = ChatSession(
                    socket = open,
                    scope = viewModelScope,
                    activePersona = ::currentPersona,
                    onAnswered = ::replyReady,
                    conversationId = conversationId,
                    initialMessagesSaved = false,
                    initialApproval = waiting,
                    onApprovalAnswered = pendingApprovals::answered,
                )
                showChat(session)
                then?.invoke(session)
                return@launch
            }
            val opened = runCatching {
                found ?: reader.open(conversationId) ?: error("It is not on your computer any more.")
            }
                .getOrElse { failure ->
                    _notice.value = failure.message?.takeIf { it.isNotBlank() }
                        ?.let { "Could not open that conversation: $it" }
                        ?: "Could not open that conversation."
                    return@launch
                }
            // Carry the real creation time through, so re-saving does not rewrite it
            // to now on a conversation that was started days ago at the computer.
            val summary = _conversations.value.firstOrNull { it.id == conversationId }
            val createdAt = opened.createdAtEpochMs
                ?: summary?.createdAtEpochMs?.takeIf { it > 0 }
                ?: System.currentTimeMillis()

            val session = ChatSession(
                socket = open,
                scope = viewModelScope,
                activePersona = ::currentPersona,
                onAnswered = ::replyReady,
                conversationId = conversationId,
                initialMessages = opened.messages,
                createdAt = createdAt,
                // The conversation's own project, not whichever one happens to be
                // active. Saving with the active one refiles a conversation the user
                // merely opened — the turn would run in a workspace they did not
                // choose, and the conversation would move out of the group they
                // found it in.
                //
                // Read from the conversation itself, and never defaulted. This was
                // `summary?.projectId ?: activeProjectId`, and a plain chat's project
                // is null — so opening one on the phone filed it into whatever project
                // the computer had open, and its next turn ran with that project's
                // files. Seen on the test phone: a plain chat, opened from search and
                // renamed, moved into Nebula2.
                projectId = opened.projectId,
                existingTitle = opened.storedTitle ?: summary?.storedTitle,
                initialApproval = pendingApprovals.forConversation(conversationId),
                onApprovalAnswered = pendingApprovals::answered,
            )
            showChat(session)
            then?.invoke(session)
        }
    }

    /**
     * Start a fresh conversation. Nothing is written until the first message is sent.
     *
     * Outside any project unless one is named. This used to inherit whichever project
     * was active, which put a chat started on the phone inside a project folder on the
     * computer — so it did not appear in the general chat list where it was looked
     * for, and the turn ran with access to real files nobody had asked it to touch.
     *
     * The desktop's `chatStore.newConversation` takes the same shape and holds the
     * same rule: a chat created without an explicit project must not *silently*
     * inherit one. Naming it is a different thing entirely, and that is what the
     * Workspace screen does — the phone had no way to say it before, which is why
     * there was no way to start work in a project from here at all.
     */
    fun newConversation(projectId: String? = null, temporary: Boolean = false) {
        val open = socket ?: return
        showChat(
            ChatSession(
                socket = open,
                scope = viewModelScope,
                activePersona = ::currentPersona,
                onAnswered = ::replyReady,
                projectId = projectId,
                temporary = temporary,
            ),
        )
    }

    /**
     * Switch the empty chat on screen between ordinary and temporary.
     *
     * Only before anything is sent: a conversation that has already been recorded on
     * the computer cannot be made temporary after the fact, and pretending otherwise
     * would be the one promise here that isn't kept.
     */
    fun setTemporary(temporary: Boolean) {
        val current = _chat.value ?: return
        if (current.messages.value.isNotEmpty() || current.temporary == temporary) return
        newConversation(current.projectId, temporary)
    }

    private var socket: AnodexSocket? = null

    private val controller = ConnectionController(
        scope = viewModelScope,
        networkRelation = { networkMonitor.currentRelation(_paired.value?.pairedNetworkId) },
        attemptConnection = { host -> openSocket(host) },
        isFinal = ::isNoLongerPaired,
    )

    private val deviceName: String
        get() = (Build.MANUFACTURER + " " + Build.MODEL).trim()

    /**
     * Open a socket and hand back the desktop's model state.
     *
     * Throws on any failure, which is what the controller wants: it owns the backoff and the
     * grace period, and a transport that swallowed errors would leave it believing a dead
     * connection was alive.
     */
    private suspend fun openSocket(host: PairedHostRef): ModelStatus? {
        socket?.close()
        val stored = _paired.value ?: error("Nothing is paired.")
        val fingerprint = hexToBytes(host.certificateFingerprint)

        // Try every address the desktop has told us about, in the order that makes
        // sense from where this phone is standing. At home the LAN address is first
        // and the first attempt wins; on mobile data it is moved behind the
        // forwarded public address, because a LAN address cannot work from there and
        // trying it first spends the whole connect timeout finding that out.
        //
        // This is what makes working off the home network possible without Anodex
        // running a relay or anyone else's service sitting in between.
        // Every address's failure, not just the newest. Keeping only the last one
        // meant a conclusive answer from one address — "that is not the computer you
        // paired with" — was overwritten by a timeout from another, and the timeout
        // is the one thing that settles nothing. See `mostTellingFailure`.
        val failures = mutableListOf<AttemptFailure>()

        for (address in Reachability.orderByPlausibility(stored.addresses, localIPv4Addresses())) {
            val candidate = AnodexSocket(
                address = address,
                port = stored.port,
                certificateSha256 = fingerprint,
                deviceName = deviceName,
            )
            try {
                val handshake = candidate.connect(AnodexSocket.Credential.DeviceKey(host.secret))

                // Everything the desktop pushes that is not a chat token: run
                // finished, task failed, something waiting on a human.
                viewModelScope.launch {
                    candidate.events.collect { event -> onPushed(event) }
                }

                // Tell the controller when this connection dies, rather than waiting
                // for the user to discover it by typing into a dead socket.
                candidate.onDropped = { farewell ->
                    if (socket === candidate) {
                        _chat.value?.let { rememberForReconnect(it) }
                        showChat(null)

                        // The computer's own account of why it went, when it gave
                        // one. It beats anything the phone can work out from a dead
                        // socket — "Gort went to sleep" instead of three guesses —
                        // so it replaces the diagnosis rather than sitting beside it.
                        //
                        // Set before `onDisconnected`, so the offline screen has the
                        // explanation the first time it draws rather than a frame
                        // later.
                        if (farewell != null) {
                            _connectionHint.value = farewell.explain(host.identity.displayName)
                        }
                        _noLongerPaired.value = farewell == RemoteFarewell.UNPAIRED
                        controller.onDisconnected(host)
                    }
                }

                _newerVersion.value = handshake.mobileVersion
                    .takeIf { isUpdateAvailable(BuildConfig.VERSION_NAME, it) }

                // The computer says this phone is behind. Only GitHub knows what is
                // actually downloadable, so that is who gets asked — and this one
                // skips the throttle, because it is a fact rather than a poll.
                if (_newerVersion.value != null) checkForUpdate(force = true)

                _connectionHint.value = null
                _noLongerPaired.value = false
                socket = candidate
                conversationReader = Conversations(candidate)
                devicesClient = dev.anodex.mobile.devices.Devices(candidate)
                projectClient = Projects(candidate)
                agentClient = Agents(candidate)
                emailClient = Email(candidate)
                workspace = Workspace(candidate)
                personalityClient = Personalities(candidate)
                uploads = Uploads(candidate, getApplication<Application>().contentResolver)
                schedulerClient = Scheduler(candidate)
                memoryClient = Memory(candidate)
                profileReader = ProfileReader(candidate)
                // What the computer is still waiting on, asked again on every connection:
                // an approval asked while this phone was away, or before the app restarted,
                // was sent to nobody who is here now. Asked rather than pushed, because
                // nothing is listening until this point.
                pendingApprovals.clear()
                viewModelScope.launch {
                    val answer = runCatching {
                        candidate.invoke(PendingApprovals.CHANNEL_WAITING, emptyList())
                    }.getOrNull() ?: return@launch
                    waitingApprovals(answer).forEach(pendingApprovals::onRequest)
                    _chat.value?.let { open ->
                        pendingApprovals.forConversation(open.conversationId)?.let(open::offerApproval)
                    }
                }
                // The desktop forgets this when the socket goes, so it is said again
                // on every new one rather than only when the user changes it.
                tellComputerAboutTokens()
                tellComputerAboutThinking()
                // The greeting on the home screen wants the name, and that screen is
                // the first thing anybody sees. Reading it only when Settings opens
                // meant it was never there when it was needed.
                refreshProfile()
                modelClient = Models(candidate)
                showChat(
                    ChatSession(
                        socket = candidate,
                        scope = viewModelScope,
                        // Read per turn, so a personality changed mid-conversation labels
                        // what follows rather than rewriting what came before.
                        activePersona = ::currentPersona,
                        onAnswered = ::replyReady,
                    ),
                )
                // Adopt whatever the computer calls itself, every connection rather
                // than only at pairing. A phone paired by typing an address had the
                // address as its name and showed it on every screen — a home router's
                // public IP in plain sight on a device that leaves the house. Doing it
                // here rather than at pairing means a phone paired before the computer
                // sent a name picks it up by reconnecting, not by pairing again.
                store.recordHostName(handshake.hostName)
                // The state machine captured the identity when pairing began, so the
                // store alone is not enough — without this the header keeps showing
                // the old name until the app is restarted, which is the shape of a
                // fix that appears to work and does nothing.
                controller.onHostRenamed(handshake.hostName)

                store.recordSeen(System.currentTimeMillis())
                refreshConversations()
                refreshProjects()
                refreshAgentRuns()
                refreshUnreadEmail()
                refreshPersonalities()
                refreshModels()
                resumeAfterReconnect()
                pendingQuickAction?.let {
                    pendingQuickAction = null
                    applyQuickAction(it)
                }
                pendingShare?.let { (text, uris) ->
                    pendingShare = null
                    applyShare(text, uris)
                }
                pendingNotificationOpen?.let {
                    pendingNotificationOpen = null
                    openConversation(it)
                }

                // Refreshed every time, so a desktop that gains a VPN — or has its
                // port forwarded — after pairing becomes reachable from away without
                // the user doing anything.
                //
                // Stored in the desktop's own ranking rather than with whichever
                // address just worked hoisted to the front. Hoisting looks like a
                // free optimisation and is not: succeeding once on the public
                // address away from home would leave it ahead of the LAN address
                // forever, so every reconnect back at home would go out to the
                // router and back. Ordering is decided per attempt instead, by
                // where the phone actually is.
                // The address that just worked goes first, then everything the
                // desktop reports. Replacing the list outright was a real fault: a
                // desktop that has not been told its own public address advertises
                // LAN addresses only, so a phone that had just reached it from the
                // internet would overwrite the route that worked with routes that
                // cannot work from where it is standing. The connection in hand
                // survived; the next reconnect had nowhere to go, and the way back
                // was a pairing code on a screen the user was nowhere near.
                //
                // An address that stops working is dropped by being unreachable, not
                // by being forgotten — trying a stale one costs a timeout, and losing
                // a live one costs the whole feature.
                val advertised = handshake.addresses.ifEmpty { stored.addresses }
                val reported = (listOf(address) + advertised).distinct()
                store.recordAddresses(reported)
                _paired.value = stored.copy(
                    addresses = reported,
                    identity = stored.identity.copy(
                        displayName = handshake.hostName.ifBlank { stored.identity.displayName },
                    ),
                )

                // Model state is a nicety for the header, never a reason to fail a
                // connection that is otherwise working.
                return runCatching { readModelState(candidate) }.getOrNull()
            } catch (e: Exception) {
                candidate.close()
                failures += AttemptFailure(address, e)
                // Every address leads to the same computer, and it has just said this
                // key is not paired. Asking again at the next address only counts
                // towards its lockout.
                if (isNoLongerPaired(e)) break
            }
        }

        // Every address failed. Say why, if the phone can work it out - "reconnecting"
        // forever with no explanation is the worst version of being away from home.
        // What the exception actually says, where it says anything definite. A
        // certificate mismatch or a refused connection is evidence; the network
        // heuristic below is a guess, and a guess must not outrank evidence.
        val telling = mostTellingFailure(failures)
        val diagnosis = diagnoseConnectionFailure(
            telling?.error,
            telling?.address ?: stored.addresses.firstOrNull().orEmpty(),
            stored.port,
            hostName = host.identity.displayName,
        )

        val explanation = diagnosis ?: explainUnreachable(
            // The one it would have tried first from here, which is not the desktop's
            // top-ranked address precisely when the user is away from home — and that
            // is exactly when the hint matters.
            address = Reachability.orderByPlausibility(stored.addresses, localIPv4Addresses())
                .firstOrNull().orEmpty(),
            knownAddresses = stored.addresses,
            attempted = attemptedLabel(stored.addresses, stored.port),
        )
        _connectionHint.value = explanation
        _noLongerPaired.value = telling?.error?.let(::isNoLongerPaired) == true
        throw telling?.error ?: IllegalStateException(explanation)
    }

    /**
     * Read the desktop's EngineState for the connection header.
     *
     * Field names come from the generated contract, not from memory: the first
     * version looked for `modelName`, which does not exist, so the header would
     * have stayed blank however well everything else worked.
     */
    private suspend fun readModelState(open: AnodexSocket): ModelStatus? =
        modelStatusFrom(open.invoke("models:get-state"))

    /**
     * The computer's engine state, however it arrived.
     *
     * `models:get-state` answers with it and `models:state-changed` pushes the same
     * shape, so both go through here. Two readers for one shape is how the header
     * ends up showing a different context figure depending on whether the phone
     * asked or was told.
     */
    private fun modelStatusFrom(element: JsonElement?): ModelStatus? {
        val state = element as? JsonObject ?: return null
        val name = (state["model"] as? JsonObject)
            ?.get("name")
            ?.let { (it as? JsonPrimitive)?.content }
            ?: return null

        return ModelStatus(
            name = name,
            // Not `intOrZero`. The desktop omits this whenever its engine has no
            // live sequence, and reading that as zero is what made the meter look
            // permanently empty instead of honestly blank.
            contextUsedTokens = (state["contextTokensUsed"] as? JsonPrimitive)?.intOrNull,
            contextConversationId =
                (state["contextTokensConversationId"] as? JsonPrimitive)?.contentOrNull,
            contextTotalTokens = state.intOrZero("contextSize"),
            activeReplies = state.intOrZero("activeReplies"),
            // Marks the running model in the picker. Empty is fine — the list simply
            // ticks nothing rather than ticking the wrong row.
            path = (state["model"] as? JsonObject)
                ?.get("path")
                ?.let { (it as? JsonPrimitive)?.content }
                .orEmpty(),
        )
    }

    private fun JsonObject.intOrZero(key: String): Int =
        (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 0

    private val _manualState = MutableStateFlow<ManualPairState>(ManualPairState.Entering)
    val manualState: StateFlow<ManualPairState> = _manualState.asStateFlow()

    /** Details typed in, held between the probe and the user's confirmation. */
    private var pendingManual: PendingManual? = null

    private data class PendingManual(
        val address: String,
        val port: Int,
        val code: String,
        val fingerprint: ByteArray,
    )

    /**
     * Look at what is answering, without trusting it.
     *
     * A typed code carries no fingerprint, so there is nothing to pin yet. This
     * fetches the certificate the host presents and shows it - nothing is sent to
     * that host until the user confirms it is their computer.
     */
    fun probeManualHost(address: String, port: Int, code: String) {
        _pairingError.value = null
        _manualState.value = ManualPairState.Probing

        viewModelScope.launch {
            try {
                val fingerprint = CertificateProbe.fingerprintOf(address, port)
                pendingManual = PendingManual(address, port, code, fingerprint)
                _manualState.value = ManualPairState.Confirming(humanFingerprintOf(fingerprint))
            } catch (e: Exception) {
                _manualState.value = ManualPairState.Entering
                _pairingError.value = explainProbeFailure(address, port, e)
            }
        }
    }

    /**
     * Say why a connection attempt could not have worked, when that is knowable.
     *
     * "Nothing answered" is true whether the computer is asleep, the port is wrong,
     * or the phone is on a different network — three problems, three different
     * remedies, one indistinguishable symptom. The subnet check is a heuristic and
     * is only ever used to explain a failure that already happened.
     */
    private fun explainProbeFailure(address: String, port: Int, error: Exception): String =
        // Evidence first here too, and this is the path that matters most: somebody
        // typing the details by hand has just told the app exactly where to look, so
        // "wrong certificate" or "nothing listening on that port" is directly
        // actionable in a way that a subnet guess never is.
        diagnoseConnectionFailure(error, address, port)
            ?: explainUnreachable(address, listOf(address), attempted = "$address:$port")

    /**
     * Say why a connection could not have worked, when that is knowable.
     *
     * Each verdict maps to the one thing that would actually fix it. Telling
     * somebody on mobile data to check their Wi-Fi is worse than saying nothing:
     * it sends them to look at something that was never the problem.
     */
    /** "192.168.1.40:47800", or "3 addresses on port 47800" when it tried several. */
    private fun attemptedLabel(addresses: List<String>, port: Int): String =
        if (addresses.size <= 1) {
            "${addresses.firstOrNull().orEmpty()}:$port"
        } else {
            "${addresses.size} addresses on port $port"
        }

    private fun explainUnreachable(
        address: String,
        knownAddresses: List<String>,
        attempted: String,
    ): String {
        // Whether the desktop has a way in from outside decides what to say here.
        // Telling someone on mobile data to "check their Wi-Fi" when the real answer
        // is a port that was never forwarded is the kind of hint that wastes an hour.
        val hasPublicRoute = knownAddresses.any { !Reachability.isLocalRoute(it) }

        // There is a route in from outside and it did not answer, so the far end is
        // the problem — not this phone's network, which is what the user would
        // otherwise be sent off to check.
        val publicRouteFailed =
            "This phone isn't on your home network, and the way in from outside didn't answer. " +
                "Check the computer is awake, and that the port is still forwarded on your router."

        // The actionable case, and the one worth being specific about: nothing this
        // phone does on its own can reach a computer with no way in.
        val noWayIn =
            "This phone isn't on your home network, and your computer has no way in from " +
                "outside. On the computer, open Settings → Remote and turn on " +
                "\"Reach this computer from anywhere\"."

        return when (
            Reachability.verdictFor(address, localIPv4Addresses(), knownAddresses)
        ) {
            Reachability.Verdict.MESH_AVAILABLE_BUT_OFF ->
                "Your computer can be reached over your VPN, but this phone isn't on it. Turn " +
                    "the VPN on and try again."

            // Both mean "this phone is not where the computer is". What to do about
            // that depends entirely on whether a way in from outside exists at all,
            // so that is what splits them rather than the verdict.
            Reachability.Verdict.NO_ROUTE ->
                if (hasPublicRoute) publicRouteFailed else noWayIn

            Reachability.Verdict.DIFFERENT_SUBNET ->
                if (hasPublicRoute) {
                    publicRouteFailed
                } else {
                    "This phone is on a different network than $address. If your router has " +
                        "separate 2.4GHz and 5GHz names, join the one your computer is on."
                }

            // Both of these mean "nothing came back", which is the genuinely
            // ambiguous case: asleep, firewalled and wrong-port look identical from
            // here. Naming what was tried is the most useful thing left to say.
            Reachability.Verdict.SAME_SUBNET, Reachability.Verdict.SAME_MESH ->
                "No answer from $attempted. Check the computer is awake, that remote access is " +
                    "on in its Settings, and that Windows Firewall is not blocking Anodex."

            Reachability.Verdict.UNKNOWN ->
                "No answer from $attempted. Check the computer is awake and that remote access " +
                    "is still on."
        }
    }

    /** The user says the fingerprint matches. Only now does anything get sent. */
    fun confirmManualFingerprint() {
        val pending = pendingManual ?: return
        _manualState.value = ManualPairState.Pairing
        pairWith(
            address = pending.address,
            port = pending.port,
            certificateSha256 = pending.fingerprint,
            credential = AnodexSocket.Credential.PairingSecret(pending.code),
            hostId = bytesToHex(pending.fingerprint).take(16),
            displayName = pending.address,
        )
    }

    /** Leave the manual flow, forgetting anything typed. */
    fun cancelManualPairing() {
        pendingManual = null
        _manualState.value = ManualPairState.Entering
        _pairingError.value = null
    }

    /**
     * Finish pairing with a scanned code.
     *
     * The device key the desktop issues is persisted and never logged. The network is recorded
     * at the same moment, so the offline screen can later say whether the phone has moved.
     */
    fun completePairing(payload: PairingPayload) {
        val secret = Base64.encodeToString(
            payload.secret,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        pairWith(
            address = payload.address,
            port = payload.port,
            certificateSha256 = payload.certificateSha256,
            credential = AnodexSocket.Credential.PairingSecret(secret),
            hostId = payload.hostId,
            displayName = payload.displayName,
        )
    }

    /**
     * The one place a pairing is completed, whether the code was scanned or typed.
     *
     * Both paths arrive here with a certificate they have already decided to trust
     * - the scanned one from the QR, the typed one from the user's confirmation -
     * so from this point the two are identical, and there is no second
     * implementation to drift.
     */
    private fun pairWith(
        address: String,
        port: Int,
        certificateSha256: ByteArray,
        credential: AnodexSocket.Credential,
        hostId: String,
        displayName: String,
    ) {
        viewModelScope.launch {
            val pairingSocket = AnodexSocket(
                address = address,
                port = port,
                certificateSha256 = certificateSha256,
                deviceName = deviceName,
            )
            try {
                val handshake = pairingSocket.connect(credential)
                val issued = handshake.issuedDeviceKey
                    ?: error("That computer did not issue a device key.")

                // Keep the address that actually worked first, then everything else the
                // desktop reports - so a phone paired at home already knows the mesh
                // address before it first leaves the house.
                val known = listOf(address) + handshake.addresses.filterNot { it == address }

                val host = PairedHost(
                    identity = HostIdentity(id = hostId, displayName = displayName),
                    secret = issued,
                    certificateFingerprint = bytesToHex(certificateSha256),
                    pairedNetworkId = networkMonitor.currentNetworkId(),
                    lastSeenEpochMs = System.currentTimeMillis(),
                    addresses = known,
                    port = port,
                )
                store.save(host)
                _paired.value = host
                _pairingError.value = null
                pendingManual = null
                _manualState.value = ManualPairState.Entering
                controller.pair(host.toRef())
            } catch (e: Exception) {
                _manualState.value = ManualPairState.Entering
                _pairingError.value = e.message ?: "That didn't work. Show a new code and retry."
            } finally {
                pairingSocket.close()
            }
        }
    }

    /** The app's root state. Every screen reads this. */
    val state: StateFlow<ConnectionState> = controller.state

    /**
     * The last thing between a dropped socket and a dead app.
     *
     * `viewModelScope` carries a `SupervisorJob`, so a `launch` that throws does not
     * cancel its siblings — but with no handler the exception still reaches the
     * thread's default one, and Android's default one is to kill the process. Every
     * in-flight call is resumed with the failure when a socket dies (`failPending`),
     * so *any* unguarded `launch` awaiting the computer is a crash waiting for a
     * connection to drop at the wrong moment. One of them did, on a real phone,
     * after fifteen good ping/pongs.
     *
     * The individual sites are guarded where they have somewhere to report to — a
     * file reader can say the read failed, an upload can say it did not send. This
     * is for the ones nobody thought of, and it is deliberately not silent: swallowing
     * is the defect this codebase is named for in `AGENTS.md`. It says something and
     * lets the connection machinery do its job, which is the behaviour a dropped
     * socket should have had all along.
     *
     * It also covers the launches that write the pairing. `DataStore.edit` throws on
     * a disk it cannot write, and `SecretCipher` throws when the Android keystore
     * refuses — which really happens, on a lock-screen change and on some OEM
     * builds. Rarer than a dropped socket by a long way, and the same class of
     * crash exactly.
     *
     * `updater.check` and `updater.download` were examined and left alone: the first
     * wraps its whole body in `runCatching` and returns null, the second returns a
     * `Result`. Neither can throw into its caller.
     */
    private val farEnd = CoroutineExceptionHandler { _, thrown ->
        // Cancellation is the ordinary way a scope ends — the screen closed, the
        // ViewModel died. It is not news.
        if (thrown is CancellationException) return@CoroutineExceptionHandler
        // Not written to the crash log: that file is read back and shown as "the app
        // crashed", and this is precisely the case where it did not.
        _notice.value = thrown.message ?: "Lost the connection to your computer."
    }

    init {
        // Walking out of the house changes the answer, and the computer is still
        // sending. Watched rather than checked on connect alone, because the case this
        // exists for is exactly the one where the network changes underneath somebody.
        viewModelScope.launch {
            networkMonitor.meteredChanges().collect { tellComputerAboutTokens() }
        }

        // Re-measure the open conversation's context every time a turn settles.
        viewModelScope.launch(farEnd) {
            settledTurns().collect { conversationId ->
                if (conversationId != null) refreshContextUsage(conversationId)
            }
        }

        viewModelScope.launch(farEnd) {
            val stored = store.paired.first()
            _paired.value = stored
            if (stored != null) controller.pair(stored.toRef())
        }

        viewModelScope.launch(farEnd) {
            // Re-classify as the phone moves between networks so the offline screen's explanation
            // updates under the user, rather than waiting for the next failed attempt.
            networkMonitor.relationTo(_paired.value?.pairedNetworkId).collect { relation ->
                controller.onNetworkChanged(relation)
            }
        }

        viewModelScope.launch(farEnd) { holdProcessWhileConnected() }

        viewModelScope.launch(farEnd) {
            // Ask once, the first time this phone is actually driving a computer.
            //
            // The old trigger was a notification that had already failed to show,
            // which is the wrong moment twice over: the app has to be in front to
            // raise a prompt, and a notification only matters when it is not. So the
            // first one was always lost, and the prompt arrived later with no
            // connection to the thing it was about.
            //
            // Connecting is the moment notifications start being worth anything, and
            // it is a moment the user is almost always looking at the app.
            state.first { it is ConnectionState.Connected }
            refreshNotificationAccess()
            advanceSetup()
        }
    }

    /**
     * Keep the process alive for as long as there is a live link worth keeping.
     *
     * Android kills backgrounded processes without warning, and nothing the phone
     * knows is cached to disk - the socket, the open conversation, an unanswered
     * approval. A kill is therefore not a pause, it is the session gone, and the user
     * discovers it by opening the app to be told it is reconnecting. That is exactly
     * the moment this app exists for: the phone in a pocket while a long run works.
     *
     * The price is a notification the user cannot dismiss, so it runs while connected
     * or actively reconnecting and stops the moment the connection is given up. An
     * ongoing notification for a link that is *offline* is a lie the user has to look
     * at all day.
     */
    private suspend fun holdProcessWhileConnected() {
        val context = getApplication<Application>()

        // The notification carries what the computer is *doing*, so it is driven by
        // more than the connection state now. It used to read "Anodex can reach your
        // computer" for ever, which is the one thing its own existence already says.
        combine(state, turnInFlight(), contextUsage) { current, working, usage ->
            Triple(current, working, usage)
        }.collect { (current, working, usage) ->
            // The home screen widget's dot. Saved only when it changes, so a context
            // meter ticking over does not redraw the launcher.
            WidgetState.saveConnection(context, widgetConnectionFor(current))

            val hold = processHoldFor(current)
            if (hold == null) {
                ConnectionService.stop(context)
            } else {
                val model = (current as? ConnectionState.Connected)?.model
                ConnectionService.start(
                    context = context,
                    hostName = hold.hostName,
                    connected = hold.connected,
                    working = working,
                    detail = connectionDetail(
                        connected = hold.connected,
                        working = working,
                        modelName = model?.name,
                        usedTokens = usage?.usedTokens,
                        contextSize = usage?.contextSize,
                    ),
                )
            }
        }
    }

    /**
     * Whether a turn is in flight, across whichever session is open.
     *
     * `flatMapLatest` for the same reason `settledTurns` uses it: the session is
     * replaced on reconnect and whenever a different conversation is opened, and a
     * collector left on the old one would report its state as the current one.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun turnInFlight(): Flow<Boolean> =
        _chat.flatMapLatest { session -> session?.sending ?: flowOf(false) }


    /** Retry from the offline screen. */
    fun retry() {
        _paired.value?.let { controller.retryNow(it.toRef()) }
    }

    /** Rejected input, when the typed address could not be one. Null when fine. */
    private val _addressError = MutableStateFlow<String?>(null)
    val addressError: StateFlow<String?> = _addressError.asStateFlow()

    /**
     * Teach an already-paired computer a new way to be reached.
     *
     * The gap this closes: a phone knows only the addresses its computer has
     * advertised, and a computer behind a router does not know its own public
     * address unless somebody tells it. So the one address that works from away is
     * precisely the one the phone can never learn on its own. Before this, saying it
     * meant pairing again — which needs a code from the computer's screen, which is
     * the thing a user away from home does not have.
     *
     * Pairing is untouched. The device key already authenticates this phone and the
     * certificate is still pinned by fingerprint, so a wrong address fails to
     * connect rather than connecting to the wrong machine. This says where to knock,
     * not who is allowed in.
     */
    fun addAddress(raw: String) {
        val cleaned = raw.trim().removeSurrounding("[", "]")
        val problem = when {
            cleaned.isEmpty() -> "Type an address first."
            cleaned.any { it.isWhitespace() } -> "An address cannot contain spaces."
            "://" in cleaned -> "Just the address — no http:// in front."
            cleaned.count { it == ':' } == 1 ->
                "Just the address. The port is already set to ${_paired.value?.port ?: 47800}."
            else -> null
        }
        if (problem != null) {
            _addressError.value = problem
            return
        }

        viewModelScope.launch(farEnd) {
            val current = _paired.value ?: return@launch
            _addressError.value = null
            // First, because the user typing it is better evidence about where this
            // phone is standing than anything the computer has said about itself.
            val merged = (listOf(cleaned) + current.addresses).distinct()
            store.recordAddresses(merged)
            _paired.value = current.copy(addresses = merged)
            controller.retryNow(current.toRef())
        }
    }

    /** Clears a rejected-address message, so reopening the field starts clean. */
    fun clearAddressError() {
        _addressError.value = null
    }

    /** Completed pairing: persist it and start connecting. */
    fun onPaired(host: PairedHost) {
        viewModelScope.launch(farEnd) {
            val withNetwork = host.copy(pairedNetworkId = networkMonitor.currentNetworkId())
            store.save(withNetwork)
            _paired.value = withNetwork
            controller.pair(withNetwork.toRef())
        }
    }

    /** Forget the desktop entirely, dropping the stored secret and its Keystore key. */
    fun unpair() {
        _noLongerPaired.value = false
        viewModelScope.launch(farEnd) {
            controller.unpair()
            store.clear()
            _paired.value = null
        }
    }

    /**
     * Hand the larger home screen widget the conversations you used most recently.
     *
     * The same rule as the drawer's recents: only chats a person started, newest
     * first — never the scheduled and agent runs that write conversations too.
     */
    private fun rememberRecentsForWidget(conversations: List<ConversationSummary>) {
        WidgetState.saveRecents(
            getApplication(),
            conversations
                .filter { it.isMine }
                .sortedByDescending { it.updatedAtEpochMs }
                .take(WidgetState.RECENT_LIMIT)
                .map { WidgetRecent(it.id, it.title) },
        )
    }

    override fun onCleared() {
        // Nothing will be holding the connection once this is gone, so the widget
        // must not go on showing a green dot for it.
        WidgetState.saveConnection(getApplication(), WidgetConnection.OFFLINE)
        if (RunActionBridge.handler === runActionHandler) RunActionBridge.handler = null
        if (ReplyBridge.handler === replyHandler) ReplyBridge.handler = null
        socket?.close()
        // viewModelScope cancellation would stop the loop anyway; saying so explicitly means the
        // controller's lifecycle does not depend on knowing that.
        controller.stop()
        // The app is going away for good, so the ongoing notification would be
        // describing a connection nothing is using.
        ConnectionService.stop(getApplication())
        super.onCleared()
    }

    /**
     * Put a desktop notification on the phone's shade.
     *
     * An approval keeps a stable id so answering it at the computer replaces or
     * clears this one, rather than leaving a dead notification that taps into
     * nothing. Everything else gets its own id so a run finishing does not
     * overwrite a task that failed.
     */
    /**
     * Something the computer sent without being asked.
     *
     * These were being dropped — every channel except notifications — and the cost
     * showed up exactly where this app is supposed to be strongest. You start a
     * build from the phone, watch the Agents screen, and it never changes: the run
     * moves from planning to waiting to finished on the computer while the phone
     * holds whatever it fetched when the screen opened. The only way to see progress
     * was to leave the screen and come back.
     *
     * The desktop was broadcasting all of it to remote clients the whole time.
     *
     * The payload is the whole list, so this costs no round trip: a run that changes
     * on the computer is on the phone in the time it takes the socket to carry it.
     */
    private fun onPushed(event: ServerFrame.Event) {
        when (event.channel) {
            CHANNEL_NOTIFICATION -> onNotification(event.payload)

            // Kept for whichever chat opens next: see `PendingApprovals`.
            PendingApprovals.CHANNEL_REQUEST -> parseToolApproval(event.payload)?.let(pendingApprovals::onRequest)
            PendingApprovals.CHANNEL_CANCELLED ->
                pendingApprovals.onCancelled((event.payload as? JsonPrimitive)?.content)

            CHANNEL_AGENT_RUNS -> {
                _agentRuns.value = parseAgentRuns(event.payload)
                clearSettledPlanNotification()
                // Something about a run changed — a turn, most often — so the page
                // following one reads its turns again.
                // A run deleted on the computer closes its page rather than leaving
                // one that reads turns for nothing.
                _openRunId.value?.let { id ->
                    if (_agentRuns.value.any { it.id == id }) refreshRunTurns() else closeRun()
                }
                // A push proves the computer is reachable, so any error the last read
                // left on screen is now stale.
                _agentsError.value = null
            }

            CHANNEL_TASKS_CHANGED -> {
                _tasks.value = parseTasks(event.payload)
                _tasksError.value = null
            }

            // The header's model and context meter. Read once at connect and then
            // frozen, so the bar sat still through a turn that was visibly filling
            // it — the one number on that bar worth watching, not moving.
            CHANNEL_MODEL_STATE -> modelStatusFrom(event.payload)?.let(controller::onModelUpdated)

            // Which projects exist and which one is open. The computer switching
            // project is exactly the sort of thing that happens while somebody is
            // holding the phone and not the mouse.
            CHANNEL_PROJECTS_CHANGED -> parseProjectsState(event.payload)?.let {
                _projects.value = it
            }

            CHANNEL_CONVERSATIONS_CHANGED -> onConversationChanged(event.payload)

            // A device paired, renamed, unpaired, connected or disconnected. Read
            // again only when the list has been loaded, which is when it is on show.
            CHANNEL_DEVICES_CHANGED -> if (_pairedDevices.value != null) refreshPairedDevices()

            // Most memory is written by the model mid-turn rather than by anyone
            // typing, so this is the one that arrives while the phone is just
            // sitting there. Refreshed only when the screen is being looked at:
            // re-reading a list nobody is watching spends a round trip to update
            // something off screen, and it is read fresh on open anyway.
            CHANNEL_MEMORY_CHANGED -> if (_settingsOpen.value) refreshMemories()
        }
    }

    /**
     * A conversation changed at the computer.
     *
     * Tokens already arrive live while a turn is running, so this is not how the
     * phone watches one happen — it is how it ends up holding the same thing the
     * computer saved once the turn is over. A stream is a picture of a turn; the save
     * is the turn. They can differ: a reply may be rewritten on completion, a title
     * gets written afterwards, and a turn that began before this phone connected was
     * never streamed to it at all.
     *
     * The changed row in the list is read again, because a change is as likely to be a
     * new conversation or a new title as it is new turns in this one. Only that row: the
     * whole list is tens of kilobytes and a save is announced after every reply, to
     * every other device. Changes arriving together are read together.
     *
     * The open conversation is brought up to date unless this phone is the one
     * mid-send — a re-read then would replace a turn in flight with the version on disk
     * that does not have it yet. Its newest turns are read and laid over what is here,
     * rather than all of it again.
     */
    private fun onConversationChanged(payload: JsonElement?) {
        val changedId = (payload as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return
        changedConversations.add(changedId)
        if (changeRead?.isActive == true) return
        changeRead = viewModelScope.launch {
            // A turn's end is announced more than once in a moment: the computer's own
            // record of it, the phone's save, the title. One read covers them all, and
            // anything announced while it reads is read straight after.
            delay(CHANGE_SETTLE_MS)
            while (changedConversations.isNotEmpty()) {
                val ids = changedConversations.toSet()
                changedConversations.removeAll(ids)
                readChangedRows(ids)
                _chat.value?.takeIf { it.conversationId in ids && !it.sending.value }
                    ?.let { syncOpenConversation(it) }
            }
        }
    }

    private val changedConversations: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())
    private var changeRead: kotlinx.coroutines.Job? = null

    private suspend fun readChangedRows(ids: Set<String>) {
        val reader = conversationReader ?: return
        when (val read = runCatching { reader.summariesOf(ids) }.getOrElse { return refreshConversations() }) {
            is dev.anodex.mobile.chat.SummaryRead.WholeList -> _conversations.value = read.rows
            is dev.anodex.mobile.chat.SummaryRead.Rows ->
                _conversations.value = dev.anodex.mobile.chat.withChangedRows(_conversations.value, ids, read.rows)
        }
        _conversationsError.value = null
        rememberRecentsForWidget(_conversations.value)
    }

    /**
     * Lay the computer's newest turns over the open conversation.
     *
     * A few more than this phone is missing, so the two overlap; the whole of it only
     * when they do not.
     */
    private suspend fun syncOpenConversation(session: ChatSession) {
        val reader = conversationReader ?: return
        val stored = _conversations.value.firstOrNull { it.id == session.conversationId }?.messageCount
        val missing = (stored ?: 0) - session.messages.value.size
        val limit = (missing + SYNC_OVERLAP).coerceIn(SYNC_OVERLAP, SYNC_MAX)
        val tail = runCatching { reader.open(session.conversationId, limit) }.getOrNull() ?: return
        if (_chat.value !== session) return
        if (!session.syncFromComputer(tail, tail.complete)) openConversation(session.conversationId)
    }

    private fun onNotification(payload: JsonElement?) {
        val fields = payload as? JsonObject ?: return
        val kind = NotificationKind.parse((fields["kind"] as? JsonPrimitive)?.content)
        val title = (fields["title"] as? JsonPrimitive)?.content ?: return
        val body = (fields["body"] as? JsonPrimitive)?.content.orEmpty()
        val conversationId = (fields["conversationId"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }

        val id = if (kind == NotificationKind.NEEDS_APPROVAL) {
            Notifications.ID_APPROVAL
        } else {
            nextNotificationId++
        }

        // A plan waiting for review gets Approve and Reject on the notification. The
        // computer's notification names the conversation, not the run, so the run is
        // found by it — in the list already here, or read fresh when this arrived
        // ahead of the list's own update.
        if (kind == NotificationKind.NEEDS_APPROVAL && conversationId != null) {
            viewModelScope.launch {
                val planRunId = reviewRunFor(conversationId)
                    ?: runCatching { agentClient?.list() }.getOrNull()?.let { runs ->
                        _agentRuns.value = runs
                        reviewRunFor(conversationId)
                    }
                postNotification(id, kind, title, body, conversationId, planRunId)
            }
            return
        }
        postNotification(id, kind, title, body, conversationId, null)
    }

    /** The run a plan notification with Approve and Reject is up for, if one is. */
    private var planNotificationRunId: String? = null

    /**
     * Take the plan notification down once its run is no longer waiting.
     *
     * Answered at the computer, or on this phone's own run page, the notification would
     * otherwise sit in the shade offering buttons for a question already settled.
     */
    private fun clearSettledPlanNotification() {
        val runId = planNotificationRunId ?: return
        if (_agentRuns.value.any { it.id == runId && it.status == AgentRun.Status.NEEDS_REVIEW }) return
        notifications.cancel(Notifications.ID_APPROVAL)
        planNotificationRunId = null
    }

    private fun reviewRunFor(conversationId: String): String? =
        _agentRuns.value.firstOrNull {
            it.conversationId == conversationId && it.status == AgentRun.Status.NEEDS_REVIEW
        }?.id

    private fun postNotification(
        id: Int,
        kind: NotificationKind,
        title: String,
        body: String,
        conversationId: String?,
        planRunId: String?,
    ) {
        planNotificationRunId = planRunId ?: planNotificationRunId.takeIf { kind != NotificationKind.NEEDS_APPROVAL }
        if (!notifications.show(id, kind, title, body, conversationId, planRunId)) {
            // Could not be shown — almost always an ungranted permission, or the app
            // switched off in system settings. Feeds the same sequence the first
            // connection uses rather than a second, separate flag: there is one
            // question here ("can anything reach you?") and it should have one
            // answer and one place that asks it.
            //
            // It will only surface when the app is next in front, which is the
            // honest limit — a prompt cannot be raised over somebody else's screen,
            // and that is precisely why this was never the right primary trigger.
            advanceSetup()
        }
    }

    private var nextNotificationId = 100

    /** How long to wait for a dropped conversation to reopen before sending into what is open. */
    private val QUEUE_REOPEN_TIMEOUT_MS = 15_000L

    /** How long announcements of a change are gathered before they are read. */
    private val CHANGE_SETTLE_MS = 300L

    /** Turns read beyond what the phone is missing, so the computer's copy overlaps this one. */
    private val SYNC_OVERLAP = 8

    /** The most turns a sync reads — as many as opening the conversation does. */
    private val SYNC_MAX = 200

    /** Whether the app is on screen. Set from the activity's resume and pause. */
    @Volatile private var appVisible = false

    fun onAppVisible(visible: Boolean) {
        appVisible = visible
        // Back in front with an answer notification still up for the chat on screen:
        // it has been seen now.
        if (visible) _chat.value?.let { notifications.cancel(Notifications.replyNotificationId(it.conversationId)) }
    }

    /**
     * Tell somebody who left that their answer is ready.
     *
     * Found on the test phone: a question sent and the app left answered in about
     * fifteen seconds, and nothing said so — the computer notifies the phone about
     * runs, scheduled tasks and approvals, never a chat reply, and the phone did not
     * either. The only way to find out was to keep opening the app.
     *
     * Only when the app is not on screen. Somebody watching the reply arrive does
     * not need a notification about it.
     */
    private fun replyReady(session: ChatSession, reply: ChatMessage) {
        if (appVisible) return
        val title = session.title.value ?: titleFromFirstTurn(session.messages.value)
        notifications.show(
            id = Notifications.replyNotificationId(session.conversationId),
            kind = NotificationKind.FINISHED,
            title = title,
            body = replyPreview(reply.text),
            // A temporary chat has no conversation on the computer to open again, so
            // a tap brings the app forward instead, where the chat still is.
            conversationId = session.conversationId.takeUnless { session.temporary },
            replyConversationId = session.conversationId,
        )
    }

    private val _chatOpenRequest = MutableStateFlow<String?>(null)

    /** A conversation a notification tap asked to open. The screen consumes it. */
    val chatOpenRequest: StateFlow<String?> = _chatOpenRequest.asStateFlow()

    private var pendingNotificationOpen: String? = null

    /**
     * Open the conversation a notification was about.
     *
     * The phone may not be connected yet — a tap after the process was reclaimed starts
     * it cold — so the request waits for the connection rather than being dropped.
     */
    fun openFromNotification(conversationId: String) {
        _chatOpenRequest.value = conversationId
        if (socket != null && conversationReader != null) {
            openConversation(conversationId)
        } else {
            pendingNotificationOpen = conversationId
        }
    }

    fun consumeChatOpenRequest() {
        _chatOpenRequest.value = null
    }

    /** What the home screen widget asked for: a new chat, or a photo for one — taken or chosen. */
    enum class QuickAction { NEW_CHAT, TEMPORARY, CAMERA, PHOTOS }

    private val _quickAction = MutableStateFlow<QuickAction?>(null)

    /** A widget tap waiting for the chat screen. The screen consumes it. */
    val quickAction: StateFlow<QuickAction?> = _quickAction.asStateFlow()

    private var pendingQuickAction: QuickAction? = null

    /**
     * Start a new chat for a widget tap, and hand the screen what else was asked for.
     *
     * Waits for the connection when the tap is what opened the app, like a share does.
     */
    fun receiveQuickAction(action: QuickAction) {
        if (socket == null) {
            pendingQuickAction = action
            return
        }
        applyQuickAction(action)
    }

    private fun applyQuickAction(action: QuickAction) {
        newConversation(temporary = action == QuickAction.TEMPORARY)
        _chat.value?.let { _chatOpenRequest.value = it.conversationId }
        _quickAction.value = action
    }

    fun consumeQuickAction() {
        _quickAction.value = null
    }

    /** The conversation that was open when the connection dropped. */
    data class OfflineChat(
        val conversationId: String,
        val messages: List<ChatMessage>,
        /** A temporary chat, which has nothing on the computer to reopen. */
        val temporary: Boolean = false,
        /** The project it runs against, for a chat reopened from this copy. */
        val projectId: String? = null,
    )

    private val _offlineChat = MutableStateFlow<OfflineChat?>(null)

    /**
     * What was on screen when the connection went, kept on screen while it comes back.
     *
     * A drop used to replace the conversation with "Reconnecting…" and then, once
     * connected, a blank new chat — somebody mid-conversation on a train lost their
     * place every time the signal dipped.
     */
    val offlineChat: StateFlow<OfflineChat?> = _offlineChat.asStateFlow()

    private val _queuedMessages = MutableStateFlow<List<String>>(emptyList())

    /** Messages written while the computer was unreachable, sent in order once it is back. */
    val queuedMessages: StateFlow<List<String>> = _queuedMessages.asStateFlow()

    fun queueWhileOffline(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _queuedMessages.value = _queuedMessages.value + trimmed
        // A message written into a chat that had no conversation yet still needs
        // somewhere to be shown while it waits.
        if (_offlineChat.value == null) _offlineChat.value = OfflineChat(conversationId = "", messages = emptyList())
    }

    /** Whether the connection has taken the open chat away, as opposed to the user leaving it. */
    fun chatIsGone(): Boolean = _chat.value == null

    fun unqueue(index: Int) {
        _queuedMessages.value = _queuedMessages.value.filterIndexed { i, _ -> i != index }
    }

    private fun rememberForReconnect(session: ChatSession) {
        val messages = session.messages.value
            // An answer cut off by the drop is shown as far as it got, not as still arriving.
            .map { if (it.streaming) it.copy(streaming = false) else it }
            .filter { it.role == ChatMessage.Role.USER || it.text.isNotBlank() || it.tools.isNotEmpty() }
        _offlineChat.value = OfflineChat(
            // A temporary chat was never saved, so there is nothing to reopen by id; it
            // comes back from what is held here instead.
            conversationId = if (messages.isEmpty() || session.temporary) "" else session.conversationId,
            messages = messages,
            temporary = session.temporary,
            projectId = session.projectId,
        )
    }

    /**
     * Back where the connection dropped: the same conversation, then anything written
     * while it was away, sent one after another.
     *
     * A notification tap or a share waiting on this connection is somewhere the user
     * asked to go, so it wins and the old conversation is not reopened over it.
     */
    private fun resumeAfterReconnect() {
        val offline = _offlineChat.value ?: return
        val queued = _queuedMessages.value
        val goingElsewhere = pendingNotificationOpen != null || pendingShare != null

        if (offline.conversationId.isNotEmpty() && !goingElsewhere) {
            openConversation(offline.conversationId, heldHere = offline)
        } else if (offline.temporary && !goingElsewhere) {
            // Rebuilt from the phone's own copy, still temporary: the computer never
            // had it, and reconnecting must not quietly turn it into a saved chat.
            socket?.let { open ->
                showChat(
                    ChatSession(
                        socket = open,
                        scope = viewModelScope,
                        activePersona = ::currentPersona,
                        onAnswered = ::replyReady,
                        initialMessages = offline.messages,
                        temporary = true,
                    ),
                )
            }
        }
        _offlineChat.value = null
        if (queued.isEmpty()) return

        viewModelScope.launch {
            val session = if (offline.conversationId.isNotEmpty() && !goingElsewhere) {
                withTimeoutOrNull(QUEUE_REOPEN_TIMEOUT_MS) {
                    _chat.filterNotNull().first { it.conversationId == offline.conversationId }
                }
            } else {
                _chat.value
            } ?: _chat.value ?: return@launch

            for (text in queued) {
                session.sending.first { !it }
                session.send(text)
                _queuedMessages.value = _queuedMessages.value.drop(1)
            }
        }
    }

    private val _sharedDraft = MutableStateFlow<String?>(null)

    /** Text shared into the app, waiting to be put in the composer. */
    val sharedDraft: StateFlow<String?> = _sharedDraft.asStateFlow()

    fun consumeSharedDraft() {
        _sharedDraft.value = null
    }

    private var pendingShare: Pair<String?, List<android.net.Uri>>? = null

    /**
     * Something shared from another app: a new chat, the text in its composer and the
     * files attached, ready to send — never sent on its own, because what to ask about
     * a shared screenshot is still the user's to say.
     *
     * Held until connected when it arrives first; a share is often what opened the app.
     */
    fun receiveShare(text: String?, uris: List<android.net.Uri>) {
        if (socket == null || uploads == null) {
            pendingShare = text to uris
            return
        }
        applyShare(text, uris)
    }

    private fun applyShare(text: String?, uris: List<android.net.Uri>) {
        newConversation()
        uris.forEach(::attach)
        _sharedDraft.value = text
        _chat.value?.let { _chatOpenRequest.value = it.conversationId }
    }

    /** Take the approval notification down once the prompt is gone. */
    fun clearApprovalNotification() {
        notifications.cancel(Notifications.ID_APPROVAL)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun PairedHost.toRef() = PairedHostRef(
        identity = identity,
        secret = secret,
        certificateFingerprint = certificateFingerprint,
    )

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T {
                val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                return AnodexViewModel(application) as T
            }
        }
    }
}

/** Long enough that a resume-driven check cannot exhaust GitHub's anonymous quota. */
private const val UPDATE_CHECK_INTERVAL_MS = 30 * 60 * 1000L

/**
 * A short pause before asking the computer what a phrase means.
 *
 * Long enough that typing a sentence is one question rather than thirty, short
 * enough that the preview appears while the person is still looking at the field
 * rather than after they have moved on.
 */
private const val PARSE_DEBOUNCE_MS = 350L

/**
 * A conversation was archived — or was not.
 *
 * Carries the title because the bar naming it is most of what makes the undo
 * usable: "Archived" alone leaves somebody wondering which one, at exactly the
 * moment they have a few seconds to decide.
 */
data class ArchiveNotice(
    val conversationId: String,
    val title: String,
    /** True when the computer refused, in which case there is nothing to undo. */
    val failed: Boolean = false,
)

/** A followed run's turns, with whether they are still arriving and why a read failed. */
data class RunTurnsState(
    val turns: List<RunTurn> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** About a hundred phone-sized pictures, read back from the computer. */
private const val PICTURE_CACHE_BYTES = 16 * 1024 * 1024

/** The widget's dot for a connection state. */
internal fun widgetConnectionFor(state: ConnectionState): WidgetConnection = when (state) {
    is ConnectionState.Connected -> WidgetConnection.CONNECTED
    is ConnectionState.Reconnecting -> WidgetConnection.RECONNECTING
    is ConnectionState.Offline, ConnectionState.Unpaired -> WidgetConnection.OFFLINE
}
