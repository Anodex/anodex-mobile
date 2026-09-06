package dev.anodex.mobile.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When the app holds its own process alive, and when it lets go.
 *
 * Both mistakes here are quiet. Holding through `Offline` leaves the user with an
 * undismissable notification about a connection that gave up hours ago — the exact
 * behaviour that makes people distrust apps which run in the background. Letting go
 * during `Reconnecting` lets Android kill the process in the middle of the Wi-Fi
 * handoff the grace period exists to ride out, which loses the session rather than
 * pausing it, because nothing on the phone is cached to disk.
 */
class ProcessHoldTest {

    private val host = HostIdentity(id = "h1", displayName = "STUDIO-PC")

    @Test
    fun `a live connection is held, and named`() {
        val hold = processHoldFor(ConnectionState.Connected(host, model = null))

        assertNotNull(hold)
        assertEquals("STUDIO-PC", hold!!.hostName)
        assertEquals(true, hold.connected)
    }

    @Test
    fun `a reconnect inside the grace period is still held`() {
        // The single most important case. This is a two-second Wi-Fi blip, and dying
        // here is what makes the app look broken when the user gets it out again.
        val hold = processHoldFor(
            ConnectionState.Reconnecting(host, attempt = 2, lastSeenEpochMs = null)
        )

        assertNotNull(hold)
        assertEquals("STUDIO-PC", hold!!.hostName)
    }

    @Test
    fun `a reconnect says so rather than claiming to be connected`() {
        // The notification is the only thing the user can see while the app is in a
        // pocket. Saying "Connected" during a reconnect is the one lie it could tell.
        val hold = processHoldFor(
            ConnectionState.Reconnecting(host, attempt = 1, lastSeenEpochMs = null)
        )

        assertEquals(false, hold!!.connected)
    }

    @Test
    fun `giving up releases the process`() {
        val hold = processHoldFor(
            ConnectionState.Offline(host, lastSeenEpochMs = null, networkChanged = false)
        )

        assertNull(hold)
    }

    @Test
    fun `an unpaired app holds nothing`() {
        assertNull(processHoldFor(ConnectionState.Unpaired))
    }
}
