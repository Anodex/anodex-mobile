package dev.anodex.mobile.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Drives [reduceConnection]: owns the current state, the grace timer and the reconnect backoff.
 *
 * The reducer decides *what a fact means*; this decides *when to act*. Keeping them apart is what
 * lets the interesting timing — a grace period that must not fire late, a backoff that must stay
 * capped — be tested in milliseconds of virtual time instead of by waiting around with a phone.
 *
 * Everything here is transport-agnostic on purpose. A socket does not exist yet (the protocol
 * contract and the desktop bridge come first), so [attemptConnection] is supplied by the caller and
 * this class never learns what a WebSocket is.
 */
class ConnectionController(
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val networkRelation: () -> NetworkRelation = { NetworkRelation.UNKNOWN },
    /**
     * Try once to reach the desktop. Returns the desktop's model state on success, or throws.
     *
     * Called on the controller's scope, one attempt at a time — never concurrently — so an
     * implementation does not need its own mutual exclusion.
     */
    private val attemptConnection: suspend (PairedHostRef) -> ModelStatus? = { null },
) {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Unpaired)

    /** The app's root state. Every screen reads this. */
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var graceTimer: Job? = null
    private var reconnectLoop: Job? = null

    /** Begin talking to a paired desktop. Replaces any host already in play. */
    fun pair(host: PairedHostRef) {
        dispatch(ConnectionEvent.Paired(host.identity))
        startReconnecting(host)
    }

    /** Forget the pairing and stop all activity. */
    fun unpair() {
        reconnectLoop?.cancel()
        reconnectLoop = null
        dispatch(ConnectionEvent.Unpaired)
    }

    /** The user asked to try again from the offline screen. Restarts the attempt loop now. */
    fun retryNow(host: PairedHostRef) {
        startReconnecting(host)
    }

    /**
     * An established connection dropped.
     *
     * The transport reports this; the controller does not discover it, because only the transport
     * knows the difference between a closed socket and a heartbeat that went unanswered. Restarting
     * the attempt loop from here is what stops a mid-session drop from stranding the app in
     * Reconnecting with nothing actually retrying.
     */
    fun onDisconnected(host: PairedHostRef) {
        dispatch(ConnectionEvent.SocketClosed)
        startReconnecting(host)
    }

    /** The desktop reported new model or context state. */
    fun onModelUpdated(model: ModelStatus?) = dispatch(ConnectionEvent.ModelUpdated(model))

    /** The phone's network changed. */
    fun onNetworkChanged(relation: NetworkRelation) =
        dispatch(ConnectionEvent.NetworkRelationChanged(relation))

    /**
     * Apply an event, then bring the timers in line with whatever state it produced.
     *
     * The timer management lives here rather than at each call site because getting it wrong is
     * subtle in exactly one direction: a grace timer left running across a successful reconnect.
     * The reducer already refuses to act on that stale fire, so this is defence in depth — but it
     * is the cheap half, and it keeps a pointless coroutine from surviving the transition.
     */
    private fun dispatch(event: ConnectionEvent) {
        val previous = _state.value
        val next = reduceConnection(previous, event, now(), networkRelation())
        _state.value = next

        when {
            next.awaitingGrace && !previous.awaitingGrace -> startGraceTimer()
            !next.awaitingGrace -> cancelGraceTimer()
        }
    }

    private fun startGraceTimer() {
        graceTimer?.cancel()
        graceTimer = scope.launch {
            delay(ConnectionState.GRACE_PERIOD)
            dispatch(ConnectionEvent.GraceElapsed)
        }
    }

    private fun cancelGraceTimer() {
        graceTimer?.cancel()
        graceTimer = null
    }

    /**
     * Retry until the desktop answers.
     *
     * Reconnection is phone-driven and has to be: a machine that is asleep or powered off cannot
     * send anything, so "the computer tells the phone it's back" is not available as a mechanism
     * (§6.1). The backoff is capped so a long-asleep desktop is still picked up within half a
     * minute of waking, rather than the phone having backed off to several minutes and left the
     * user staring at an offline screen well after the PC came back.
     */
    private fun startReconnecting(host: PairedHostRef) {
        reconnectLoop?.cancel()
        reconnectLoop = scope.launch {
            var wait = INITIAL_BACKOFF
            while (true) {
                val model = try {
                    attemptConnection(host)
                } catch (e: Exception) {
                    // Count the failure before waiting on it, so the attempt shown on screen
                    // matches the attempt actually being made rather than the previous one.
                    dispatch(ConnectionEvent.SocketClosed)
                    delay(wait)
                    wait = (wait * BACKOFF_FACTOR).coerceAtMost(MAX_BACKOFF)
                    continue
                }
                dispatch(ConnectionEvent.SocketOpened(model))
                return@launch
            }
        }
    }

    companion object {
        /** First retry is quick: most drops are a blip and come straight back. */
        val INITIAL_BACKOFF: Duration = 500.milliseconds

        /**
         * Capped so a desktop that wakes after hours is found within ~30s, not minutes. An
         * uncapped exponential is correct for a server under load and wrong for a machine that
         * spends most of its time asleep.
         */
        val MAX_BACKOFF: Duration = 30.seconds

        const val BACKOFF_FACTOR = 2
    }
}

/** The minimum a [ConnectionController] needs to know about the paired desktop to reach it. */
data class PairedHostRef(
    val identity: HostIdentity,
    val secret: String,
    val certificateFingerprint: String,
)
