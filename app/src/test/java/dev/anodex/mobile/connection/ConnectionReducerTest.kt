package dev.anodex.mobile.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connection reducer.
 *
 * Every test here corresponds to a failure a user would actually experience, because that is the
 * only reason this logic is worth pinning down. The two that matter most are the grace period
 * (a blip must not blank the screen) and the stale timer (a timer from before a reconnect must not
 * knock a working connection offline) — both are miserable to reproduce by hand and trivial here.
 */
class ConnectionReducerTest {

    private val host = HostIdentity(id = "h1", displayName = "STUDIO-PC")
    private val other = HostIdentity(id = "h2", displayName = "STUDIO-PC")
    private val model = ModelStatus("Qwen3-30B", contextUsedTokens = 100, contextTotalTokens = 8_192)
    private val now = 1_757_000_000_000L

    private fun reduce(
        state: ConnectionState,
        event: ConnectionEvent,
        at: Long = now,
        network: NetworkRelation = NetworkRelation.UNKNOWN,
    ) = reduceConnection(state, event, at, network)

    private val connected = ConnectionState.Connected(host, model)

    // --- the grace period ------------------------------------------------------------------

    @Test
    fun `a dropped socket goes to reconnecting, never straight to offline`() {
        val next = reduce(connected, ConnectionEvent.SocketClosed)
        assertEquals(ConnectionState.Reconnecting(host, attempt = 1, lastSeenEpochMs = now), next)
    }

    @Test
    fun `reconnecting within the grace period returns to connected without passing through offline`() {
        var state: ConnectionState = connected
        state = reduce(state, ConnectionEvent.SocketClosed)
        state = reduce(state, ConnectionEvent.SocketOpened(model))
        assertEquals(connected, state)
    }

    @Test
    fun `only an elapsed grace period produces the offline takeover`() {
        var state: ConnectionState = connected
        state = reduce(state, ConnectionEvent.SocketClosed)
        state = reduce(state, ConnectionEvent.GraceElapsed, network = NetworkRelation.SAME)

        assertEquals(
            ConnectionState.Offline(host, lastSeenEpochMs = now, networkChanged = false),
            state,
        )
    }

    @Test
    fun `a stale grace timer cannot knock a live connection offline`() {
        // The sequence that produces this: drop, timer scheduled, socket comes back inside the
        // grace period, then the already-scheduled timer fires anyway. Honouring it would replace
        // a working screen with a full-screen "STUDIO-PC is offline".
        var state: ConnectionState = connected
        state = reduce(state, ConnectionEvent.SocketClosed)
        state = reduce(state, ConnectionEvent.SocketOpened(model))
        state = reduce(state, ConnectionEvent.GraceElapsed)

        assertEquals(connected, state)
    }

    @Test
    fun `repeated drops while reconnecting count attempts and keep the original last-seen`() {
        var state: ConnectionState = connected
        state = reduce(state, ConnectionEvent.SocketClosed, at = now)
        state = reduce(state, ConnectionEvent.SocketClosed, at = now + 5_000)
        state = reduce(state, ConnectionEvent.SocketClosed, at = now + 9_000)

        val reconnecting = state as ConnectionState.Reconnecting
        assertEquals(3, reconnecting.attempt)
        // "Last seen" means the last time it worked, not the last time we tried.
        assertEquals(now, reconnecting.lastSeenEpochMs)
    }

    @Test
    fun `the grace timer runs in exactly one state`() {
        assertTrue(ConnectionState.Reconnecting(host, 1, now).awaitingGrace)
        assertFalse(connected.awaitingGrace)
        assertFalse(ConnectionState.Offline(host, now, false).awaitingGrace)
        assertFalse(ConnectionState.Unpaired.awaitingGrace)
    }

    // --- network diagnosis -----------------------------------------------------------------

    @Test
    fun `going offline on a different network says so`() {
        var state: ConnectionState = connected
        state = reduce(state, ConnectionEvent.SocketClosed)
        state = reduce(state, ConnectionEvent.GraceElapsed, network = NetworkRelation.CHANGED)

        assertTrue((state as ConnectionState.Offline).networkChanged)
    }

    @Test
    fun `an unknown network relation is not reported as a changed one`() {
        // UNKNOWN means "we could not tell", and guessing would put a confident, wrong explanation
        // in front of someone trying to diagnose a real problem.
        var state: ConnectionState = connected
        state = reduce(state, ConnectionEvent.SocketClosed)
        state = reduce(state, ConnectionEvent.GraceElapsed, network = NetworkRelation.UNKNOWN)

        assertFalse((state as ConnectionState.Offline).networkChanged)
    }

    @Test
    fun `walking back onto the paired network updates the offline screen in place`() {
        val offline = ConnectionState.Offline(host, lastSeenEpochMs = now, networkChanged = true)
        val next = reduce(offline, ConnectionEvent.NetworkRelationChanged(NetworkRelation.SAME))

        assertFalse((next as ConnectionState.Offline).networkChanged)
    }

    // --- pairing ---------------------------------------------------------------------------

    @Test
    fun `revoking the pairing wins from every state`() {
        val states = listOf(
            connected,
            ConnectionState.Reconnecting(host, 2, now),
            ConnectionState.Offline(host, now, true),
            ConnectionState.Unpaired,
        )
        for (state in states) {
            assertEquals(ConnectionState.Unpaired, reduce(state, ConnectionEvent.Unpaired))
        }
    }

    @Test
    fun `pairing a new host starts attempting, with no last-seen to report`() {
        val next = reduce(ConnectionState.Unpaired, ConnectionEvent.Paired(host))
        val reconnecting = next as ConnectionState.Reconnecting

        assertEquals(host, reconnecting.host)
        assertEquals(1, reconnecting.attempt)
        // We have never reached this machine, so "last seen 14 minutes ago" would be a fabrication.
        assertNull(reconnecting.lastSeenEpochMs)
    }

    @Test
    fun `re-pairing the machine we are already connected to does not disturb the screen`() {
        assertEquals(connected, reduce(connected, ConnectionEvent.Paired(host)))
    }

    @Test
    fun `pairing a different machine while connected drops the old connection`() {
        val next = reduce(connected, ConnectionEvent.Paired(other))
        assertEquals(other, (next as ConnectionState.Reconnecting).host)
    }

    // --- events that should be ignored ------------------------------------------------------

    @Test
    fun `an open socket with nothing paired changes nothing`() {
        assertEquals(
            ConnectionState.Unpaired,
            reduce(ConnectionState.Unpaired, ConnectionEvent.SocketOpened(model)),
        )
    }

    @Test
    fun `model news about an unreachable machine is not shown`() {
        val offline = ConnectionState.Offline(host, now, false)
        assertEquals(offline, reduce(offline, ConnectionEvent.ModelUpdated(model)))
    }

    @Test
    fun `context usage updates in place while connected`() {
        val fuller = model.copy(contextUsedTokens = 7_900)
        val next = reduce(connected, ConnectionEvent.ModelUpdated(fuller))

        assertEquals(fuller, (next as ConnectionState.Connected).model)
    }

    @Test
    fun `a close while already offline is not treated as a fresh drop`() {
        val offline = ConnectionState.Offline(host, now, false)
        assertEquals(offline, reduce(offline, ConnectionEvent.SocketClosed))
    }
}
