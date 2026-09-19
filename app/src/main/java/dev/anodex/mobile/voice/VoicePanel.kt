package dev.anodex.mobile.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.anodex.mobile.ui.theme.AnodexTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Speak, with the microphone permission in front of it.
 *
 * The permission is asked here rather than inside [VoiceController] for a reason
 * that is easy to get wrong: a system prompt raised from a controller appears over
 * whatever screen happens to be showing, including none. Asked from the screen that
 * wants it, the sequence a person sees is the one they started — they tapped Speak,
 * so they are asked about the microphone.
 *
 * Nothing is asked until then. `RECORD_AUDIO` is declared in the manifest because
 * Android requires it declared, and requested at the moment somebody turns voice on,
 * which is the only moment it means anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePanel(controller: VoiceController, onClose: () -> Unit) {
    val context = LocalContext.current
    val state by controller.state.collectAsStateWithLifecycle()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // A refusal is not an error to report. The screen stays as it was, saying
        // Speak, and nothing starts — which is the honest picture of what happened.
        if (granted) controller.start()
    }

    // Leaving the screen closes the microphone, whether by the button, by back, or
    // by the connection dropping underneath it. A voice session that outlives the
    // screen is a microphone nobody can see is open.
    DisposableEffect(Unit) {
        onDispose { controller.stop() }
    }

    var showingVoiceSheet by remember { mutableStateOf(false) }

    if (showingVoiceSheet) {
        ModalBottomSheet(
            onDismissRequest = { showingVoiceSheet = false },
            containerColor = AnodexTheme.colors.bgElevated,
        ) {
            VoiceSheet(onDismiss = { showingVoiceSheet = false })
        }
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        VoiceScreen(
            onVoice = { showingVoiceSheet = true },
            state = state,
            onStart = {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED

                if (granted) controller.start() else launcher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onStop = controller::stop,
            onClose = {
                controller.stop()
                onClose()
            },
        )
    }
}
