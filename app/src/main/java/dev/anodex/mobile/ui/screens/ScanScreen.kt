package dev.anodex.mobile.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.anodex.mobile.pairing.PairingParseError
import dev.anodex.mobile.pairing.PairingParseException
import dev.anodex.mobile.pairing.PairingPayload
import dev.anodex.mobile.pairing.QrAnalyzer
import dev.anodex.mobile.pairing.parsePairingPayload
import dev.anodex.mobile.ui.components.PrimaryButton
import dev.anodex.mobile.ui.components.SecondaryButton
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import java.util.concurrent.Executors

/**
 * Scan the pairing code shown on the computer.
 *
 * The camera permission is requested here, at the moment it is obviously needed,
 * rather than at launch. This is the app's only permission prompt, and asking for
 * it while the user is looking at a screen that says "point this at your computer"
 * is the difference between an obvious request and an alarming one.
 *
 * A decoded string is not trusted. It goes through [parsePairingPayload], which
 * refuses anything malformed, expired, or not an Anodex code, and each refusal is
 * shown as something specific rather than a generic failure.
 */
@Composable
fun ScanScreen(
    onScanned: (PairingPayload) -> Unit,
    onCancel: () -> Unit,
    pairingError: String?,
    modifier: Modifier = Modifier,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    val context = LocalContext.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var scanError by remember { mutableStateOf<String?>(null) }

    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed -> granted = allowed }

    LaunchedEffect(Unit) {
        if (!granted) request.launch(Manifest.permission.CAMERA)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgApp)
            .safeDrawingPadding()
            .padding(Spacing.x5),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.x4),
    ) {
        Text(
            text = "Scan the code on your computer",
            style = type.title,
            color = colors.text,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Anodex → Settings → Remote → Pair a phone.",
            style = type.body,
            color = colors.textMuted,
            textAlign = TextAlign.Center,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(Radii.lg)
                .background(colors.bgSurface)
                .border(1.dp, colors.border, Radii.lg),
            contentAlignment = Alignment.Center,
        ) {
            if (granted) {
                CameraPreview(
                    onDecoded = { text ->
                        val result = parsePairingPayload(text, System.currentTimeMillis() / 1000)
                        result.fold(
                            onSuccess = { payload ->
                                scanError = null
                                onScanned(payload)
                            },
                            onFailure = { error ->
                                scanError = describe(error)
                            },
                        )
                    },
                )
            } else {
                Text(
                    text = "Anodex needs the camera to read the pairing code. Nothing is " +
                        "recorded — frames are decoded and discarded.",
                    style = type.body,
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(Spacing.x6),
                )
            }
        }

        if (granted && scanError == null && pairingError == null) {
            // Shown from the start rather than after a timeout. Someone whose camera
            // is struggling has no way to know whether to keep trying or give up, and
            // the typed code works identically — it is a genuine alternative, not a
            // consolation prize.
            Text(
                text = "Holding still and filling the frame helps — this code is denser " +
                    "than most. If it will not read, cancel and enter the code by hand.",
                style = type.meta,
                color = colors.textFaint,
                textAlign = TextAlign.Center,
            )
        }

        val message = pairingError ?: scanError
        if (message != null) {
            Text(
                text = message,
                style = type.body,
                color = colors.danger,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radii.md)
                    .background(colors.dangerSoft)
                    .padding(Spacing.x4),
            )
        }

        if (!granted) {
            PrimaryButton(
                label = "Allow the camera",
                onClick = { request.launch(Manifest.permission.CAMERA) },
            )
        }
        SecondaryButton(label = "Cancel", onClick = onCancel)
    }
}

@Composable
private fun CameraPreview(onDecoded: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)

            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(view.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    // Only the newest frame matters: a queue of stale frames adds
                    // latency to a decode the user is waiting on.
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // CameraX defaults image analysis to 640x480, and that is not
                    // enough for *this* code. A pairing URI is around 190 characters,
                    // which QR encodes as a 61x61 module grid — at 480 lines, with the
                    // code filling maybe half the frame, each module lands on about
                    // four pixels, which is the very edge of what a decoder can
                    // resolve through camera blur.
                    //
                    // An everyday QR code is a short URL: 25 to 29 modules, three
                    // times the pixels per module, and it scans instantly at the same
                    // resolution. That is exactly why this one failed on a phone whose
                    // camera reads every other code fine.
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1280, 720),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                )
                            )
                            .build()
                    )
                    .build()
                    .also { it.setAnalyzer(executor, QrAnalyzer(onDecoded)) }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }, ContextCompat.getMainExecutor(ctx))

            view
        },
    )
}

/**
 * Turn a parse failure into something the user can act on.
 *
 * "Invalid code" tells someone nothing about whether to move closer, show a new
 * code, or update the app — and those are three different remedies.
 */
private fun describe(error: Throwable): String = when (val reason =
    (error as? PairingParseException)?.error) {
    is PairingParseError.NotAPairingCode ->
        "That is not an Anodex pairing code."

    is PairingParseError.Expired ->
        "That code has expired. Show a new one on the computer."

    is PairingParseError.UnsupportedVersion ->
        "That computer is running a different version of Anodex. Update both."

    is PairingParseError.Malformed ->
        "That code is damaged. Show a new one on the computer."

    null -> error.message ?: "That code could not be read."
}
