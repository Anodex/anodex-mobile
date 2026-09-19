package dev.anodex.mobile.voice

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlin.concurrent.thread

/**
 * The microphone and the speaker.
 *
 * ## Why 24 kHz when recognition only needs 16
 *
 * Recognition is happy with 16 kHz and every model in the plan resamples to it.
 * Capture is at 24 anyway, because audio recorded at 16 kHz is 16 kHz forever: the
 * day there is a voice to fine-tune on real recordings, the recordings have to be
 * clean, and downsampling is free while the opposite is impossible. Capture high,
 * throw away copies, never the original — `docs/HANDOFF_VOICE.md` D7.
 *
 * ## Why VOICE_COMMUNICATION
 *
 * It asks Android for the telephony capture path, which on most devices brings the
 * platform's own echo canceller and noise suppressor with it. That matters for
 * barge-in: without echo cancellation the phone hears its own speaker, decides
 * somebody is talking, and interrupts itself. Using the OS effect here is the same
 * bargain as using `AudioRecord` at all — it is the platform, not a third-party
 * engine bundled into the app.
 */
object VoiceAudio {

    /** Samples per second, both directions. */
    const val SAMPLE_RATE = 24_000

    /** 20 ms, which is one frame on the wire: 480 samples, 960 bytes. */
    const val FRAME_SAMPLES = SAMPLE_RATE / 50

    const val FRAME_BYTES = FRAME_SAMPLES * 2

    private const val TAG = "voice"
}

/**
 * Capture, as a stream of 20 ms frames.
 *
 * Runs its own thread rather than a coroutine on a dispatcher, because a capture
 * loop that is late is a loop that drops audio, and a shared dispatcher is exactly
 * where lateness comes from. The thread does one thing and blocks on the microphone.
 */
class VoiceCapture {

    private var record: AudioRecord? = null
    private var effects: List<Any> = emptyList()
    @Volatile private var running = false

    val isRunning: Boolean get() = running

    /**
     * Start capturing.
     *
     * @param onFrame called on the capture thread, once per 20 ms, with 16-bit mono
     *   samples. It must not block: anything slow belongs behind a queue.
     * @return false if the microphone could not be opened at all, which is a
     *   permission that was refused or a device that is busy — both of which the
     *   caller has to show rather than retry.
     */
    @SuppressLint("MissingPermission")
    fun start(onFrame: (ShortArray, Long) -> Unit): Boolean {
        if (running) return true

        val minimum = AudioRecord.getMinBufferSize(
            VoiceAudio.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) {
            Log.w("voice", "this device will not capture at ${VoiceAudio.SAMPLE_RATE}Hz")
            return false
        }

        // Four frames of slack. Less and an ordinary scheduling hiccup drops audio;
        // more and the latency budget is spent on a buffer nobody asked for.
        val buffer = maxOf(minimum, VoiceAudio.FRAME_BYTES * 4)

        val opened = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                VoiceAudio.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                buffer,
            )
        } catch (denied: SecurityException) {
            Log.w("voice", "the microphone was refused", denied)
            return false
        }

        if (opened.state != AudioRecord.STATE_INITIALIZED) {
            opened.release()
            Log.w("voice", "the microphone did not initialise")
            return false
        }

        effects = attachEffects(opened.audioSessionId)
        record = opened
        running = true
        opened.startRecording()

        thread(name = "anodex-voice-capture", isDaemon = true) {
            val samples = ShortArray(VoiceAudio.FRAME_SAMPLES)
            while (running) {
                val read = opened.read(samples, 0, samples.size)
                if (read <= 0) {
                    // A negative read is the device going away — a call arriving, the
                    // headset being unplugged. Stop rather than spin.
                    if (read < 0) break else continue
                }
                val frame = if (read == samples.size) samples.copyOf() else samples.copyOf(read)
                onFrame(frame, System.currentTimeMillis())
            }
        }
        return true
    }

    fun stop() {
        running = false
        val open = record ?: return
        record = null
        runCatching { open.stop() }
        runCatching { open.release() }
        effects.forEach { effect ->
            runCatching {
                when (effect) {
                    is AcousticEchoCanceler -> effect.release()
                    is NoiseSuppressor -> effect.release()
                }
            }
        }
        effects = emptyList()
    }

    /**
     * Ask the platform for echo cancellation and noise suppression.
     *
     * Both are optional on Android and absent on plenty of devices, so both are
     * best-effort and neither is depended on. Where the canceller is missing, voice
     * needs headphones or it will talk over itself — which is the desktop's
     * situation too, and is stated as a v1 assumption rather than hidden.
     */
    private fun attachEffects(sessionId: Int): List<Any> {
        val attached = mutableListOf<Any>()
        if (AcousticEchoCanceler.isAvailable()) {
            runCatching { AcousticEchoCanceler.create(sessionId) }.getOrNull()?.let {
                it.enabled = true
                attached += it
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(sessionId) }.getOrNull()?.let {
                it.enabled = true
                attached += it
            }
        }
        return attached
    }
}

/**
 * Playback, written to as frames arrive.
 *
 * [flush] is the barge-in path: it drops whatever has been handed to the track but
 * not yet heard. Without it, interrupting means listening to the rest of a sentence
 * that is already in a buffer, which is exactly the thing that makes a voice
 * assistant feel like it is not listening.
 */
class VoicePlayback {

    private var track: AudioTrack? = null

    val isPlaying: Boolean get() = track != null

    fun start(): Boolean {
        if (track != null) return true

        val minimum = AudioTrack.getMinBufferSize(
            VoiceAudio.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) return false

        val opened = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Speech, not media: this is what routes it to the earpiece or the
                    // headset the way a call would, and what makes the volume keys
                    // change the right volume.
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(VoiceAudio.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minimum, VoiceAudio.FRAME_BYTES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (opened.state != AudioTrack.STATE_INITIALIZED) {
            opened.release()
            return false
        }
        opened.play()
        track = opened
        return true
    }

    /** Hand one frame to the speaker. Blocking is bounded by the track's own buffer. */
    fun write(pcm: ByteArray) {
        val open = track ?: return
        runCatching { open.write(pcm, 0, pcm.size) }
    }

    /** Barge-in: drop everything queued but not yet heard. */
    fun flush() {
        val open = track ?: return
        runCatching {
            open.pause()
            open.flush()
            open.play()
        }
    }

    fun stop() {
        val open = track ?: return
        track = null
        runCatching { open.pause() }
        runCatching { open.flush() }
        runCatching { open.stop() }
        runCatching { open.release() }
    }
}
