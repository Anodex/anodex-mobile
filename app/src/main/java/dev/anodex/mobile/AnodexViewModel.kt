package dev.anodex.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.anodex.mobile.connection.ConnectionController
import dev.anodex.mobile.connection.ConnectionState
import dev.anodex.mobile.connection.NetworkMonitor
import dev.anodex.mobile.connection.PairedHostRef
import android.os.Build
import android.util.Base64
import dev.anodex.mobile.chat.ChatSession
import dev.anodex.mobile.connection.HostIdentity
import dev.anodex.mobile.connection.ModelStatus
import dev.anodex.mobile.pairing.PairedHost
import dev.anodex.mobile.pairing.PairedHostStore
import dev.anodex.mobile.pairing.PairingPayload
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

        val next = AnodexSocket(
            address = stored.address,
            port = stored.port,
            certificateSha256 = hexToBytes(host.certificateFingerprint),
            deviceName = deviceName,
        )
        next.connect(AnodexSocket.Credential.DeviceKey(host.secret))

        socket = next
        _chat.value = ChatSession(next, viewModelScope)
        store.recordSeen(System.currentTimeMillis())

        // Model state is a nicety for the header, never a reason to fail a working connection.
        return runCatching { readModelState(next) }.getOrNull()
    }

    private suspend fun readModelState(open: AnodexSocket): ModelStatus? {
        val fields = open.invoke("models:get-state") as? JsonObject ?: return null
        val name = (fields["modelName"] as? JsonPrimitive)?.content ?: return null
        return ModelStatus(name = name, contextUsedTokens = 0, contextTotalTokens = 0)
    }

    /**
     * Finish pairing with a scanned code.
     *
     * The device key the desktop issues is persisted and never logged. The network is recorded
     * at the same moment, so the offline screen can later say whether the phone has moved.
     */
    fun completePairing(payload: PairingPayload) {
        viewModelScope.launch {
            val pairingSocket = AnodexSocket(
                address = payload.address,
                port = payload.port,
                certificateSha256 = payload.certificateSha256,
                deviceName = deviceName,
            )
            try {
                val secret = Base64.encodeToString(
                    payload.secret,
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                )
                val handshake =
                    pairingSocket.connect(AnodexSocket.Credential.PairingSecret(secret))
                val issued = handshake.issuedDeviceKey
                    ?: error("That computer did not issue a device key.")

                val host = PairedHost(
                    identity = HostIdentity(id = payload.hostId, displayName = payload.displayName),
                    secret = issued,
                    certificateFingerprint = bytesToHex(payload.certificateSha256),
                    pairedNetworkId = networkMonitor.currentNetworkId(),
                    lastSeenEpochMs = System.currentTimeMillis(),
                    address = payload.address,
                    port = payload.port,
                )
                store.save(host)
                _paired.value = host
                _pairingError.value = null
                controller.pair(host.toRef())
            } catch (e: Exception) {
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
