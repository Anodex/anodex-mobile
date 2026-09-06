package dev.anodex.mobile.pairing

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Reads a QR code out of the camera preview.
 *
 * Decodes ZXing's luminance plane directly rather than pulling in a barcode
 * service: the Y plane of a YUV frame *is* a greyscale image, which is exactly
 * what a QR decoder wants, so the conversion is a crop and nothing else. It also
 * keeps the app free of Play Services, which matters for something distributed as
 * a sideloaded APK.
 *
 * The analyzer only reports strings. Deciding whether a scanned string is a valid
 * Anodex pairing code belongs to [parsePairingPayload], which treats it as the
 * hostile input it is.
 */
class QrAnalyzer(private val onDecoded: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                // QR only. Letting it try every format wastes the frame budget on
                // barcodes a pairing screen will never see.
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    /** Set once a code has been read, so one scan cannot fire a dozen times. */
    @Volatile
    private var decoded = false

    override fun analyze(image: ImageProxy) {
        if (decoded) {
            image.close()
            return
        }

        try {
            val text = decode(image)
            if (text != null) {
                decoded = true
                onDecoded(text)
            }
        } catch (_: Exception) {
            // A frame that will not decode is the normal case, not an error — most
            // frames are the user still lining the code up.
        } finally {
            // Must close on every path, or the camera stalls after a few frames
            // waiting for a buffer that is never returned.
            image.close()
        }
    }

    private fun decode(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val source = PlanarYUVLuminanceSource(
            bytes,
            plane.rowStride,
            image.height,
            0,
            0,
            image.width,
            image.height,
            false,
        )

        // Tried the normal way up first, then inverted.
        //
        // A QR decoder finds a code by its three finder patterns and looks for *dark*
        // squares on a light field; ZXing does not try the image the other way round.
        // Anodex used to draw its pairing code pale-on-dark so it sat nicely in a dark
        // settings panel, which made it invisible to this decoder while every other QR
        // code in the world scanned fine.
        //
        // The desktop draws it the right way up now. This stays so a phone can still
        // pair with a computer that has not been updated yet — the retry costs one
        // decode attempt on a frame that had already failed.
        val upright = runCatching {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        }.getOrNull()
        if (upright != null) return upright

        return runCatching {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))).text
        }.getOrNull()
    }
}
