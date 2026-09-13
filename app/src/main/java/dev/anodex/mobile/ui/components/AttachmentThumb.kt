package dev.anodex.mobile.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The picture that was attached, rather than its filename.
 *
 * A paperclip and `20260901_100743.jpg` tells you a file went, not which one — and
 * for a screenshot, which one is the entire content of the message. The desktop
 * shows the image in its composer and in the turn afterwards; this is that.
 *
 * Falls back to the paperclip for anything that is not an image, and for an image
 * that will not decode. A tile that cannot be drawn should look like a file, not
 * like a hole.
 */
/**
 * How a transcript gets a picture that lives on the computer: a message id and the
 * picture's position on it, to JPEG bytes. Provided by the chat for the conversation it
 * shows; null where there is no conversation to ask about.
 */
val LocalRemotePictures = staticCompositionLocalOf<(suspend (messageId: String, index: Int) -> ByteArray?)?> { null }

@Composable
fun AttachmentThumb(
    /** The phone's own `content://` copy. Null for a file that came from elsewhere. */
    localUri: String?,
    isImage: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    /**
     * Show the picture in its own shape, [size] wide, rather than cropped to a square.
     *
     * For the image in a sent message, where it *is* the message: a screenshot cut to
     * the square in its middle showed a strip of text from halfway down and none of
     * what it was a screenshot of. The composer's small tile stays square.
     */
    whole: Boolean = false,
    /** For a picture read back from the computer: its message and position there. */
    remote: Pair<String, Int>? = null,
) {
    val colors = AnodexTheme.colors
    val context = LocalContext.current
    // The tile's longest edge in pixels, which is what the decode aims for.
    val targetPx = with(LocalDensity.current) { (size * if (whole) MAX_TALL else 1f).roundToPx() }
    val remotePictures = LocalRemotePictures.current
    var bitmap by remember(localUri, remote) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(localUri, isImage, targetPx, remote) {
        if (!isImage) return@LaunchedEffect
        if (localUri == null) {
            val (messageId, index) = remote ?: return@LaunchedEffect
            val bytes = remotePictures?.invoke(messageId, index) ?: return@LaunchedEffect
            bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sampleFor(maxOf(bounds.outWidth, bounds.outHeight), targetPx)
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
                }.getOrNull()
            }
            return@LaunchedEffect
        }

        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(localUri)
                // Measured first, then sampled to fit. A fixed eight-to-one made a
                // 1080x1920 screenshot 135x240 and then stretched it across a tile five
                // hundred pixels wide; a 12-megapixel photo still wants cutting down.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val sample = sampleFor(maxOf(bounds.outWidth, bounds.outHeight), targetPx)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeStream(input, null, options)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }

    val image = bitmap
    // Wide screenshots and tall ones both kept to something a message can hold; past
    // these, the picture is trimmed rather than the transcript taken over by it.
    val ratio = if (whole && image != null) {
        (image.width.toFloat() / image.height).coerceIn(1f / MAX_TALL, MAX_WIDE)
    } else {
        1f
    }

    Box(
        modifier = modifier
            .then(if (whole && image != null) Modifier.width(size).aspectRatio(ratio) else Modifier.size(size))
            .clip(Radii.md)
            .background(colors.bgElevated),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = if (whole) Modifier.width(size).aspectRatio(ratio) else Modifier.size(size),
            )
        } else {
            AnodexIcon(AnodexIcon.PAPERCLIP, size = size * 0.4f, tint = colors.textFaint)
        }
    }
}

/**
 * The largest power-of-two reduction that still leaves [longEdge] at least [targetPx].
 *
 * Powers of two are the decoder's cheap path. Never below the target, so the picture
 * is only ever scaled down on screen, never blown up.
 */
internal fun sampleFor(longEdge: Int, targetPx: Int): Int {
    if (longEdge <= 0 || targetPx <= 0) return 1
    var sample = 1
    while (longEdge / (sample * 2) >= targetPx) sample *= 2
    return sample
}

/** How tall, against its width, a whole picture may be before it is trimmed. */
private const val MAX_TALL = 1.6f

/** How wide, against its height, a whole picture may be before it is trimmed. */
private const val MAX_WIDE = 2f
