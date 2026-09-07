package dev.anodex.mobile.ui

import dev.anodex.mobile.ui.components.initialsOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a personality without a picture is abbreviated.
 *
 * Ported from the desktop's `personalityInitials`, and worth pinning because the two
 * are meant to agree: the same personality abbreviated two different ways on two
 * screens of one product is the sort of drift nothing at runtime notices.
 */
class PersonalityAvatarTest {

    @Test
    fun `one word gives two letters`() {
        assertEquals("RO", initialsOf("Rook"))
        assertEquals("VA", initialsOf("Vale"))
    }

    @Test
    fun `two words give one letter each`() {
        assertEquals("SM", initialsOf("Scout Master"))
    }

    @Test
    fun `punctuation is not a letter`() {
        // The desktop's own example: "Rook (mine)" must read RM, not "R(".
        assertEquals("RM", initialsOf("Rook (mine)"))
    }

    @Test
    fun `a name of nothing still renders something`() {
        // A blank byline is the one place this must not produce an empty tile, so an
        // unnamed personality falls back the way the desktop's does.
        assertEquals("UN", initialsOf(""))
        assertEquals("UN", initialsOf("   "))
    }

    @Test
    fun `a name with no letters at all is a question mark`() {
        assertEquals("?", initialsOf("!!!"))
    }
}
