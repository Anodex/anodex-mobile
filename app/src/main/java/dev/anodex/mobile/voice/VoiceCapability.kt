package dev.anodex.mobile.voice

/**
 * The name voice announces itself under on the handshake.
 *
 * `feature.major`: if the audio framing changes shape this becomes `voice.2`, an
 * older computer does not recognise it, and the feature is absent there rather than
 * broken. The same reasoning as the protocol major, scoped to one feature instead
 * of the whole connection.
 *
 * The phone says this on every connection whatever the computer replies. Gating is
 * the desktop's job — it is the end that has to have voice switched on, and a phone
 * that stayed quiet until it was sure would never find out.
 */
const val VOICE_CAPABILITY = "voice.1"
