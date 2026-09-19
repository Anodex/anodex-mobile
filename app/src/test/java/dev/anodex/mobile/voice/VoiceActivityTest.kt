package dev.anodex.mobile.voice

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that decides whether anybody is talking.
 *
 * It is a simple thing and these are the properties that make it useful rather
 * than the ones that make it clever: that it learns the room instead of trusting a
 * fixed threshold, that it does not treat a pause between words as the end of a
 * sentence, and that it is not fooled by something merely loud.
 *
 * Feeding synthetic audio is not the same as feeding a kitchen, and the honest
 * measurement happens on a real phone. What this pins is the behaviour that would
 * otherwise rot silently as the constants are tuned.
 */
class VoiceActivityTest {

    private val frame = VoiceAudio.FRAME_SAMPLES

    private fun tone(amplitude: Int, hz: Double = 220.0): ShortArray =
        ShortArray(frame) { i ->
            val t = i.toDouble() / VoiceAudio.SAMPLE_RATE
            (sin(2 * PI * hz * t) * amplitude).toInt().toShort()
        }

    private fun hiss(amplitude: Int, seed: Int = 1): ShortArray {
        val random = Random(seed)
        return ShortArray(frame) { random.nextInt(-amplitude, amplitude + 1).toShort() }
    }

    private fun feed(gate: VoiceActivity, samples: ShortArray, frames: Int, from: Long): Long {
        var clock = from
        repeat(frames) {
            gate.accept(samples, clock)
            clock += 20
        }
        return clock
    }

    @Test
    fun `quiet stays shut`() {
        val gate = VoiceActivity()
        val clock = feed(gate, hiss(60), frames = 50, from = 0)
        assertFalse(gate.accept(hiss(60), clock))
    }

    @Test
    fun `speech over a quiet room opens it`() {
        val gate = VoiceActivity()
        val clock = feed(gate, hiss(60), frames = 30, from = 0)
        assertTrue(gate.accept(tone(6_000), clock))
    }

    @Test
    fun `the same speech in a loud room also opens it`() {
        // The reason the floor adapts at all. A fixed threshold is right in exactly
        // one room; phones are used in kitchens and cars twenty decibels apart.
        val gate = VoiceActivity()
        val clock = feed(gate, hiss(1_500), frames = 40, from = 0)
        assertTrue(gate.accept(tone(12_000), clock))
    }

    @Test
    fun `a fan is not somebody talking`() {
        val gate = VoiceActivity()
        var clock = feed(gate, hiss(1_200), frames = 60, from = 0)
        // Steady noise, however loud, becomes the floor rather than speech.
        clock = feed(gate, hiss(1_200, seed = 7), frames = 40, from = clock)
        assertFalse(gate.accept(hiss(1_200, seed = 9), clock))
    }

    @Test
    fun `a gap between words is not the end of a sentence`() {
        val gate = VoiceActivity()
        var clock = feed(gate, hiss(60), frames = 30, from = 0)
        clock = feed(gate, tone(8_000), frames = 10, from = clock)
        assertTrue(gate.speaking)

        // 200 ms of nothing — a pause inside an utterance, shorter than the 300 ms
        // hangover.
        clock = feed(gate, hiss(60), frames = 10, from = clock)
        assertTrue("a pause between words must not close the gate", gate.speaking)
    }

    @Test
    fun `it closes once somebody has actually stopped`() {
        val gate = VoiceActivity()
        var clock = feed(gate, hiss(60), frames = 30, from = 0)
        clock = feed(gate, tone(8_000), frames = 10, from = clock)
        clock = feed(gate, hiss(60), frames = 40, from = clock)
        assertFalse(gate.speaking)
    }

    @Test
    fun `hangover is measured in real time, not in frames`() {
        // A capture thread that stalls must not hold the gate open across the gap.
        val gate = VoiceActivity()
        var clock = feed(gate, hiss(60), frames = 30, from = 0)
        clock = feed(gate, tone(8_000), frames = 5, from = clock)
        assertTrue(gate.speaking)

        assertFalse(gate.accept(hiss(60), clock + 5_000))
    }

    @Test
    fun `the room is forgotten between sessions`() {
        val gate = VoiceActivity()
        feed(gate, hiss(3_000), frames = 60, from = 0)
        gate.reset()
        assertFalse(gate.speaking)

        // A floor learned in a car would otherwise deafen the phone in a bedroom.
        val clock = feed(gate, hiss(60), frames = 30, from = 0)
        assertTrue(gate.accept(tone(6_000), clock))
    }

    @Test
    fun `an empty frame changes nothing`() {
        val gate = VoiceActivity()
        assertFalse(gate.accept(ShortArray(0), 0))
    }

    @Test
    fun `digital silence does not open the gate on the first sound`() {
        // Any ratio against a floor of zero is infinite. The floor has a minimum for
        // exactly this: a phone that starts in a silent recording opens on the first
        // sample of noise otherwise.
        val gate = VoiceActivity()
        val clock = feed(gate, ShortArray(frame), frames = 30, from = 0)
        assertFalse(gate.accept(hiss(30), clock))
    }
}
