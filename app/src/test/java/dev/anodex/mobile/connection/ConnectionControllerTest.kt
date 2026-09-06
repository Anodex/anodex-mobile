package dev.anodex.mobile.connection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The controller's timing.
 *
 * All of this runs in virtual time, which is the point of keeping the timers out of the reducer:
 * a backoff that would take four minutes of standing about with a phone is checked here in
 * microseconds, and a grace period that must not fire a moment early or late is asserted exactly
 * rather than approximately.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionControllerTest {

    private val host = PairedHostRef(
        identity = HostIdentity(id = "h1", displayName = "MERLIN-PC"),
        secret = "secret",
        certificateFingerprint = "fp",
    )
    private val model = ModelStatus("Qwen3-30B", contextUsedTokens = 100, contextTotalTokens = 8_192)

    private class Unreachable : Exception("desktop unreachable")

    @Test
    fun `a reachable desktop connects without ever showing offline`() = runTest {
        val seen = mutableListOf<ConnectionState>()
        val controller = ConnectionController(
            scope = this,
            attemptConnection = { model },
        )
        controller.pair(host)
        advanceUntilIdle()
        seen += controller.state.value

        assertEquals(ConnectionState.Connected(host.identity, model), controller.state.value)
        assertTrue(seen.none { it is ConnectionState.Offline })

        controller.stop()
    }

    @Test
    fun `the offline takeover waits out the full grace period and not a moment less`() = runTest {
        val controller = ConnectionController(
            scope = this,
            attemptConnection = { throw Unreachable() },
        )
        controller.pair(host)

        // One millisecond before the deadline the normal UI is still on screen.
        advanceTimeBy(ConnectionState.GRACE_PERIOD - 1.milliseconds)
        assertTrue(
            "still reconnecting at grace - 1ms, was ${controller.state.value}",
            controller.state.value is ConnectionState.Reconnecting,
        )

        advanceTimeBy(2.milliseconds)
        assertTrue(
            "offline once grace elapses, was ${controller.state.value}",
            controller.state.value is ConnectionState.Offline,
        )

        controller.stop()
    }

    @Test
    fun `a blip inside the grace period never reaches the offline screen`() = runTest {
        // The lift-doorway case: unreachable briefly, then fine. The user should see a reconnecting
        // indicator in the header and nothing else — certainly not a full-screen takeover.
        var reachable = false
        val controller = ConnectionController(
            scope = this,
            attemptConnection = { if (reachable) model else throw Unreachable() },
        )

        val seen = mutableListOf<ConnectionState>()
        // backgroundScope is cancelled by runTest when the test body finishes, so a collector
        // started here cannot outlive the test or stall it.
        backgroundScope.launch { controller.state.collect { seen += it } }

        controller.pair(host)
        advanceTimeBy(2.seconds)
        reachable = true
        advanceUntilIdle()

        assertEquals(ConnectionState.Connected(host.identity, model), controller.state.value)
        assertTrue("never went offline, saw $seen", seen.none { it is ConnectionState.Offline })

        controller.stop()
    }

    @Test
    fun `a desktop that wakes after hours is found within the backoff cap`() = runTest {
        // The backoff must stay capped. Uncapped exponential retry is right for a server under
        // load and wrong for a machine that spends most of its life asleep: by the time the PC
        // wakes, the phone would be waiting minutes between attempts.
        var reachable = false
        var attempts = 0
        val controller = ConnectionController(
            scope = this,
            attemptConnection = {
                attempts++
                if (reachable) model else throw Unreachable()
            },
        )
        controller.pair(host)

        advanceTimeBy(4.minutes)
        val attemptsWhileAsleep = attempts
        assertTrue(
            "kept retrying while the desktop slept, only $attemptsWhileAsleep attempts",
            attemptsWhileAsleep > 5,
        )

        reachable = true
        advanceTimeBy(ConnectionController.MAX_BACKOFF + 1.seconds)
        advanceUntilIdle()

        assertEquals(ConnectionState.Connected(host.identity, model), controller.state.value)

        controller.stop()
    }

    @Test
    fun `a stale grace timer cannot fire after a reconnect`() = runTest {
        // The timer is scheduled on the drop and the socket returns before it expires. Both the
        // controller (cancels it) and the reducer (ignores it) guard this; the assertion is that
        // the pair of them actually hold.
        var reachable = false
        val controller = ConnectionController(
            scope = this,
            attemptConnection = { if (reachable) model else throw Unreachable() },
        )
        controller.pair(host)
        advanceTimeBy(1.seconds)

        reachable = true
        advanceUntilIdle()
        assertTrue(controller.state.value is ConnectionState.Connected)

        // Well past when the original grace timer would have expired.
        advanceTimeBy(ConnectionState.GRACE_PERIOD * 2)
        advanceUntilIdle()

        assertEquals(
            "a live connection must survive its own stale grace timer",
            ConnectionState.Connected(host.identity, model),
            controller.state.value,
        )

        controller.stop()
    }

    @Test
    fun `unpairing stops the retry loop rather than leaving it running`() = runTest {
        var attempts = 0
        val controller = ConnectionController(
            scope = this,
            attemptConnection = {
                attempts++
                throw Unreachable()
            },
        )
        controller.pair(host)
        advanceTimeBy(5.seconds)

        controller.unpair()
        val afterUnpair = attempts
        advanceTimeBy(2.minutes)

        assertEquals(ConnectionState.Unpaired, controller.state.value)
        assertEquals("no attempts after unpairing", afterUnpair, attempts)

        controller.stop()
    }

    @Test
    fun `a mid-session drop restarts the retry loop`() = runTest {
        var reachable = true
        var attempts = 0
        val controller = ConnectionController(
            scope = this,
            attemptConnection = {
                attempts++
                if (reachable) model else throw Unreachable()
            },
        )
        controller.pair(host)
        advanceUntilIdle()
        assertTrue(controller.state.value is ConnectionState.Connected)

        // The transport notices the socket has gone. Without onDisconnected restarting the loop,
        // the app would sit in Reconnecting with nothing actually retrying.
        reachable = false
        val beforeDrop = attempts
        controller.onDisconnected(host)
        advanceTimeBy(10.seconds)

        assertTrue("retried after the drop", attempts > beforeDrop)
        assertTrue(controller.state.value is ConnectionState.Offline)

        controller.stop()
    }

    @Test
    fun `going offline on a changed network says so`() = runTest {
        val controller = ConnectionController(
            scope = this,
            networkRelation = { NetworkRelation.CHANGED },
            attemptConnection = { throw Unreachable() },
        )
        controller.pair(host)
        advanceTimeBy(ConnectionState.GRACE_PERIOD + 1.seconds)

        val offline = controller.state.value as ConnectionState.Offline
        assertTrue("the wrong-network explanation is shown", offline.networkChanged)

        controller.stop()
    }
}
