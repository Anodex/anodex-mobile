package dev.anodex.mobile.voice

import android.util.Log
import dev.anodex.mobile.transport.AnodexSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Everything about voice that the rest of the app has to know, which is very little.
 *
 * The view model owns one of these and tells it two things: a connection arrived,
 * and a connection went. Everything else — whether the computer can do voice at all,
 * the microphone, the socket, the state a screen draws — lives behind this. That is
 * not tidiness for its own sake: the whole feature is meant to be removable, and a
 * controller with a three-call surface is removable in a way that the same code
 * sprinkled through a four-thousand-line view model is not.
 *
 * See `docs/HANDOFF_VOICE.md` §9 in the desktop repository.
 */
class VoiceController(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(VoiceScreenState())

    /** What the screen draws. */
    val state: StateFlow<VoiceScreenState> = _state.asStateFlow()

    private val _available = MutableStateFlow(false)

    /**
     * Whether this computer offers voice at all.
     *
     * False until a connected desktop has said `voice.1`, which means the button
     * that opens the screen is absent rather than present-and-failing on every
     * computer that has not got it. A control that does nothing is worse than no
     * control: it is a bug report.
     */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private var socket: AnodexSocket? = null
    private var loop: VoiceLoop? = null
    private var pump: Job? = null
    private var ticker: Job? = null

    /**
     * A connection arrived.
     *
     * Capabilities are read from this handshake rather than remembered, because the
     * same computer announces a different list once it updates.
     */
    fun onConnected(socket: AnodexSocket, capabilities: List<String>, hostName: String) {
        this.socket = socket
        _available.value = capabilities.contains(VOICE_CAPABILITY)
        _state.value = VoiceScreenState(hostName = hostName)
    }

    /** The connection went. Whatever was open is closed; nothing is retried. */
    fun onDisconnected() {
        stop()
        socket = null
        _available.value = false
        _state.value = VoiceScreenState()
    }

    /**
     * Open the microphone.
     *
     * The caller has already asked for the microphone permission: this returns false
     * rather than asking, because a permission prompt raised from here would appear
     * over whatever screen happened to be showing.
     *
     * @return false if there is no connection, the computer cannot do voice, or the
     *   microphone would not open.
     */
    fun start(): Boolean {
        val open = socket ?: return false
        if (!_available.value) return false
        if (loop != null) return true

        val started = VoiceLoop(send = open::sendBinary)
        loop = started

        // Binary frames are their own stream beside the JSON one. Collected here and
        // nowhere else, so that removing voice removes the only reader.
        pump = scope.launch {
            open.binary.collect { frame -> started.onBinary(frame) }
        }

        if (!started.start()) {
            stop()
            Log.w("voice", "the microphone would not open")
            return false
        }

        // The loop's own state changes on the capture thread fifty times a second.
        // Redrawing a screen at that rate is pointless — this samples it at
        // something a person can see, which also keeps the halo from flickering.
        ticker = scope.launch {
            while (isActive) {
                publish(started)
                delay(REDRAW_MS)
            }
        }
        publish(started)
        return true
    }

    fun stop() {
        loop?.stop()
        loop = null
        ticker?.cancel()
        ticker = null
        pump?.cancel()
        pump = null
        _state.value = _state.value.copy(
            running = false,
            connected = false,
            listening = false,
            answering = false,
            level = 0f,
        )
    }

    private fun publish(loop: VoiceLoop) {
        val stats = loop.stats.value
        _state.value = _state.value.copy(
            running = stats.running,
            connected = stats.acknowledged,
            listening = stats.speaking,
            answering = loop.answering(),
            level = stats.level,
            framesHeard = stats.framesHeard,
            framesLost = stats.framesLost,
            medianRoundTripMs = stats.medianRoundTripMs,
            bestRoundTripMs = stats.bestRoundTripMs,
            worstRoundTripMs = stats.worstRoundTripMs,
        )
    }

    private companion object {
        /** About 30 a second: smooth to watch, and a fraction of the capture rate. */
        const val REDRAW_MS = 33L
    }
}
