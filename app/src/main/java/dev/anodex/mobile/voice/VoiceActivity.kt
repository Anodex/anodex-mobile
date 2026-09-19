package dev.anodex.mobile.voice

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Is somebody talking?
 *
 * The cheap half of the answer, and deliberately the whole of it for now. Loudness
 * against a noise floor the room sets itself, plus a zero-crossing check to tell
 * speech from a door closing, plus hysteresis so a syllable boundary is not a
 * sentence boundary. A few hundred lines of nothing, no model, no weights.
 *
 * The spec (`docs/HANDOFF_VOICE.md` §4.2) has this growing into a small trained
 * network, and it should — but not yet. Stage 1 exists to measure the loop, and a
 * gate that is honest about being a gate is enough to prove the microphone opens,
 * the frames flow and the barge-in path fires. Replacing the guts here later
 * changes nothing above it, which is why it is behind an interface this narrow.
 *
 * ## Why the floor adapts
 *
 * A fixed threshold works in exactly one room. Phones are used in kitchens, cars
 * and offices whose noise differs by twenty decibels, and a threshold that is right
 * in a quiet bedroom treats a fan as continuous speech. So the floor tracks the
 * quietest recent energy and the gate opens on a *ratio* above it.
 *
 * The floor rises quickly and falls slowly: mistaking a fan for silence costs one
 * second of listening, while mistaking speech for the floor deafens the thing
 * entirely, and it takes a while to notice that a room got quieter.
 */
class VoiceActivity(
    /** How far above the room's own noise a frame must be to count as speech. */
    private val openRatio: Double = 3.0,
    /** Falling back below this closes the gate — lower than [openRatio] on purpose. */
    private val closeRatio: Double = 1.8,
    /**
     * How long the gate stays open after the last loud frame.
     *
     * The pause between words is 150-300 ms and the pause at the end of a sentence
     * is not much longer, which is why endpointing is a harder problem than this
     * class solves. 300 ms keeps a gap inside one utterance from being read as the
     * end of it; deciding somebody has actually *finished* belongs to stage 4.
     */
    private val hangoverMs: Int = 300,
) {

    private var floor = 0.0
    private var seenFrames = 0
    private var open = false
    private var lastLoudAtMs = 0L
    private var lastLevel = 0f

    /** True while the gate is open — speech, or the hangover after it. */
    val speaking: Boolean get() = open

    /**
     * How loud the last frame was, 0..1, for something to draw.
     *
     * Against a fixed full scale rather than against the room's floor: the gate
     * wants a ratio, and a picture wants loudness. A halo scaled to the noise floor
     * would swell in a quiet room and barely move in a loud one, which is the
     * opposite of what somebody watching it expects.
     */
    val level: Float get() = lastLevel

    /**
     * Feed one frame of 16-bit mono samples.
     *
     * @param atMs this phone's clock, so hangover is measured in real time rather
     *   than in frames — a stalled capture thread must not hold the gate open.
     * @return true while the gate is open.
     */
    fun accept(samples: ShortArray, atMs: Long): Boolean {
        if (samples.isEmpty()) return open

        val energy = rms(samples)
        val voiced = crossingRate(samples) in SPEECH_CROSSINGS
        lastLevel = (energy / LEVEL_FULL_SCALE).toFloat().coerceIn(0f, 1f)

        seenFrames += 1
        floor = when {
            // The first frames set the floor outright. Starting from zero would put
            // every ratio at infinity and open the gate on the first breath.
            seenFrames <= WARMUP_FRAMES -> if (floor == 0.0) energy else (floor + energy) / 2
            energy < floor -> floor + (energy - floor) * FLOOR_FALL
            else -> floor + (energy - floor) * FLOOR_RISE
        }

        val reference = floor.coerceAtLeast(SILENT_FLOOR)
        val ratio = energy / reference

        if (!open) {
            if (ratio >= openRatio && voiced) {
                open = true
                lastLoudAtMs = atMs
            }
            return open
        }

        if (ratio >= closeRatio) {
            lastLoudAtMs = atMs
        } else if (atMs - lastLoudAtMs >= hangoverMs) {
            open = false
        }
        return open
    }

    /** Forget the room. Called when capture stops, so the next session re-learns it. */
    fun reset() {
        floor = 0.0
        seenFrames = 0
        open = false
        lastLoudAtMs = 0
        lastLevel = 0f
    }

    private fun rms(samples: ShortArray): Double {
        var sum = 0.0
        for (sample in samples) {
            val value = sample.toDouble()
            sum += value * value
        }
        return sqrt(sum / samples.size)
    }

    /**
     * How often the waveform crosses zero, as a fraction of the frame.
     *
     * Speech sits in a band: voiced sounds cross slowly, fricatives quickly, and
     * almost everything that is *not* speech sits outside both. It is a crude
     * feature and it earns its place by rejecting the thing loudness alone gets
     * wrong — a knock, a door, a phone set down on a table, all of which are loud
     * and none of which are somebody talking.
     */
    private fun crossingRate(samples: ShortArray): Double {
        var crossings = 0
        for (i in 1 until samples.size) {
            val previous = samples[i - 1]
            val current = samples[i]
            if ((previous >= 0) != (current >= 0) && abs(current - previous) > CROSSING_NOISE) {
                crossings += 1
            }
        }
        return crossings.toDouble() / samples.size
    }

    private companion object {
        /** Frames used to learn the room before any gate decision is trusted. */
        const val WARMUP_FRAMES = 10

        /** Rise fast, fall slow — see the class comment. */
        const val FLOOR_RISE = 0.05
        const val FLOOR_FALL = 0.30

        /**
         * A floor below this is treated as this.
         *
         * Digital silence has zero energy, and any ratio against zero opens the gate
         * on the first sample of noise. Sixteen-bit full scale is 32,767, so this is
         * about -70 dBFS: quieter than any room and louder than nothing.
         */
        const val SILENT_FLOOR = 10.0

        /** Ignore crossings from dither and rounding, which are not the signal. */
        const val CROSSING_NOISE = 200

        /**
         * What counts as a full halo.
         *
         * Not 32,767. Conversational speech at arm's length lands around 2,000-8,000
         * RMS, and scaling the picture to the loudest sound a microphone can encode
         * would leave the ring nearly still while somebody talks normally.
         */
        const val LEVEL_FULL_SCALE = 8_000.0

        /** The band speech lives in, as a fraction of samples that cross zero. */
        val SPEECH_CROSSINGS = 0.01..0.35
    }
}
