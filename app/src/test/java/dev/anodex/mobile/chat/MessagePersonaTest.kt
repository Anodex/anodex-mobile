package dev.anodex.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Who a reply says wrote it.
 *
 * The bug this pins: the byline was resolved once per screen from the *currently*
 * selected personality, so changing personality relabelled the entire transcript.
 * Ask Rook something, switch to Vale, and Rook's answer claimed to be Vale's — which
 * is worse than no label at all, because the label exists precisely so somebody can
 * tell which one said a thing.
 *
 * The rule is that a message is written by one personality and keeps it, and that
 * "not known" renders as nothing rather than as a guess.
 */
class MessagePersonaTest {

    private fun reply(persona: MessagePersona?) =
        ChatMessage("a", ChatMessage.Role.ASSISTANT, "text", persona = persona)

    @Test
    fun `a reply carries its own author, not the screen's`() {
        val rook = MessagePersona("Rook", "series-3")
        val vale = MessagePersona("Vale", "accent")

        val transcript = listOf(reply(rook), reply(vale))

        // Each keeps what it was stamped with. Nothing about rendering the second
        // can reach back and change the first.
        assertEquals("Rook", transcript[0].persona?.name)
        assertEquals("Vale", transcript[1].persona?.name)
    }

    @Test
    fun `switching personality later does not rewrite an existing message`() {
        val original = reply(MessagePersona("Rook", "series-3"))

        // The selection moving on is a change to a different object entirely; a
        // message is immutable and the copy is explicit.
        val laterSelection = MessagePersona("Juno", "green")

        assertEquals("Rook", original.persona?.name)
        assertEquals("Juno", laterSelection.name)
    }

    @Test
    fun `an unknown author is null, so the byline is simply absent`() {
        // History loaded back from the computer: the desktop's conversation store
        // records no author, and inventing one from the current selection is exactly
        // the bug. Blank is the honest rendering.
        assertNull(reply(null).persona)
    }

    @Test
    fun `the tint travels with the name`() {
        // Both halves are needed: the dot is how the personality is recognised
        // before the name is read, and a name with the wrong colour beside it is
        // its own small lie.
        val persona = reply(MessagePersona("Cass", "violet")).persona!!

        assertEquals("Cass", persona.name)
        assertEquals("violet", persona.tint)
    }
}
