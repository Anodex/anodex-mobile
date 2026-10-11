package dev.anodex.mobile.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When the app holds its own process alive, and when it lets go.
 *
 * Letting go during `Reconnecting` lets Android kill the process in the middle of the
 * Wi-Fi handoff the grace period exists to ride out, which loses the session rather
 * than pausing it, because nothing on the phone is cached to disk.
 *
 * `Offline` used to let go too, to spare the user an ongoing notification about a
 * computer that is away. Measured on a Galaxy S7, that is what silenced the phone: the
 * app was reclaimed while the computer slept, and nothing was left to hear it wake.
 * Notifications reaching the phone outranks the quiet notification that makes it
 * possible.
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
    fun `a computer that is away is still waited for, and not called connected`() {
        val hold = processHoldFor(
            ConnectionState.Offline(host, lastSeenEpochMs = null, networkChanged = false)
        )

        assertNotNull(hold)
        assertEquals("STUDIO-PC", hold!!.hostName)
        assertEquals(false, hold.connected)
    }

    @Test
    fun `an unpaired app holds nothing`() {
        assertNull(processHoldFor(ConnectionState.Unpaired))
    }
}
