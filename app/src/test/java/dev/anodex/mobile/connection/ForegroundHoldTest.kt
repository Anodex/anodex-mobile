package dev.anodex.mobile.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stop must never land between `startForegroundService` and `startForeground`.
 *
 * Seen on the emulator: the connection service was started and, 8 ms later, stopped
 * while Android was "still waiting for start foreground", and the app was killed with
 * `ForegroundServiceDidNotStartInTimeException`.
 */
class ForegroundHoldTest {

    @Test
    fun `a stop with nothing on its way happens at once`() {
        val hold = ForegroundHold()
        assertTrue(hold.stopRequested())
    }

    @Test
    fun `a stop right after a start waits for the start to land`() {
        val hold = ForegroundHold()
        hold.startRequested()

        assertFalse(hold.stopRequested())
        // The service reaches the foreground, and only then stops.
        assertTrue(hold.reachedForeground())
    }

    @Test
    fun `a start after the stop cancels it`() {
        val hold = ForegroundHold()
        hold.startRequested()
        assertFalse(hold.stopRequested())
        hold.startRequested()

        assertFalse(hold.reachedForeground())
        assertFalse(hold.reachedForeground())
    }

    @Test
    fun `the stop waits for every start still on its way`() {
        val hold = ForegroundHold()
        hold.startRequested()
        hold.startRequested()
        assertFalse(hold.stopRequested())

        assertFalse(hold.reachedForeground())
        assertTrue(hold.reachedForeground())
    }

    @Test
    fun `starts that landed leave nothing pending`() {
        val hold = ForegroundHold()
        hold.startRequested()
        assertFalse(hold.reachedForeground())

        assertTrue(hold.stopRequested())
    }
}
