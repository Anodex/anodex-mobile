package dev.anodex.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.anodex.mobile.connection.ConnectionController
import dev.anodex.mobile.connection.ConnectionService
import dev.anodex.mobile.connection.processHoldFor
import dev.anodex.mobile.email.Email
import dev.anodex.mobile.workspace.FileContent
import dev.anodex.mobile.scheduler.ParsedWhen
import dev.anodex.mobile.scheduler.ScheduledTask
import dev.anodex.mobile.scheduler.Scheduler
import dev.anodex.mobile.workspace.Workspace
import dev.anodex.mobile.workspace.WorkspaceFile
import dev.anodex.mobile.email.EmailNote
import dev.anodex.mobile.email.EmailThread
import dev.anodex.mobile.connection.ConnectionState
import dev.anodex.mobile.connection.diagnoseConnectionFailure
import dev.anodex.mobile.connection.isUpdateAvailable
import dev.anodex.mobile.update.UpdateState
import dev.anodex.mobile.ui.screens.ThemeMode
import dev.anodex.mobile.ui.theme.AppearanceStore
import dev.anodex.mobile.update.Updater
import dev.anodex.mobile.connection.NetworkMonitor
import dev.anodex.mobile.notify.NotificationKind
import dev.anodex.mobile.notify.Notifications
import dev.anodex.mobile.connection.Reachability
import dev.anodex.mobile.connection.localIPv4Addresses
import dev.anodex.mobile.connection.PairedHostRef
import android.os.Build
import android.util.Base64
import dev.anodex.mobile.agents.AgentRun
import dev.anodex.mobile.agents.Agents
import dev.anodex.mobile.chat.ChatMessage
import dev.anodex.mobile.chat.ChatSession
import dev.anodex.mobile.chat.MessagePersona
import dev.anodex.mobile.chat.LocalModel
import dev.anodex.mobile.chat.Models
import dev.anodex.mobile.chat.Personalities
import dev.anodex.mobile.chat.UploadState
import dev.anodex.mobile.chat.Uploads
import dev.anodex.mobile.chat.PersonalityState
import dev.anodex.mobile.chat.ConversationSummary
import dev.anodex.mobile.chat.Conversations
import dev.anodex.mobile.chat.Projects
import dev.anodex.mobile.chat.ProjectsState
import dev.anodex.mobile.connection.HostIdentity
import dev.anodex.mobile.connection.ModelStatus
import dev.anodex.mobile.pairing.PairedHost
import dev.anodex.mobile.pairing.PairedHostStore
import dev.anodex.mobile.pairing.CertificateProbe
import dev.anodex.mobile.pairing.PairingPayload
import dev.anodex.mobile.pairing.humanFingerprintOf
import dev.anodex.mobile.ui.screens.ManualPairState
import dev.anodex.mobile.transport.AnodexSocket
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Holds the app together: the stored pairing, the connection controller, and the network monitor.
 *
 * Deliberately thin. The decisions live in `reduceConnection` (what a fact means) and
 * `ConnectionController` (when to act) precisely so that they can be unit-tested without Android;
 * pulling logic up into here would put it back out of reach. This class wires, it does not decide.
 */
private const val CHANNEL_NOTIFICATION = "remote:notification"

class AnodexViewModel(application: Application) : AndroidViewModel(application) {

    private val store = PairedHostStore(application)
    private val networkMonitor = NetworkMonitor(application)
    private val notifications = Notifications(application).apply { ensureChannels() }

    private val _needsNotificationPermission = MutableStateFlow(false)

    /**
     * True when something arrived that the user should have been told about, and
     * the phone could not.
     *
     * Asked for at that moment rather than at launch: a permission prompt makes
     * sense when there is a concrete thing it would have shown, and reads as
     * arbitrary before that.
     */
    val needsNotificationPermission: StateFlow<Boolean> =
        _needsNotificationPermission.asStateFlow()

    fun notificationPermissionHandled() {
        _needsNotificationPermission.value = false
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
            val file = client.describe(uri) ?: return@launch

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

        session.send(text, ready.map { it.uploaded })

        // Only the ones that went. Anything still uploading is still the user's.
        _attachments.value = _attachments.value.filterNot { it is UploadState.Done }
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
            _installedModels.value = runCatching { client.list() }.getOrDefault(emptyList())
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
            // Failure leaves the list empty and Settings says it is waiting. The
            // personalities are a nicety, never a reason to fail a connection.
            _personalities.value = runCatching { client.state() }
                .getOrDefault(PersonalityState(null, emptyList()))
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
            _openFileContent.value = client.read(relativePath)
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

    private val _unreadEmail = MutableStateFlow(0)

    /**
     * Unread threads, for the tab badge.
     *
     * Fetched on connect rather than when the Email tab is opened, because a badge
     * that only appears once you have already looked is telling you something you
     * necessarily already know.
     */
    val unreadEmail: StateFlow<Int> = _unreadEmail.asStateFlow()

    private fun refreshUnreadEmail() {
        val client = emailClient ?: return
        viewModelScope.launch {
            _unreadEmail.value = runCatching { client.unreadCount() }.getOrDefault(0)
        }
    }

    fun refreshEmail() {
        val client = emailClient ?: return
        viewModelScope.launch {
            _emailLoading.value = true
            // Asked first, so an empty result can be reported as "no account" rather
            // than as "no mail" -- the two look identical in a list and mean opposite
            // things to somebody waiting on a message.
            _emailConfigured.value = runCatching { client.isConfigured() }.getOrDefault(false)
            _emailThreads.value = runCatching { client.threads() }.getOrDefault(emptyList())
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
            _openThread.value = runCatching {
                client.messages(thread.id, thread.accountId.takeIf { it.isNotBlank() })
            }.getOrDefault(emptyList())
            _threadLoading.value = false
        }
    }

    fun closeEmailThread() {
        _openThread.value = null
    }

    fun refreshAgentRuns() {
        val client = agentClient ?: return
        viewModelScope.launch {
            _agentsLoading.value = true
            _agentRuns.value = runCatching { client.list() }.getOrDefault(emptyList())
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
            _projects.value = runCatching { client.state() }
                .getOrDefault(ProjectsState(emptyList(), null))
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

    /** Re-read the conversation list from the computer. */
    fun refreshConversations() {
        val reader = conversationReader ?: return
        viewModelScope.launch {
            _loadingConversations.value = true
            _conversations.value = runCatching { reader.list() }.getOrDefault(emptyList())
            _loadingConversations.value = false
        }
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
            val history = runCatching { reader.messagesOf(conversationId) }
                .getOrDefault(emptyList<ChatMessage>())
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
        var lastFailure: Exception? = null
        var lastFailureAddress: String? = null

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
                    candidate.events.collect { event ->
                        if (event.channel == CHANNEL_NOTIFICATION) onNotification(event.payload)
                    }
                }

                // Tell the controller when this connection dies, rather than waiting
                // for the user to discover it by typing into a dead socket.
                candidate.onDropped = {
                    if (socket === candidate) {
                        _chat.value = null
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
                lastFailure = e
                lastFailureAddress = address
            }
        }

        // Every address failed. Say why, if the phone can work it out - "reconnecting"
        // forever with no explanation is the worst version of being away from home.
        // What the exception actually says, where it says anything definite. A
        // certificate mismatch or a refused connection is evidence; the network
        // heuristic below is a guess, and a guess must not outrank evidence.
        val diagnosis = diagnoseConnectionFailure(
            lastFailure,
            lastFailureAddress ?: stored.addresses.firstOrNull().orEmpty(),
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
        throw lastFailure ?: IllegalStateException(explanation)
    }

    /**
     * Read the desktop's EngineState for the connection header.
     *
     * Field names come from the generated contract, not from memory: the first
     * version looked for `modelName`, which does not exist, so the header would
     * have stayed blank however well everything else worked.
     */
    private suspend fun readModelState(open: AnodexSocket): ModelStatus? {
        val state = open.invoke("models:get-state") as? JsonObject ?: return null
        val name = (state["model"] as? JsonObject)
            ?.get("name")
            ?.let { (it as? JsonPrimitive)?.content }
            ?: return null

        return ModelStatus(
            name = name,
            contextUsedTokens = state.intOrZero("contextTokensUsed"),
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

    init {
        viewModelScope.launch {
            val stored = store.paired.first()
            _paired.value = stored
            if (stored != null) controller.pair(stored.toRef())
        }

        viewModelScope.launch {
            // Re-classify as the phone moves between networks so the offline screen's explanation
            // updates under the user, rather than waiting for the next failed attempt.
            networkMonitor.relationTo(_paired.value?.pairedNetworkId).collect { relation ->
                controller.onNetworkChanged(relation)
            }
        }

        viewModelScope.launch { holdProcessWhileConnected() }
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

        state.collect { current ->
            val hold = processHoldFor(current)
            if (hold == null) {
                ConnectionService.stop(context)
            } else {
                ConnectionService.start(context, hold.hostName, hold.connected)
            }
        }
    }


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

        viewModelScope.launch {
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
        viewModelScope.launch {
            val withNetwork = host.copy(pairedNetworkId = networkMonitor.currentNetworkId())
            store.save(withNetwork)
            _paired.value = withNetwork
            controller.pair(withNetwork.toRef())
        }
    }

    /** Forget the desktop entirely, dropping the stored secret and its Keystore key. */
    fun unpair() {
        viewModelScope.launch {
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
            // Could not be shown - almost always an ungranted permission. Ask now,
            // when there is a concrete thing it would have told them about.
            _needsNotificationPermission.value = true
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
