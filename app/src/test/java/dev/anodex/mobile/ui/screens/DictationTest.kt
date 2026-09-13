package dev.anodex.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/** How dictated words join what is already in the composer. */
class DictationTest {

    @Test
    fun `an empty draft becomes what was said`() {
        assertEquals("fix the orbit jitter", withDictated("", "fix the orbit jitter"))
    }

    @Test
    fun `dictation adds to a typed draft rather than replacing it`() {
        assertEquals("In Nebula2, fix the jitter", withDictated("In Nebula2,", "fix the jitter"))
    }

    @Test
    fun `no doubled space when the draft already ends in one`() {
        assertEquals("Then run the tests", withDictated("Then ", "run the tests"))
        assertEquals("Line one\nline two", withDictated("Line one\n", "line two"))
    }

    @Test
    fun `nothing heard leaves the draft alone`() {
        assertEquals("keep this", withDictated("keep this", "   "))
    }
}
