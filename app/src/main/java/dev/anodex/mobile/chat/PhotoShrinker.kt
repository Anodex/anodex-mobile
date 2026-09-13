package dev.anodex.mobile.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A photo made small enough to send quickly, before it is sent.
 *
 * A phone camera writes four to twelve megabytes a shot. Sent as it is, that is a
 * long wait on mobile data away from home — the case this app is for — and the model
 * on the computer scales it down to well under this size before it looks anyway.
 * ChatGPT and Claude both shrink a photo on the phone first; so does this.
 *
 * Only photos. A screenshot is a PNG of text, small already, and re-encoding it as
 * JPEG is exactly how text gets blurred, so it goes as it is unless it is huge.
 */
class PhotoShrinker(private val context: Context) {

    /**
     * The picture at [uri], smaller if it needed to be, as a URI this app can read.
     *
     * Returns [uri] unchanged for anything that is not a picture worth shrinking, or
     * when shrinking fails — a photo sent at full size is better than no photo.
     */
    suspend fun shrinkIfLarge(uri: Uri): Uri = withContext(Dispatchers.IO) {
        runCatching { shrink(uri) }.getOrNull() ?: uri
    }

    private fun shrink(uri: Uri): Uri? {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)?.lowercase()
        val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        val type = mime ?: bounds.outMimeType?.lowercase()

        if (!shouldShrink(type, size, width, height)) return null

        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        val sample = sampleToFit(maxOf(width, height), MAX_EDGE)
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val scaled = scaleToFit(decoded, MAX_EDGE)
        val upright = rotated(scaled, orientation)

        val dir = File(context.cacheDir, "uploads").apply { mkdirs() }
        val name = uploadName(resolver.queryName(uri))
        val out = File(dir, name)
        out.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        if (upright !== decoded) decoded.recycle()
        upright.recycle()

        // A shrink that did not shrink is not worth the re-encode's quality loss.
        if (size in 1..out.length()) {
            out.delete()
            return null
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.updates", out)
    }

    private fun android.content.ContentResolver.queryName(uri: Uri): String? =
        runCatching {
            query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

    private fun scaleToFit(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    /**
     * Applied to the pixels, because the orientation tag does not survive re-encoding:
     * a portrait photo would otherwise arrive on its side.
     */
    private fun rotated(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    companion object {
        /** Long edge after shrinking. Past what a vision model reads, short of a camera's. */
        const val MAX_EDGE = 2048

        const val JPEG_QUALITY = 88

        /** A photo under this, already within [MAX_EDGE], goes as it is. */
        const val SMALL_PHOTO_BYTES = 1_000_000L

        /** A PNG over this is re-encoded after all; below it, its text stays crisp. */
        const val HUGE_PNG_BYTES = 4_000_000L

        /** Whether a picture of this type, size and shape is worth making smaller. */
        internal fun shouldShrink(mime: String?, sizeBytes: Long, width: Int, height: Int): Boolean {
            if (width <= 0 || height <= 0) return false
            val oversized = maxOf(width, height) > MAX_EDGE
            return when (mime) {
                "image/jpeg", "image/jpg" -> oversized || sizeBytes > SMALL_PHOTO_BYTES
                "image/png" -> sizeBytes > HUGE_PNG_BYTES
                else -> false
            }
        }

        /** The largest power-of-two sample that keeps [longEdge] at or above [target]. */
        internal fun sampleToFit(longEdge: Int, target: Int): Int {
            var sample = 1
            while (longEdge / (sample * 2) >= target) sample *= 2
            return sample
        }

        /** The name the computer sees: the original's, as a JPEG. */
        internal fun uploadName(original: String?): String {
            val base = original?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                ?: "photo-${System.currentTimeMillis()}"
            val safe = base.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80)
            return "$safe.jpg"
        }
    }
}
