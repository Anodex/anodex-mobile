package dev.anodex.mobile.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anodex.mobile.ui.theme.AnodexTheme

/**
 * Speak, which arrives already listening.
 *
 * The key in the composer is marked Speak and that is the whole intention. A screen
 * that then asks somebody to press Speak again is two taps for one decision, and the
 * second is a toll rather than a choice. So the microphone opens as the screen does,
 * and the control at the bottom is Stop.
 *
 * The permission is asked here rather than inside [VoiceController] for a reason
 * that is easy to get wrong: a system prompt raised from a controller appears over
 * whatever screen happens to be showing, including none. Asked as this screen opens,
 * the sequence somebody sees is the one they started — they tapped Speak, so they
 * are asked about the microphone.
 *
 * Nothing is asked before then. `RECORD_AUDIO` is declared in the manifest because
 * Android requires it declared, and requested at the moment somebody chooses to
 * talk, which is the only moment it means anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePanel(controller: VoiceController, onClose: () -> Unit) {
    val context = LocalContext.current
    val state by controller.state.collectAsStateWithLifecycle()

    var showingVoiceSheet by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refused = !granted
        if (granted) controller.start()
    }

    fun granted(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    // Listening begins with the screen. Keyed to nothing, so it runs once on the way
    // in and not again when the state it starts changes underneath it.
    LaunchedEffect(Unit) {
        if (granted()) controller.start() else launcher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Leaving closes the microphone, whether by the button, by back, or by the
    // connection dropping underneath it. A voice session that outlives its screen is
    // a microphone nobody can see is open.
    DisposableEffect(Unit) {
        onDispose { controller.stop() }
    }

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
            state = state.copy(microphoneRefused = refused),
            onVoice = { showingVoiceSheet = true },
            // Only reached after Stop, since the screen starts listening. Asking again
            // is right there: somebody who stopped and changed their mind has made a
            // new decision, and Android answers from what it already remembers.
            onStart = {
                if (granted()) {
                    refused = false
                    controller.start()
                } else {
                    launcher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onStop = controller::stop,
            onClose = {
                controller.stop()
                onClose()
            },
        )
    }
}
