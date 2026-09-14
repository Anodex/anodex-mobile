package dev.anodex.mobile.chat

import dev.anodex.mobile.chat.PhotoShrinker.Companion.sampleToFit
import dev.anodex.mobile.chat.PhotoShrinker.Companion.shouldShrink
import dev.anodex.mobile.chat.PhotoShrinker.Companion.uploadName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which pictures are made smaller before sending, and how far. */
class PhotoShrinkerTest {

    @Test
    fun `a camera photo is shrunk`() {
        assertTrue(shouldShrink("image/jpeg", 4_500_000, 4000, 3000))
    }

    @Test
    fun `a small jpeg within the size limit goes as it is`() {
        assertFalse(shouldShrink("image/jpeg", 400_000, 1600, 1200))
    }

    @Test
    fun `an oversized jpeg is shrunk even when its file is small`() {
        assertTrue(shouldShrink("image/jpeg", 600_000, 4000, 3000))
    }

    @Test
    fun `a screenshot keeps its crisp text unless it is huge`() {
        assertFalse(shouldShrink("image/png", 900_000, 1080, 2400))
        assertTrue(shouldShrink("image/png", 6_000_000, 4000, 8000))
    }

    @Test
    fun `gifs and unknown pictures are never re-encoded`() {
        assertFalse(shouldShrink("image/gif", 9_000_000, 4000, 4000))
        assertFalse(shouldShrink(null, 9_000_000, 4000, 4000))
        assertFalse(shouldShrink("image/jpeg", 9_000_000, 0, 0))
    }

    @Test
    fun `decoding samples down without going under the target`() {
        assertEquals(1, sampleToFit(4000, 2048))
        assertEquals(2, sampleToFit(4096, 2048))
        assertEquals(4, sampleToFit(9000, 2048))
    }

    @Test
    fun `the upload keeps the original name as a jpeg`() {
        assertEquals("IMG_20260913_1200.jpg", uploadName("IMG_20260913_1200.HEIC"))
        assertEquals("my_photo_.jpg", uploadName("my/photo?.png"))
        assertTrue(uploadName(null).startsWith("photo-"))
    }
}
