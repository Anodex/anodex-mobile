package dev.anodex.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who the home screen thinks it is talking to.
 *
 * This refused to use a name for a long time, on the grounds that anything it had
 * would have been lifted from the Android profile — a stranger using your first name.
 * That objection was about the source, and the source changed: the desktop now hands
 * over the display name somebody set on their own machine.
 *
 * What it must never do is greet somebody by a placeholder. The desktop ships with
 * "Anodex User" in that field, so a naive greeting says "Good evening, Anodex User"
 * to everybody who has not touched it — which is worse than not greeting them at all,
 * and is what most of an app's users would see.
 */
class GreetingTest {

    @Test
    fun `a name that was chosen is used`() {
        assertTrue(greeting("Merlin").endsWith(", Merlin"))
    }

    @Test
    fun `the shipped placeholder is not a name`() {
        // The case that matters. Anyone who never edited their profile has this.
        assertFalse(greeting(DEFAULT_NAME).contains(DEFAULT_NAME))
        assertFalse(greeting("anodex user").contains("anodex"))
        assertFalse(greeting("  Anodex User  ").contains("Anodex"))
    }

    @Test
    fun `no name at all is greeted anyway`() {
        // Before the computer has answered, and on a phone that is not paired. The
        // greeting is not conditional on knowing who you are.
        for (unknown in listOf(null, "", "   ")) {
            val said = greeting(unknown)
            assertFalse("greeted with a trailing comma: <$said>", said.endsWith(","))
            assertTrue("said nothing at all", said.isNotBlank())
        }
    }

    @Test
    fun `a name is trimmed rather than repeated verbatim`() {
        assertEquals(greeting("Merlin"), greeting("  Merlin  "))
    }

    @Test
    fun `the hour decides the words and the name never changes them`() {
        // Split so a change to the clock wording cannot quietly drop the name, and
        // adding a name cannot quietly change the time of day.
        val withName = greeting("Merlin")
        val without = greeting(null)

        assertTrue(withName.startsWith(without))
    }
}
