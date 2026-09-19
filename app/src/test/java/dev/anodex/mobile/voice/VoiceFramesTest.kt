package dev.anodex.mobile.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The byte layout, which two repositories have to agree on.
 *
 * `VoiceFrames.kt` and the desktop's `voiceFrames.ts` are two hand-written copies
 * of one format, for the same reason `Frames.kt` is: the generated artifact covers
 * the channels, and this is the envelope underneath them. The failure when they
 * disagree is silent — the desktop cannot read the frame, answers nothing, and the
 * loop simply never comes back — so the layout is pinned here in bytes rather than
 * trusted to stay in step.
 */
class VoiceFramesTest {

    @Test
    fun `a frame is twelve bytes of header and then the payload`() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val encoded = VoiceFrames.encode(VoiceFrames.KIND_AUDIO, seq = 1, atMs = 2, payload = payload)

        assertEquals(VoiceFrames.HEADER_BYTES + payload.size, encoded.size)
        assertEquals(1, encoded[0].toInt())
        assertEquals(VoiceFrames.KIND_AUDIO, encoded[1].toInt())
        assertEquals(0, encoded[2].toInt())
        assertEquals(0, encoded[3].toInt())
    }

    @Test
    fun `the counters are big-endian, because the header is a network header`() {
        // Pinned against the desktop's readUInt32BE. The payload is little-endian
        // PCM and the header is not, which is the kind of thing that is obvious
        // until it is three in the morning.
        val encoded = VoiceFrames.encode(
            VoiceFrames.KIND_AUDIO,
            seq = 0x01020304,
            atMs = 0x0A0B0C0D,
            payload = ByteArray(0),
        )
        assertArrayEquals(
            byteArrayOf(0x01, 0x02, 0x03, 0x04),
            encoded.copyOfRange(4, 8),
        )
        assertArrayEquals(
            byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D),
            encoded.copyOfRange(8, 12),
        )
    }

    @Test
    fun `the whole frame, byte for byte`() {
        // The same vector the desktop asserts in `voiceFrames.test.ts`. Two
        // hand-written copies of one format drift silently — the far end cannot
        // read the frame, answers nothing, and the loop simply never comes back —
        // so both repositories pin the identical bytes.
        val encoded = VoiceFrames.encode(
            VoiceFrames.KIND_AUDIO,
            seq = 0x01020304,
            atMs = 0x0A0B0C0D,
            // 0x1234 and 0x5678 as little-endian PCM, which is what capture sends.
            payload = byteArrayOf(0x34, 0x12, 0x78, 0x56),
        )

        assertArrayEquals(
            byteArrayOf(
                0x01, // version
                0x01, // kind: audio
                0x00, 0x00, // reserved flags
                0x01, 0x02, 0x03, 0x04, // seq, big-endian
                0x0A, 0x0B, 0x0C, 0x0D, // atMs, big-endian
                0x34, 0x12, 0x78, 0x56, // payload, little-endian samples
            ),
            encoded,
        )
    }

    @Test
    fun `what goes out comes back`() {
        val payload = ByteArray(960) { (it % 251).toByte() }
        val decoded = VoiceFrames.decode(
            VoiceFrames.encode(VoiceFrames.KIND_AUDIO, seq = 42, atMs = 987_654, payload = payload),
        )

        assertEquals(VoiceFrames.KIND_AUDIO, decoded?.kind)
        assertEquals(42, decoded?.seq)
        assertEquals(987_654, decoded?.atMs)
        assertArrayEquals(payload, decoded?.payload)
    }

    @Test
    fun `a sequence number that has wrapped still compares equal`() {
        // The far end echoes the raw 32 bits. Widening to a Long here would make a
        // wrapped counter stop matching the one that comes back, and the symptom
        // would be every frame counted as lost after 49 days of talking.
        val encoded = VoiceFrames.encode(
            VoiceFrames.KIND_AUDIO,
            seq = -1,
            atMs = -1,
            payload = ByteArray(0),
        )
        assertEquals(-1, VoiceFrames.decode(encoded)?.seq)
        assertEquals(-1, VoiceFrames.decode(encoded)?.atMs)
    }

    @Test
    fun `a control frame carries its json`() {
        val decoded = VoiceFrames.decode(VoiceFrames.control(1, 2, """{"type":"start"}"""))
        assertEquals(VoiceFrames.KIND_CONTROL, decoded?.kind)
        assertEquals("""{"type":"start"}""", decoded?.payload?.toString(Charsets.UTF_8))
    }

    // --- what must not be read --------------------------------------------------

    @Test
    fun `too short to hold a header`() {
        assertNull(VoiceFrames.decode(ByteArray(VoiceFrames.HEADER_BYTES - 1)))
    }

    @Test
    fun `a framing this build does not speak`() {
        val encoded = VoiceFrames.encode(VoiceFrames.KIND_AUDIO, 1, 1, ByteArray(0))
        encoded[0] = 2
        assertNull(VoiceFrames.decode(encoded))
    }

    @Test
    fun `a kind nothing sends`() {
        val encoded = VoiceFrames.encode(VoiceFrames.KIND_AUDIO, 1, 1, ByteArray(0))
        encoded[1] = 99
        assertNull(VoiceFrames.decode(encoded))
    }

    @Test
    fun `a reserved flag that has no meaning yet`() {
        val encoded = VoiceFrames.encode(VoiceFrames.KIND_AUDIO, 1, 1, ByteArray(0))
        encoded[3] = 1
        assertNull(VoiceFrames.decode(encoded))
    }

    @Test
    fun `a payload larger than any audio frame`() {
        var threw = false
        try {
            VoiceFrames.encode(
                VoiceFrames.KIND_AUDIO,
                1,
                1,
                ByteArray(VoiceFrames.MAX_PAYLOAD_BYTES + 1),
            )
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue("encoding must refuse a payload it could never send", threw)
    }

    @Test
    fun `samples become little-endian bytes`() {
        // The payload is PCM, which every consumer on both ends reads little-endian.
        assertArrayEquals(
            byteArrayOf(0x34, 0x12, 0x78, 0x56),
            toLittleEndianBytes(shortArrayOf(0x1234, 0x5678)),
        )
    }

    @Test
    fun `a decoded frame compares by its contents`() {
        // The generated equals compares arrays by identity, which would make every
        // assertion above pass whatever the bytes were.
        val one = VoiceFrames.decode(VoiceFrames.encode(1, 1, 1, byteArrayOf(9)))
        val two = VoiceFrames.decode(VoiceFrames.encode(1, 1, 1, byteArrayOf(9)))
        val other = VoiceFrames.decode(VoiceFrames.encode(1, 1, 1, byteArrayOf(8)))
        assertEquals(one, two)
        assertTrue(one != other)
    }
}
