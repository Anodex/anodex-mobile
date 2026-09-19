package dev.anodex.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the screen says, and in which order it decides.
 *
 * These are two short strings and they are the entire feedback somebody gets while
 * holding a phone to their face — there is no transcript on this screen and, at this
 * stage, no words coming back. The precedence between them is the whole design:
 * "Listening" while the far end is also talking would be true and useless, and
 * "Go ahead" before the computer has answered invites somebody to talk into nothing.
 */
class VoiceScreenStateTest {

    @Test
    fun `stopped says what it is and where it goes`() {
        val state = VoiceScreenState(hostName = "STUDIO-PC")
        assertEquals("Speak", state.headline())
        assertEquals("Talk to Anodex on STUDIO-PC", state.detail())
    }

    @Test
    fun `no host name still reads as a sentence`() {
        // A phone paired by typing an address had no name for the computer until it
        // connected, and "Talk to Anodex on " is the shape of bug that ships.
        assertEquals("Talk to Anodex", VoiceScreenState().detail())
    }

    @Test
    fun `waiting for the computer is not an invitation to talk`() {
        val state = VoiceScreenState(running = true, connected = false)
        assertEquals("Asking your computer…", state.headline())
        assertEquals("Waiting for it to answer", state.detail())
    }

    @Test
    fun `answering wins over listening`() {
        // Both are true the moment somebody talks over the reply, and the useful one
        // is the one that says interrupting is allowed.
        val state = VoiceScreenState(running = true, connected = true, listening = true, answering = true)
        assertEquals("Answering", state.headline())
        assertEquals("Talk over it whenever you like", state.detail())
    }

    @Test
    fun `listening says what ends the turn`() {
        val state = VoiceScreenState(running = true, connected = true, listening = true)
        assertEquals("Listening", state.headline())
        assertEquals("Stop talking and it will answer", state.detail())
    }

    @Test
    fun `a refused microphone says so instead of inviting another tap`() {
        // The idle screen would invite a tap that does nothing: Android asks once and
        // then remembers, so the second request is refused without showing anything
        // and the screen looks broken.
        val state = VoiceScreenState(microphoneRefused = true, hostName = "STUDIO-PC")
        assertEquals("Anodex cannot hear", state.headline())
        assertEquals("Allow the microphone in Android's settings for Anodex", state.detail())
    }

    @Test
    fun `a refused microphone outranks whatever the loop thinks`() {
        // It can be refused while a previous session's state is still on the screen.
        val state = VoiceScreenState(running = true, connected = true, listening = true, microphoneRefused = true)
        assertEquals("Anodex cannot hear", state.headline())
    }

    @Test
    fun `connected and quiet is the invitation`() {
        val state = VoiceScreenState(running = true, connected = true)
        assertEquals("Go ahead", state.headline())
        assertEquals("It is listening", state.detail())
    }
}
