package dev.anodex.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The rule this codebase keeps re-learning, finally pinned.
 *
 * `AGENTS.md` names reporting a failure as an emptiness the most common defect
 * shape here and says it has been found in six-plus places; four more turned up
 * the same week. Those four all lived in `AnodexViewModel`, and not by accident —
 * it takes an `Application`, builds its own store, monitor and notifier, and so
 * cannot be instantiated in a JVM unit test. The one file the suite could not
 * reach is the one the defect kept coming back to.
 *
 * Moving the decision out of it is what makes these tests possible at all. They
 * are short because the rule is short. Being short is the point: the reason
 * `getOrDefault(emptyList())` kept winning is that it was the least typing, and
 * nothing else will beat it on those terms.
 */
class RefreshingTest {

    private class Unreachable(message: String?) : Exception(message)

    @Test
    fun `a good read replaces the value and clears the error`() {
        // Clearing matters as much as setting. An error left standing after a
        // successful retry is a screen still apologising for something that worked.
        val read = Result.success(listOf("b")).orKeep(listOf("a"), "boom")

        assertEquals(listOf("b"), read.value)
        assertNull(read.error)
    }

    @Test
    fun `a failed read keeps exactly what was there`() {
        // Not a copy, not an empty list — the same list, still on screen. Those
        // conversations are still on the computer; the phone simply could not ask.
        val previous = listOf("a", "b")
        val read = Result.failure<List<String>>(Unreachable("no route to host"))
            .orKeep(previous, "Could not read your conversations.")

        assertSame(previous, read.value)
    }

    @Test
    fun `the throwable speaks for itself when it has something to say`() {
        val read = Result.failure<String>(Unreachable("no route to host"))
            .orKeep("old", "Could not read your projects.")

        assertEquals("no route to host", read.error)
    }

    @Test
    fun `a silent failure still says something`() {
        // Plenty of exceptions carry no message at all, and a null error would put
        // the screen straight back into the state this exists to prevent: an empty
        // list with no explanation.
        for (useless in listOf(null, "", "   ")) {
            val read = Result.failure<String>(Unreachable(useless))
                .orKeep("old", "Could not read your projects.")

            assertEquals(
                "a message of <$useless> should have fallen back",
                "Could not read your projects.",
                read.error,
            )
        }
    }

    @Test
    fun `an empty first read is still an empty screen, not an error`() {
        // The case the rule must not over-correct: a genuinely empty list is a fact
        // about the computer and should be reported as one. "Nothing here" is only
        // a lie when nobody asked.
        val read = Result.success(emptyList<String>()).orKeep(listOf("stale"), "boom")

        assertEquals(emptyList<String>(), read.value)
        assertNull(read.error)
    }

    @Test
    fun `nothing to keep is still nothing to claim`() {
        // A first read that fails has no previous value to fall back on, so the
        // screen is empty either way — the difference is whether it says why. This
        // is the case that produced "No conversations yet. Start one." on the screen
        // the app opens to.
        val read = Result.failure<List<String>>(Unreachable(null))
            .orKeep(emptyList(), "Could not read your conversations.")

        assertEquals(emptyList<String>(), read.value)
        assertEquals("Could not read your conversations.", read.error)
    }

    @Test
    fun `it works on a value that is not a list`() {
        // The unread count, a host name, a parsed state object — the rule is about
        // the shape of the failure, not the shape of the data.
        data class Projects(val names: List<String>, val active: String?)

        val previous = Projects(listOf("sandbox"), "sandbox")
        val read = Result.failure<Projects>(Unreachable("socket closed"))
            .orKeep(previous, "Could not read your projects.")

        assertSame(previous, read.value)
        assertEquals("socket closed", read.error)
    }
}
