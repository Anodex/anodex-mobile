package dev.anodex.mobile.voice

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stage 1: talk, and hear it come back.
 *
 * The desktop echoes every audio frame with its header untouched, so this class
 * can time the whole path — microphone to network to computer to speaker — using
 * only this phone's clock. That number is the reason the stage exists. Every later
 * stage adds work inside this loop, so if the empty loop is already slow, the plan
 * needs changing before any model is downloaded rather than after.
 *
 * What it deliberately does not do is judge the number. `docs/HANDOFF_VOICE.md` §5
 * budgets 30 ms for the phone-to-desktop hop and about 800 ms for the whole
 * first-word path; whether the measurement agrees is something to find out on a
 * real phone on a real network, not to assert here.
 */
class VoiceLoop(
    private val send: (ByteArray) -> Boolean,
    private val capture: VoiceCapture = VoiceCapture(),
    private val playback: VoicePlayback = VoicePlayback(),
    private val activity: VoiceActivity = VoiceActivity(),
) {

    data class Stats(
        val running: Boolean = false,
        /** The desktop answered `start`, so the far end is really listening. */
        val acknowledged: Boolean = false,
        val speaking: Boolean = false,
        val framesSent: Int = 0,
        val framesHeard: Int = 0,
        /** Sent and never echoed. Persistent loss is the network, not the app. */
        val framesLost: Int = 0,
        val lastRoundTripMs: Int = 0,
        val bestRoundTripMs: Int = 0,
        val worstRoundTripMs: Int = 0,
        /** The middle of the last hundred, which is the honest one to quote. */
        val medianRoundTripMs: Int = 0,
        val bargeIns: Int = 0,
    )

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    private var seq = 0
    private var startedAtMs = 0L
    private val roundTrips = ArrayDeque<Int>()

    /**
     * Open the microphone and start sending.
     *
     * @return false if the microphone could not be opened — a refused permission or
     *   a device already using it. The caller shows that; retrying will not fix it.
     */
    fun start(): Boolean {
        if (_stats.value.running) return true

        seq = 0
        startedAtMs = System.currentTimeMillis()
        roundTrips.clear()
        activity.reset()
        _stats.value = Stats(running = true)

        // Said before the microphone opens, so a desktop with voice switched off
        // costs nothing but a frame. An unanswered start is indistinguishable from
        // one that was never heard, which is why the desktop answers it.
        send(VoiceFrames.control(nextSeq(), sessionMs(), """{"type":"start"}"""))

        if (!playback.start()) {
            Log.w("voice", "the speaker would not open")
        }

        val opened = capture.start { samples, atMs -> onCaptured(samples, atMs) }
        if (!opened) {
            stop()
            return false
        }
        return true
    }

    fun stop() {
        capture.stop()
        playback.stop()
        activity.reset()
        if (_stats.value.running) {
            send(VoiceFrames.control(nextSeq(), sessionMs(), """{"type":"stop"}"""))
        }
        _stats.value = _stats.value.copy(running = false, speaking = false, acknowledged = false)
    }

    /**
     * One captured frame.
     *
     * Called on the capture thread every 20 ms. Everything here is arithmetic and a
     * socket write, which does not block: `WebSocket.send` hands the frame to
     * OkHttp's writer queue and returns.
     */
    private fun onCaptured(samples: ShortArray, atMs: Long) {
        val speaking = activity.accept(samples, atMs)
        val wasSpeaking = _stats.value.speaking

        // Barge-in. Talking while the far end is talking means stop talking — and it
        // has to drop what is already queued, or interrupting means waiting out the
        // rest of a sentence that was buffered before anyone spoke.
        var bargeIns = _stats.value.bargeIns
        if (speaking && !wasSpeaking && playback.isPlaying) {
            playback.flush()
            bargeIns += 1
        }

        _stats.value = _stats.value.copy(speaking = speaking, bargeIns = bargeIns)

        // Silence is not sent. It is the cheapest saving available — most of any
        // conversation is nobody talking — and the far end reconstructs the gap from
        // the sequence numbers.
        if (!speaking) return

        val frame = VoiceFrames.encode(
            kind = VoiceFrames.KIND_AUDIO,
            seq = nextSeq(),
            atMs = sessionMs(),
            payload = toLittleEndianBytes(samples),
        )
        if (send(frame)) {
            _stats.value = _stats.value.copy(framesSent = _stats.value.framesSent + 1)
        }
    }

    /** One binary frame from the desktop. */
    fun onBinary(raw: ByteArray) {
        val frame = VoiceFrames.decode(raw) ?: return

        if (frame.kind == VoiceFrames.KIND_CONTROL) {
            val body = frame.payload.toString(Charsets.UTF_8)
            if (body.contains("\"started\"")) {
                _stats.value = _stats.value.copy(acknowledged = true)
            }
            return
        }

        playback.write(frame.payload)

        // The measurement. `atMs` is this phone's own clock, echoed back untouched,
        // so this subtracts two readings of one clock and never has to agree with
        // the computer about what time it is.
        val elapsed = sessionMs() - frame.atMs
        if (elapsed in 0..MAX_PLAUSIBLE_ROUND_TRIP_MS) {
            record(elapsed)
        }
    }

    private fun record(roundTripMs: Int) {
        roundTrips.addLast(roundTripMs)
        while (roundTrips.size > ROUND_TRIP_WINDOW) roundTrips.removeFirst()

        val sorted = roundTrips.sorted()
        val heard = _stats.value.framesHeard + 1
        _stats.value = _stats.value.copy(
            framesHeard = heard,
            // Sent minus heard, floored: an echo can arrive after the frame that
            // followed it, and a negative loss count is worse than no loss count.
            framesLost = maxOf(0, _stats.value.framesSent - heard),
            lastRoundTripMs = roundTripMs,
            bestRoundTripMs = sorted.first(),
            worstRoundTripMs = sorted.last(),
            medianRoundTripMs = sorted[sorted.size / 2],
        )
    }

    private fun nextSeq(): Int = seq++

    /** Milliseconds since this session began, which is all the header carries. */
    private fun sessionMs(): Int = (System.currentTimeMillis() - startedAtMs).toInt()

    private companion object {
        const val ROUND_TRIP_WINDOW = 100

        /**
         * Anything slower than this is not a round trip.
         *
         * A frame that arrives five seconds late is a frame from before a stall, and
         * averaging it in would describe a network nobody was using.
         */
        const val MAX_PLAUSIBLE_ROUND_TRIP_MS = 5_000
    }
}

/**
 * 16-bit samples to little-endian bytes.
 *
 * Little-endian because that is what `AudioTrack` reads back and what every PCM
 * consumer on both ends expects — the *header* is big-endian, being a network
 * protocol, and the payload is not. Worth stating once rather than discovering.
 */
internal fun toLittleEndianBytes(samples: ShortArray): ByteArray {
    val out = ByteArray(samples.size * 2)
    for (i in samples.indices) {
        val sample = samples[i].toInt()
        out[i * 2] = (sample and 0xFF).toByte()
        out[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
    }
    return out
}
