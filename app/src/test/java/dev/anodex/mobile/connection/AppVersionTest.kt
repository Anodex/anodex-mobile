package dev.anodex.mobile.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether to tell the user their phone build is behind the computer's.
 *
 * A notice nobody asked for has a low tolerance for being wrong. Saying "update
 * available" when there is none sends somebody off to reinstall what they already
 * have, and teaches them to ignore the banner — after which the one time it is right,
 * it is useless too. So every uncertain case here resolves to silence.
 */
class AppVersionTest {

    @Test
    fun `a phone behind the desktop is told`() {
        assertTrue(isUpdateAvailable(installed = "0.15.0", expected = "0.16.0"))
        assertTrue(isUpdateAvailable(installed = "0.16.0", expected = "0.16.1"))
    }

    @Test
    fun `versions compare as numbers, not as text`() {
        // The trap in every hand-rolled version compare: "0.9.0" sorts after "0.10.0"
        // as a string, so a phone a whole release behind is told it is current.
        assertTrue(isUpdateAvailable("0.9.0", "0.10.0"))
        assertFalse(isUpdateAvailable("0.10.0", "0.9.0"))
    }

    @Test
    fun `matching versions say nothing`() {
        assertFalse(isUpdateAvailable("0.16.0", "0.16.0"))
    }

    @Test
    fun `a phone ahead of the desktop is not told to downgrade`() {
        // Normal while a phone build is being tested before the desktop catches up.
        assertFalse(isUpdateAvailable("0.17.0", "0.16.0"))
    }

    @Test
    fun `a preview suffix is a label, not part of the order`() {
        // Every release so far is tagged `-preview.N`. Treating that as unparseable
        // would disable the check for the only builds that exist.
        assertTrue(isUpdateAvailable("0.15.0-preview.19", "0.16.0"))
        assertFalse(isUpdateAvailable("0.16.0-preview.22", "0.16.0"))
    }

    @Test
    fun `an older desktop that says nothing is left alone`() {
        // The field defaults to empty rather than failing the handshake, so this is
        // the ordinary case when the computer has not been updated yet.
        assertFalse(isUpdateAvailable("0.16.0", ""))
    }

    @Test
    fun `anything unreadable resolves to silence`() {
        for (value in listOf("", "unknown", "0.16", "0.16.0.1", "v0.16.0", "x.y.z", "  ")) {
            assertFalse(value, isUpdateAvailable(value, "0.16.0"))
            assertFalse(value, isUpdateAvailable("0.15.0", value))
        }
    }

    @Test
    fun `an absurdly long number does not crash the comparison`() {
        // A malformed field from anywhere should not throw on a screen the user
        // reached to find out what is going on.
        assertFalse(isUpdateAvailable("999999999999.0.0", "0.16.0"))
    }
}
