package dev.anodex.mobile.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How a turn's changes read on a phone.
 *
 * This list is what somebody decides on when they are not at their computer — the
 * only record of what actually ended up different on disk. Every one of these is
 * about it not being quietly misleading: a path that hides which file it is, or a
 * size that says something happened when nothing did.
 */
class ChangedFileTest {

    @Test
    fun `a deep path keeps the part that identifies it`() {
        // A project has four files called index. The last two segments are what tell
        // them apart; the leading directories are what will not fit.
        assertEquals("…/sim/orbit.rs", shortPath("crates/engine/src/sim/orbit.rs"))
    }

    @Test
    fun `a shallow path is left alone`() {
        assertEquals("README.md", shortPath("README.md"))
        assertEquals("src/main.rs", shortPath("src/main.rs"))
    }

    @Test
    fun `windows separators are understood`() {
        // The computer is a Windows machine and its checkpoints carry its own
        // separators. Splitting only on "/" would leave the whole path as one
        // segment and print it in full, which is exactly what this avoids.
        assertEquals("…/sim/orbit.rs", shortPath("crates\\engine\\src\\sim\\orbit.rs"))
    }

    @Test
    fun `no size change says nothing at all`() {
        // A rewrite that lands on the same length is still a change, and "0 bytes"
        // beside it reads as "nothing happened" — which is worse than silence.
        assertNull(describeDelta(0))
    }

    @Test
    fun `growth and shrinkage are signed`() {
        assertEquals("+240 bytes", describeDelta(240))
        assertEquals("−240 bytes", describeDelta(-240))
    }

    @Test
    fun `larger changes scale`() {
        assertEquals("+1.2 KB", describeDelta(1229))
        assertEquals("−2.0 MB", describeDelta(-2 * 1024 * 1024))
    }

    @Test
    fun `the delta is after minus before`() {
        val grew = ChangedFile("a.txt", "modified", beforeSize = 100, afterSize = 340, conflicted = false)
        val shrank = ChangedFile("b.txt", "modified", beforeSize = 340, afterSize = 100, conflicted = false)

        assertEquals(240L, grew.sizeDelta)
        assertEquals(-240L, shrank.sizeDelta)
    }
}
