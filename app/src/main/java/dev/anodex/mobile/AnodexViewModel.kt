package dev.anodex.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.anodex.mobile.connection.ConnectionController
import dev.anodex.mobile.connection.ConnectionState
import dev.anodex.mobile.connection.NetworkMonitor
import dev.anodex.mobile.connection.Reachability
import dev.anodex.mobile.connection.localIPv4Addresses
import dev.anodex.mobile.connection.PairedHostRef
import android.os.Build
import android.util.Base64
import dev.anodex.mobile.chat.ChatMessage
import dev.anodex.mobile.chat.ChatSession
import dev.anodex.mobile.chat.ConversationSummary
import dev.anodex.mobile.chat.Conversations
import dev.anodex.mobile.connection.HostIdentity
import dev.anodex.mobile.connection.ModelStatus
import dev.anodex.mobile.pairing.PairedHost
import dev.anodex.mobile.pairing.PairedHostStore
import dev.anodex.mobile.pairing.CertificateProbe
import dev.anodex.mobile.pairing.PairingPayload
import dev.anodex.mobile.pairing.humanFingerprintOf
import dev.anodex.mobile.ui.screens.ManualPairState
import dev.anodex.mobile.transport.AnodexSocket
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Holds the app together: the stored pairing, the connection controller, and the network monitor.
 *
 * Deliberately thin. The decisions live in `reduceConnection` (what a fact means) and
 * `ConnectionController` (when to act) precisely so that they can be unit-tested without Android;
 * pulling logic up into here would put it back out of reach. This class wires, it does not decide.
 */
class AnodexViewModel(application: Application) : AndroidViewModel(application) {

    private val store = PairedHostStore(application)
    private val networkMonitor = NetworkMonitor(application)

    private val _paired = MutableStateFlow<PairedHost?>(null)

    /** The paired desktop, once loaded. Null means unpaired — show the pairing flow. */
    val paired: StateFlow<PairedHost?> = _paired.asStateFlow()

    private val _chat = MutableStateFlow<ChatSession?>(null)

    /** The live conversation, or null while disconnected. Never a cache - see ChatSession. */
    val chat: StateFlow<ChatSession?> = _chat.asStateFlow()

    private val _pairingError = MutableStateFlow<String?>(null)
    val pairingError: StateFlow<String?> = _pairingError.asStateFlow()

    private val _conversations = MutableStateFlow<List<ConversationSummary>>(emptyList())

    /** What is on the computer. A live read, never a cache - empty when unreachable. */
    val conversations: StateFlow<List<ConversationSummary>> = _conversations.asStateFlow()

    private val _loadingConversations = MutableStateFlow(false)
    val loadingConversations: StateFlow<Boolean> = _loadingConversations.asStateFlow()

    /** Reads the desktop's conversation store. Null until a socket is open. */
    private var conversationReader: Conversations? = null

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
            _chat.value = ChatSession(open, viewModelScope, conversationId, history)
        }
    }

    /** Start a fresh conversation. Nothing is written until the first message is sent. */
    fun newConversation() {
        val open = socket ?: return
        _chat.value = ChatSession(open, viewModelScope)
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

        // Try every address the desktop has told us about, best first. At home that
        // is the LAN address and the first attempt wins; away from home the LAN
        // address fails fast and a mesh VPN address answers. This is what makes
        // working off the home network possible without Anodex running a relay.
        var lastFailure: Exception? = null

        for (address in stored.addresses) {
            val candidate = AnodexSocket(
                address = address,
                port = stored.port,
                certificateSha256 = fingerprint,
                deviceName = deviceName,
            )
            try {
                val handshake = candidate.connect(AnodexSocket.Credential.DeviceKey(host.secret))

                socket = candidate
                conversationReader = Conversations(candidate)
                _chat.value = ChatSession(candidate, viewModelScope)
                store.recordSeen(System.currentTimeMillis())
                refreshConversations()

                // Refreshed every time, so a desktop that gains a VPN after pairing
                // becomes reachable from away without the user doing anything. The
                // address that just worked is moved to the front, so the next
                // reconnect starts where this one succeeded.
                val reported = handshake.addresses.ifEmpty { stored.addresses }
                val ordered = listOf(address) + reported.filterNot { it == address }
                store.recordAddresses(ordered)
                _paired.value = stored.copy(addresses = ordered)

                // Model state is a nicety for the header, never a reason to fail a
                // connection that is otherwise working.
                return runCatching { readModelState(candidate) }.getOrNull()
            } catch (e: Exception) {
                candidate.close()
                lastFailure = e
            }
        }

        throw lastFailure ?: IllegalStateException("No address is known for that computer.")
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
                _pairingError.value = explainProbeFailure(address, e)
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
    private fun explainProbeFailure(address: String, error: Exception): String {
        val verdict = Reachability.verdictFor(address, localIPv4Addresses())
        return when (verdict) {
            Reachability.Verdict.DIFFERENT_SUBNET ->
                "This phone is on a different network than $address, so it cannot reach your " +
                    "computer. If your router has separate 2.4GHz and 5GHz names, join the one " +
                    "your computer is on."

            else ->
                error.message
                    ?: "Nothing answered at that address. Check the computer is awake and that " +
                    "remote access is still on."
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
    }

    /** Retry from the offline screen. */
    fun retry() {
        _paired.value?.let { controller.retryNow(it.toRef()) }
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
        super.onCleared()
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
