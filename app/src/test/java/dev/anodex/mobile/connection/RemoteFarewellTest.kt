package dev.anodex.mobile.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reasons the computer gives for going away.
 *
 * These numbers are a wire contract with `src/shared/remoteFarewell.ts` on the
 * desktop, mirrored by hand because there is no shared build between an Electron
 * app and an Android one — the same deliberate exception `Frames.kt` makes.
 *
 * The thing being protected is small and easy to lose: a socket that dies tells you
 * nothing about why. Asleep, quit, remote access switched off, Wi-Fi gone, laptop
 * carried out of range — all identical from here, which is why the offline screen
 * used to offer a list of three guesses. In most of those cases the computer knew
 * exactly which it was, and simply never said.
 */
class RemoteFarewellTest {

    @Test
    fun `the codes match the desktop, written out rather than derived`() {
        // Deliberately literal. A test that read the values off the enum would agree
        // with any change to it, including one that silently stops matching the
        // desktop — which is the only failure this can actually have.
        assertEquals(4001, RemoteFarewell.QUITTING.code)
        assertEquals(4002, RemoteFarewell.SLEEPING.code)
        assertEquals(4003, RemoteFarewell.DISABLED.code)
        assertEquals(4004, RemoteFarewell.RESTARTING.code)
        assertEquals(4005, RemoteFarewell.UNPAIRED.code)
    }

    @Test
    fun `every code is one the websocket spec leaves to applications`() {
        for (farewell in RemoteFarewell.entries) {
            assertTrue("${farewell.name} is below 4000", farewell.code >= 4000)
            assertTrue("${farewell.name} is above 4999", farewell.code <= 4999)
        }
    }

    @Test
    fun `no two reasons share a code`() {
        // The lookup matches on the number, so two reasons on one code is one of them
        // silently becoming the other.
        val codes = RemoteFarewell.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `a code this build has never heard of is not guessed at`() {
        // A newer desktop may have reasons this app predates. Null falls back to the
        // generic explanation, which is honest; inventing one would not be.
        assertNull(RemoteFarewell.ofCode(4099))
        assertNull(RemoteFarewell.ofCode(1000)) // an ordinary, normal-closure code
        assertNull(RemoteFarewell.ofCode(0))
    }

    @Test
    fun `every code round-trips to its own reason`() {
        for (farewell in RemoteFarewell.entries) {
            assertEquals(farewell, RemoteFarewell.ofCode(farewell.code))
        }
    }

    @Test
    fun `each explanation names the computer and what to do about it`() {
        // The whole value is in the difference between them: "asleep" and "closed"
        // send somebody to different places. An explanation that did not say which
        // would be the checklist again, in fewer words.
        for (farewell in RemoteFarewell.entries) {
            val said = farewell.explain("Gort")
            assertTrue("${farewell.name} does not name the computer", said.contains("Gort"))
            assertTrue("${farewell.name} is not a sentence", said.endsWith("."))
        }

        assertTrue(RemoteFarewell.SLEEPING.explain("Gort").contains("Wake"))
        assertTrue(RemoteFarewell.QUITTING.explain("Gort").contains("Open it again"))
        assertTrue(RemoteFarewell.DISABLED.explain("Gort").contains("Settings"))
        assertTrue(RemoteFarewell.UNPAIRED.explain("Gort").contains("Pair again"))
    }

    @Test
    fun `only a rebind is worth reconnecting straight into`() {
        // The rest need a human to wake a computer, open an app, change a setting or
        // pair again. A phone retrying hard against any of those spends battery to
        // learn nothing.
        assertTrue(RemoteFarewell.RESTARTING.reconnectsImmediately)

        for (farewell in RemoteFarewell.entries - RemoteFarewell.RESTARTING) {
            assertFalse(farewell.name, farewell.reconnectsImmediately)
        }
    }
}
