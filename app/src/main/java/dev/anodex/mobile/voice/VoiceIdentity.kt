package dev.anodex.mobile.voice

/**
 * Who is speaking.
 *
 * One voice for now, named because an unnamed voice is "the voice" and that is a
 * setting, not a character. A name is also what makes a second one cheap later:
 * the screen already shows one, so adding a switcher is a list rather than a new
 * idea.
 *
 * **Arc.** The halo the Speak screen draws around the mark, and the diagonal the
 * mark itself is cut on — so the name is already on screen before it is said. Short,
 * one syllable, and it survives being heard badly through a phone speaker, which is
 * the only place anyone will ever hear it.
 *
 * It is one constant: renaming the voice is editing this file and nothing else. It
 * was Vane for an afternoon, and that is exactly how long it should take.
 *
 * The description is what a chooser shows underneath, in the same register as the
 * rest of the app: plain, and about how it sounds rather than how clever it is.
 */
const val VOICE_NAME = "Arc"

/** One line under the name. */
const val VOICE_DESCRIPTION = "Even and unhurried"
