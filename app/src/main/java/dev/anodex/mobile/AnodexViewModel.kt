package dev.anodex.mobile

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.anodex.mobile.agents.AgentRun
import dev.anodex.mobile.agents.Agents
import dev.anodex.mobile.agents.parseAgentRuns
import dev.anodex.mobile.chat.ChatSession
import dev.anodex.mobile.chat.ContextUsage
import dev.anodex.mobile.chat.ConversationSummary
import dev.anodex.mobile.chat.contextUsageFrom
import dev.anodex.mobile.chat.Conversations
import dev.anodex.mobile.chat.LocalModel
import dev.anodex.mobile.chat.MessagePersona
import dev.anodex.mobile.chat.Models
import dev.anodex.mobile.chat.Personalities
import dev.anodex.mobile.chat.PersonalityState
import dev.anodex.mobile.chat.Projects
import dev.anodex.mobile.chat.ProjectsState
import dev.anodex.mobile.chat.UploadState
import dev.anodex.mobile.chat.Uploads
import dev.anodex.mobile.chat.parseProjectsState
import dev.anodex.mobile.connection.ConnectionController
import dev.anodex.mobile.connection.ConnectionService
import dev.anodex.mobile.connection.connectionDetail
import dev.anodex.mobile.connection.ConnectionState
import dev.anodex.mobile.connection.HostIdentity
import dev.anodex.mobile.connection.ModelStatus
import dev.anodex.mobile.connection.NetworkMonitor
import dev.anodex.mobile.connection.PairedHostRef
import dev.anodex.mobile.connection.Reachability
import dev.anodex.mobile.connection.AttemptFailure
import dev.anodex.mobile.connection.diagnoseConnectionFailure
import dev.anodex.mobile.connection.mostTellingFailure
import dev.anodex.mobile.connection.isUpdateAvailable
import dev.anodex.mobile.connection.localIPv4Addresses
import dev.anodex.mobile.connection.processHoldFor
import dev.anodex.mobile.email.Email
import dev.anodex.mobile.email.EmailNote
import dev.anodex.mobile.email.EmailThread
import dev.anodex.mobile.memory.Memory
import dev.anodex.mobile.memory.MemoryEntry
import dev.anodex.mobile.notify.NotificationAccess
import dev.anodex.mobile.notify.NotificationKind
import dev.anodex.mobile.notify.Notifications
import dev.anodex.mobile.pairing.CertificateProbe
import dev.anodex.mobile.pairing.PairedHost
import dev.anodex.mobile.pairing.PairedHostStore
import dev.anodex.mobile.pairing.PairingPayload
import dev.anodex.mobile.pairing.humanFingerprintOf
import dev.anodex.mobile.scheduler.ParsedWhen
import dev.anodex.mobile.scheduler.ScheduledTask
import dev.anodex.mobile.scheduler.Scheduler
import dev.anodex.mobile.scheduler.parseTasks
import dev.anodex.mobile.transport.AnodexSocket
import dev.anodex.mobile.transport.unwrap
import dev.anodex.mobile.transport.ServerFrame
import dev.anodex.mobile.ui.screens.ManualPairState
import dev.anodex.mobile.ui.screens.ThemeMode
import dev.anodex.mobile.ui.theme.AppearanceStore
import dev.anodex.mobile.update.UpdateState
import dev.anodex.mobile.update.Updater
import dev.anodex.mobile.workspace.FileContent
import dev.anodex.mobile.workspace.Workspace
import dev.anodex.mobile.workspace.WorkspaceFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive

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

class AnodexViewModel(application: Application) : AndroidViewModel(application) {

    private val store = PairedHostStore(application)
    private val networkMonitor = NetworkMonitor(application)
    private val notifications = Notifications(application).apply { ensureChannels() }



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

    private val _conversations = MutableStateFlow<List<ConversationSummary>>(emptyList())

    /** What is on the computer. A live read, never a cache - empty when unreachable. */
    val conversations: StateFlow<List<ConversationSummary>> = _conversations.asStateFlow()

    private val _loadingConversations = MutableStateFlow(false)
    val loadingConversations: StateFlow<Boolean> = _loadingConversations.asStateFlow()

    /** Reads the desktop's conversation store. Null until a socket is open. */
    private var conversationReader: Conversations? = null

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
    fun attach(uri: android.net.Uri) {
        val client = uploads ?: return

        viewModelScope.launch {
            // Same reason as `openWorkspaceFile`: a suspend call over a socket that
            // may already be dying. `send` below reports through `Result`; this one
            // had nothing.
            val file = runCatching { client.describe(uri) }.getOrNull() ?: return@launch

            fun update(state: UploadState) {
                _attachments.value = _attachments.value.map {
                    if (fileOf(it).uri == uri) state else it
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

    private fun refreshPersonalities() {
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
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { appearance.setThemeMode(mode) }
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

    private fun refreshUnreadEmail() {
        val client = emailClient ?: return
        viewModelScope.launch {
            _unreadEmail.value = runCatching { client.unreadCount() }.getOrNull()
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
    private fun actOnRun(runId: String, action: suspend (Agents) -> Unit) {
        val client = agentClient ?: return
        _busyRunId.value = runId
        viewModelScope.launch {
            runCatching { action(client) }
            _busyRunId.value = null
            refreshAgentRuns()
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
    fun openConversation(conversationId: String) {
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
            val history = runCatching { reader.messagesOf(conversationId) }
                .getOrElse { failure ->
                    _notice.value = failure.message?.takeIf { it.isNotBlank() }
                        ?.let { "Could not open that conversation: $it" }
                        ?: "Could not open that conversation."
                    return@launch
                }
            // Carry the real creation time through, so re-saving does not rewrite it
            // to now on a conversation that was started days ago at the computer.
            val summary = _conversations.value.firstOrNull { it.id == conversationId }
            val createdAt = summary?.createdAtEpochMs?.takeIf { it > 0 }
                ?: System.currentTimeMillis()

            _chat.value = ChatSession(
                socket = open,
                scope = viewModelScope,
                activePersona = ::currentPersona,
                conversationId = conversationId,
                initialMessages = history,
                createdAt = createdAt,
                // The conversation's own project, not whichever one happens to be
                // active. Saving with the active one refiles a conversation the user
                // merely opened — the turn would run in a workspace they did not
                // choose, and the conversation would move out of the group they
                // found it in.
                projectId = summary?.projectId ?: _projects.value.activeProjectId,
                existingTitle = summary?.storedTitle,
            )
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
    fun newConversation(projectId: String? = null) {
        val open = socket ?: return
        _chat.value = ChatSession(
            socket = open,
            scope = viewModelScope,
            activePersona = ::currentPersona,
            projectId = projectId,
        )
    }

    private var socket: AnodexSocket? = null

    private val controller = ConnectionController(
        scope = viewModelScope,
        networkRelation = { networkMonitor.currentRelation(_paired.value?.pairedNetworkId) },
        attemptConnection = { host -> openSocket(host) },
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
                        _chat.value = null

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
                socket = candidate
                conversationReader = Conversations(candidate)
                projectClient = Projects(candidate)
                agentClient = Agents(candidate)
                emailClient = Email(candidate)
                workspace = Workspace(candidate)
                personalityClient = Personalities(candidate)
                uploads = Uploads(candidate, getApplication<Application>().contentResolver)
                schedulerClient = Scheduler(candidate)
                memoryClient = Memory(candidate)
                modelClient = Models(candidate)
                _chat.value = ChatSession(
                    socket = candidate,
                    scope = viewModelScope,
                    // Read per turn, so a personality changed mid-conversation labels
                    // what follows rather than rewriting what came before.
                    activePersona = ::currentPersona,
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
        viewModelScope.launch(farEnd) {
            controller.unpair()
            store.clear()
            _paired.value = null
        }
    }

    override fun onCleared() {
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

            CHANNEL_AGENT_RUNS -> {
                _agentRuns.value = parseAgentRuns(event.payload)
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
        }
    }

    private fun onNotification(payload: JsonElement?) {
        val fields = payload as? JsonObject ?: return
        val kind = NotificationKind.parse((fields["kind"] as? JsonPrimitive)?.content)
        val title = (fields["title"] as? JsonPrimitive)?.content ?: return
        val body = (fields["body"] as? JsonPrimitive)?.content.orEmpty()

        val id = if (kind == NotificationKind.NEEDS_APPROVAL) {
            Notifications.ID_APPROVAL
        } else {
            nextNotificationId++
        }

        if (!notifications.show(id, kind, title, body)) {
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
