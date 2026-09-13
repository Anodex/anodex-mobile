package dev.anodex.mobile.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * A way to dictate into the composer, or null where the phone cannot.
 *
 * Hands off to the phone's own speech screen — `RecognizerIntent`, the same one a
 * keyboard's mic key opens — rather than recording anything here. That choice is the
 * whole design:
 *
 * - **No microphone permission.** Anodex never holds audio. The system screen asks
 *   for and owns the microphone, and hands back text.
 * - **The phone's recogniser, in the phone's language**, with whatever on-device or
 *   offline model the user already set up, rather than a second one to configure.
 * - **Text, not a message.** What comes back lands in the field to be read and
 *   corrected. Recognition is wrong often enough that sending it unseen would put
 *   words in somebody's mouth on a computer in another room.
 *
 * This is dictation, not a spoken conversation. Talking back and forth — the reply
 * read aloud, listening again — is a different feature with a different cost: the
 * reply comes from a local model that takes seconds to start, and the phone's own
 * voice would read it.
 *
 * Null when nothing on the phone answers the intent, so the mic is not drawn as a
 * button that does nothing.
 */
@Composable
fun rememberDictation(onText: (String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onText)

    val available = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .resolveActivity(context.packageManager) != null
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (spoken.isNotEmpty()) latest.value(spoken)
    }

    if (!available) return null

    return {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your message")
        // Resolved a moment ago, but a recogniser can be disabled between composing
        // and tapping. Nothing happens rather than a crash.
        try {
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
        }
    }
}

/**
 * The draft with dictated words added.
 *
 * Appended rather than replacing, so something typed first is kept. A space goes
 * between the two unless the draft already ends in whitespace; the recogniser returns
 * a phrase with no leading space of its own.
 */
internal fun withDictated(draft: String, spoken: String): String = when {
    spoken.isBlank() -> draft
    draft.isEmpty() -> spoken
    draft.last().isWhitespace() -> draft + spoken
    else -> "$draft $spoken"
}
