package dev.anodex.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** How far a picture is cut down before it is drawn. */
class SampleForTest {

    @Test
    fun `a screenshot shown large is not cut down at all`() {
        // 1920px tall, drawn about 900px tall: halving would leave 960, still enough.
        assertEquals(2, sampleFor(longEdge = 1920, targetPx = 900))
        assertEquals(1, sampleFor(longEdge = 1920, targetPx = 1000))
    }

    @Test
    fun `a 12 megapixel photo in a small tile is cut down hard`() {
        assertEquals(32, sampleFor(longEdge = 4000, targetPx = 115))
    }

    @Test
    fun `a picture is never reduced below the size it is shown`() {
        val sample = sampleFor(longEdge = 3000, targetPx = 700)
        assert(3000 / sample >= 700)
        assert(3000 / (sample * 2) < 700)
    }

    @Test
    fun `nonsense sizes decode untouched`() {
        assertEquals(1, sampleFor(longEdge = -1, targetPx = 500))
        assertEquals(1, sampleFor(longEdge = 2000, targetPx = 0))
    }
}
