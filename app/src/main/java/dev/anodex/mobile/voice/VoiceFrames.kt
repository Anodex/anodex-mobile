package dev.anodex.mobile.voice

/**
 * The audio wire format, mirroring the desktop's `src/main/voice/voiceFrames.ts`.
 *
 * Hand-written for the same reason `Frames.kt` is: the generated artifact covers
 * the *channels*, and this is the envelope underneath them. If the two files ever
 * disagree, the desktop answers a frame it could not read with nothing at all, and
 * the symptom is a loop that never comes back — so the tests below the fold pin the
 * byte layout rather than trusting the two copies to stay in step.
 *
 * ```
 *   0      1      2      4        8         12
 *   +------+------+------+--------+---------+------------
 *   | ver  | kind | flags| seq    | atMs    | payload
 *   +------+------+------+--------+---------+------------
 *   u8     u8     u16    u32be    u32be
 * ```
 *
 * [atMs] is **this phone's own clock**, and the desktop echoes it back untouched.
 * That is what makes the round trip measurable without the two ends agreeing on
 * the time: this end subtracts its own two readings. A frame whose clock were
 * rewritten in the middle would be measuring the computer instead.
 *
 * Audio goes out as binary rather than inside a JSON frame because base64 costs a
 * third more on traffic that runs fifty frames a second — and, more to the point,
 * because a continuous stream through the JSON parser would become a wire format
 * both apps had to keep.
 */
object VoiceFrames {

    const val VERSION: Int = 1
    const val HEADER_BYTES: Int = 12

    /** The largest payload sent or read. 20 ms of 24 kHz 16-bit mono is 960 bytes. */
    const val MAX_PAYLOAD_BYTES: Int = 8 * 1024

    const val KIND_AUDIO: Int = 1
    const val KIND_CONTROL: Int = 2

    data class Frame(
        val kind: Int,
        val seq: Int,
        val atMs: Int,
        val payload: ByteArray,
    ) {
        // Generated equals/hashCode compare the array by identity, which makes every
        // test that checks a decoded frame silently pass. Written out rather than
        // left to the default because the failure is invisible.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return kind == other.kind &&
                seq == other.seq &&
                atMs == other.atMs &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = kind
            result = 31 * result + seq
            result = 31 * result + atMs
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    fun encode(kind: Int, seq: Int, atMs: Int, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD_BYTES) {
            "voice payload of ${payload.size} bytes is too large to send"
        }
        val out = ByteArray(HEADER_BYTES + payload.size)
        out[0] = VERSION.toByte()
        out[1] = kind.toByte()
        out[2] = 0
        out[3] = 0
        writeUInt32(out, 4, seq)
        writeUInt32(out, 8, atMs)
        payload.copyInto(out, HEADER_BYTES)
        return out
    }

    /** A control frame: a small UTF-8 JSON object. */
    fun control(seq: Int, atMs: Int, json: String): ByteArray =
        encode(KIND_CONTROL, seq, atMs, json.toByteArray(Charsets.UTF_8))

    /**
     * Read a frame, or null if it is not one.
     *
     * Never throws. A malformed frame from the desktop is not something this end
     * can act on, and it is also not a reason to tear down a working connection —
     * the same stance `parseServerFrame` takes one layer up.
     */
    fun decode(raw: ByteArray): Frame? {
        if (raw.size < HEADER_BYTES) return null
        if (raw[0].toInt() and 0xFF != VERSION) return null

        val kind = raw[1].toInt() and 0xFF
        if (kind != KIND_AUDIO && kind != KIND_CONTROL) return null

        // Reserved, and checked rather than ignored: the first build to use a flag
        // should meet a clear refusal, not a peer quietly agreeing to something it
        // did not understand.
        if ((raw[2].toInt() or raw[3].toInt()) != 0) return null

        val payloadSize = raw.size - HEADER_BYTES
        if (payloadSize > MAX_PAYLOAD_BYTES) return null

        return Frame(
            kind = kind,
            seq = readUInt32(raw, 4),
            atMs = readUInt32(raw, 8),
            payload = raw.copyOfRange(HEADER_BYTES, raw.size),
        )
    }

    private fun writeUInt32(out: ByteArray, at: Int, value: Int) {
        out[at] = (value ushr 24).toByte()
        out[at + 1] = (value ushr 16).toByte()
        out[at + 2] = (value ushr 8).toByte()
        out[at + 3] = value.toByte()
    }

    /**
     * Read big-endian, keeping the raw 32 bits.
     *
     * Deliberately not widened to a Long. Both fields are counters the far end
     * compares modulo 2^32, and a sequence that wraps must compare equal to the one
     * that comes back — so this end keeps exactly the bits it sent.
     */
    private fun readUInt32(raw: ByteArray, at: Int): Int =
        ((raw[at].toInt() and 0xFF) shl 24) or
            ((raw[at + 1].toInt() and 0xFF) shl 16) or
            ((raw[at + 2].toInt() and 0xFF) shl 8) or
            (raw[at + 3].toInt() and 0xFF)
}
