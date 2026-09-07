package dev.anodex.mobile.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
@Composable
fun AttachmentThumb(
    /** The phone's own `content://` copy. Null for a file that came from elsewhere. */
    localUri: String?,
    isImage: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val colors = AnodexTheme.colors
    val context = LocalContext.current
    var bitmap by remember(localUri) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(localUri, isImage) {
        if (!isImage || localUri == null) return@LaunchedEffect

        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(localUri))?.use { input ->
                    // Sampled down on the way in. A 12-megapixel photo decoded at full
                    // size to fill a 44dp tile is tens of megabytes of bitmap for
                    // something the size of a thumbnail.
                    val options = BitmapFactory.Options().apply { inSampleSize = THUMB_SAMPLE }
                    BitmapFactory.decodeStream(input, null, options)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(Radii.md)
            .background(colors.bgElevated),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            AnodexIcon(AnodexIcon.PAPERCLIP, size = size * 0.4f, tint = colors.textFaint)
        }
    }
}

/**
 * Eight to one.
 *
 * A phone photo is around 4000px on its long edge and the tile is under 200px even
 * on the densest screen, so this is still oversampled — and being a power of two it
 * is the cheap path through the decoder.
 */
private const val THUMB_SAMPLE = 8
