package dev.anodex.mobile.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The desktop's `userIdentity.test.ts`, case for case, so both screens greet the same person. */
class IdentityTest {

    @Test
    fun `greets by the person's own first name, never by a stand-in`() {
        assertEquals("Sam", firstNameOf("Sam Rivera"))
        assertEquals("Sam", firstNameOf("  Sam  "))
        assertNull(firstNameOf(PLACEHOLDER_NAME))
        assertNull(firstNameOf("You"))
        assertNull(firstNameOf(""))
        assertNull(firstNameOf(null))
    }

    @Test
    fun `draws up to two initials, and none without a name`() {
        assertEquals("SR", initialsOf("Sam Rivera"))
        assertEquals("AM", initialsOf("ana maria lopez"))
        assertEquals("", initialsOf(PLACEHOLDER_NAME))
    }

    @Test
    fun `an unknown look falls back to the mark's gradient`() {
        assertEquals(AVATAR_LOOKS[0], avatarLook(99))
        assertEquals(AVATAR_LOOKS[3], avatarLook(3))
        assertEquals(6, AVATAR_LOOKS.size)
    }
}
