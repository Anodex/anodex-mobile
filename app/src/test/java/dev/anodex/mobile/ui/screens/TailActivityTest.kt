package dev.anodex.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The phone looked frozen for most of a multi-step turn, and this was why.
 *
 * Reported from the phone: the chat "stops for a moment like the AI is
 * thinking, then another chat appears, then it seems to be thinking for a
 * moment, then another chat appears." Those pauses are real work — the model
 * thinking between steps — and nothing on screen said so.
 */
class TailActivityTest {

    @Test
    fun `a turn that is still going always says something`() {
        // The regression. Once the first words landed, the old condition was an
        // `else` on "are there words yet", so a model pausing to think mid-answer
        // showed a finished-looking reply and no sign of life.
        assertEquals(TailActivity.WRITING, tailActivity(streaming = true, hasText = true, toolRunning = false))

        // And the other half: after a tool finished, the old condition required
        // `tools.isEmpty()`, which stayed false for the rest of the turn.
        assertEquals(TailActivity.THINKING, tailActivity(streaming = true, hasText = false, toolRunning = false))
    }

    @Test
    fun `a running tool reports itself, so the tail stays quiet`() {
        // Already on screen as its own row or the folded-log line. Saying it here
        // too is the same news twice.
        assertNull(tailActivity(streaming = true, hasText = false, toolRunning = true))
        assertNull(tailActivity(streaming = true, hasText = true, toolRunning = true))
    }

    @Test
    fun `a finished reply says nothing at all`() {
        assertNull(tailActivity(streaming = false, hasText = true, toolRunning = false))
        assertNull(tailActivity(streaming = false, hasText = false, toolRunning = false))
    }
}
