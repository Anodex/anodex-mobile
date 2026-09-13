package dev.anodex.mobile.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.anodex.mobile.ui.theme.AnodexTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One picture, filling the screen.
 *
 * The transcript draws attachments at 200dp, which is enough to know *which* photo
 * was sent and not enough to read a screenshot — and a screenshot is most of what
 * gets sent from a phone. Tapping the tile had no effect at all, which reads as a
 * broken control rather than a missing one.
 *
 * Decoded at a coarser sample than the tile but still not at full size: a 12
 * megapixel photo is tens of megabytes of bitmap, and the screen is a fraction of
 * that in pixels. Zooming past this point is not worth an OutOfMemoryError on a
 * thread nobody can catch it from.
 */
@Composable
fun ImageViewer(localUri: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(localUri) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(localUri) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(localUri))?.use { input ->
                    val options = BitmapFactory.Options().apply { inSampleSize = VIEW_SAMPLE }
                    BitmapFactory.decodeStream(input, null, options)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        // Full width and height, because a picture shown inside a dialog's default
        // inset is barely larger than the tile that was tapped.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }

        val transform = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
            // Panning is only meaningful once zoomed in; at rest the image is already
            // whole, and letting it drift off-centre looks like a bug.
            if (scale > 1f) {
                offsetX += panChange.x
                offsetY += panChange.y
            } else {
                offsetX = 0f
                offsetY = 0f
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                // Tap anywhere to close. No visible button: the whole surface is the
                // control, which is what every photo viewer on the phone already does.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    // The whole surface is the control and it has no visible edge, so
                    // this is the only thing that can say what tapping does. Without
                    // it a screen reader announces a full-screen button that does not
                    // say what it is for.
                    onClickLabel = "Close",
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val image = bitmap
            if (image != null) {
                Image(
                    bitmap = image,
                    // An image with no description is skipped entirely by a screen
                    // reader, which for the only thing on this screen is worse than a
                    // generic name. This component is only handed attachments, so it
                    // can say that much truthfully without knowing what is in them.
                    contentDescription = "Attached image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .transformable(transform)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY,
                        ),
                )
            } else {
                // Either still decoding or it will not decode at all. Both are the
                // same to somebody looking at it, and neither is worth a spinner that
                // outlives the picture.
                AnodexSpinner(tint = AnodexTheme.colors.textFaint)
            }
        }
    }
}

/** Two to one, against the tile's eight. */
private const val VIEW_SAMPLE = 2

private const val MAX_ZOOM = 4f
