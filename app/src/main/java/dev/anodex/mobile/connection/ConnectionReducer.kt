package dev.anodex.mobile.connection

/**
 * Something that happened to the connection. The reducer's only input besides the current state.
 *
 * These are deliberately *facts*, not commands: "the socket closed", not "go offline". What a
 * closed socket means depends on where we already were, and that judgement belongs in one place —
 * [reduceConnection] — rather than scattered across whoever noticed the event.
 */
sealed interface ConnectionEvent {

    /** Pairing completed, or a stored pairing was loaded at launch. */
    data class Paired(val host: HostIdentity) : ConnectionEvent

    /** The pairing was revoked, here or at the desktop. Everything else becomes irrelevant. */
    data object Unpaired : ConnectionEvent

    /** The socket opened and the handshake succeeded. */
    data class SocketOpened(val model: ModelStatus?) : ConnectionEvent

    /** The socket closed, or a heartbeat went unanswered. */
    data object SocketClosed : ConnectionEvent

    /**
     * The grace period elapsed without the socket coming back.
     *
     * Scheduled by whoever drives this reducer; keeping it an explicit event is what lets the
     * decision itself stay pure and testable without a clock or a coroutine.
     */
    data object GraceElapsed : ConnectionEvent

    /** The desktop reported a new model, or new context usage. */
    data class ModelUpdated(val model: ModelStatus?) : ConnectionEvent

    /** The phone's network changed relative to the one it paired on. */
    data class NetworkRelationChanged(val relation: NetworkRelation) : ConnectionEvent
}

/**
 * The whole connection-state decision, as one pure function.
 *
 * Pure on purpose. This is the root of the UI — every screen depends on it (§6.1) — and the bugs
 * it can produce are exactly the ones that are miserable to reproduce by hand: a blip that empties
 * the screen, a stale timer that knocks a healthy connection offline. Keeping the clock and the
 * scheduling outside means all of that is reachable from a unit test.
 *
 * @param nowEpochMs used only to stamp "last seen" while connected.
 */
fun reduceConnection(
    state: ConnectionState,
    event: ConnectionEvent,
    nowEpochMs: Long,
    networkRelation: NetworkRelation = NetworkRelation.UNKNOWN,
): ConnectionState = when (event) {

    is ConnectionEvent.Unpaired -> ConnectionState.Unpaired

    is ConnectionEvent.Paired ->
        // Re-pairing to the machine we are already talking to changes nothing on screen.
        if (state is ConnectionState.Connected && state.host == event.host) state
        else ConnectionState.Reconnecting(event.host, attempt = 1, lastSeenEpochMs = null)

    is ConnectionEvent.SocketOpened -> when (val host = state.hostOrNull()) {
        null -> state // Nothing paired; an open socket is meaningless.
        else -> ConnectionState.Connected(host, event.model)
    }

    is ConnectionEvent.SocketClosed -> when (state) {
        // The grace period exists so a Wi-Fi handoff or a lift doorway does not blank the screen.
        // Entered on *every* drop, including one that arrives while already reconnecting.
        is ConnectionState.Connected ->
            ConnectionState.Reconnecting(state.host, attempt = 1, lastSeenEpochMs = nowEpochMs)

        is ConnectionState.Reconnecting ->
            state.copy(attempt = state.attempt + 1)

        // Already offline, or nothing paired: a close tells us nothing new.
        is ConnectionState.Offline, ConnectionState.Unpaired -> state
    }

    is ConnectionEvent.GraceElapsed -> when (state) {
        is ConnectionState.Reconnecting -> ConnectionState.Offline(
            host = state.host,
            lastSeenEpochMs = state.lastSeenEpochMs,
            networkChanged = networkRelation == NetworkRelation.CHANGED,
        )

        // Critically, *not* applied to Connected. A grace timer scheduled before a successful
        // reconnect will still fire afterwards, and honouring it would throw a live, working
        // connection into a full-screen offline takeover for no reason the user could see.
        else -> state
    }

    is ConnectionEvent.ModelUpdated -> when (state) {
        is ConnectionState.Connected -> state.copy(model = event.model)
        else -> state // Model news about a machine we cannot reach is not worth showing.
    }

    is ConnectionEvent.NetworkRelationChanged -> when (state) {
        // Only the offline screen renders this, and it should update under the user rather than
        // wait for the next failed attempt — walking back onto the right Wi-Fi should say so.
        is ConnectionState.Offline ->
            state.copy(networkChanged = event.relation == NetworkRelation.CHANGED)

        else -> state
    }
}

/** The paired machine, if one is known in this state. */
fun ConnectionState.hostOrNull(): HostIdentity? = when (this) {
    is ConnectionState.Connected -> host
    is ConnectionState.Reconnecting -> host
    is ConnectionState.Offline -> host
    ConnectionState.Unpaired -> null
}

/**
 * Whether the grace timer should be running in this state.
 *
 * The driver reads this after every reduction: start a [ConnectionState.GRACE_PERIOD] timer on
 * entering [ConnectionState.Reconnecting], cancel it on leaving. Expressed here so the rule lives
 * next to the states it describes rather than inside whichever class happens to own the socket.
 */
val ConnectionState.awaitingGrace: Boolean
    get() = this is ConnectionState.Reconnecting
