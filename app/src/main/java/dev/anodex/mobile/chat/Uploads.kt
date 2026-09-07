package dev.anodex.mobile.chat

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import dev.anodex.mobile.transport.AnodexSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A file the user picked, before it has gone anywhere. */
data class PickedFile(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
)

/**
 * A file that made it to the computer, in the shape a turn refers to it by.
 *
 * The path is the computer's, not the phone's. That is the whole point: the bytes
 * live there now, and the message carries a reference rather than a copy.
 */
data class UploadedFile(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val isImage: Boolean,
)

/** Where an upload has got to. */
sealed interface UploadState {
    data class Sending(val file: PickedFile, val fraction: Float) : UploadState
    data class Done(val file: PickedFile, val uploaded: UploadedFile) : UploadState
    data class Failed(val file: PickedFile, val message: String) : UploadState
}

/**
 * Sending a file to the computer, in pieces.
 *
 * Chunked because a frame is capped at 256KB and a file worth attaching is bigger
 * than that. Base64 over the existing socket rather than a second connection: the
 * only thing a separate port would buy is avoiding head-of-line blocking, which
 * chunk size already bounds, and it would cost a second hand-configured port
 * forward on a router that does not open them by itself.
 *
 * The computer decides what it will take. This asks first with a name and a size,
 * so a file that was never going to be accepted costs one round trip rather than a
 * whole transfer up a phone's uplink.
 */
class Uploads(
    private val socket: AnodexSocket,
    private val resolver: ContentResolver,
) {

    /**
     * Read what the picker gave us.
     *
     * A content URI is not a file: it has no path a caller can read, and its name and
     * size have to be asked for. Returns null when the provider will not say how big
     * it is, because an upload has to declare its size before it starts.
     */
    suspend fun describe(uri: Uri): PickedFile? = withContext(Dispatchers.IO) {
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null

                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex < 0 || cursor.isNull(sizeIndex)) return@use null

                PickedFile(
                    uri = uri,
                    name = if (nameIndex >= 0) cursor.getString(nameIndex) else "attachment",
                    sizeBytes = cursor.getLong(sizeIndex),
                )
            }
        }.getOrNull()
    }

    /**
     * Send it, reporting progress as it goes.
     *
     * Read and sent a chunk at a time. Loading a 20MB file into memory to base64 the
     * whole thing would be the phone's version of the mistake the desktop side is
     * careful to avoid.
     */
    suspend fun send(
        file: PickedFile,
        onProgress: (Float) -> Unit,
    ): Result<UploadedFile> = withContext(Dispatchers.IO) {
        runCatching {
            val id = begin(file) ?: error("Your computer would not take that file.")

            try {
                resolver.openInputStream(file.uri)?.use { input ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    var sent = 0L

                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break

                        val encoded = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                        socket.invoke(
                            CHANNEL_CHUNK,
                            listOf(
                                buildJsonObject {
                                    put("id", JsonPrimitive(id))
                                    put("data", JsonPrimitive(encoded))
                                },
                            ),
                        )

                        sent += read
                        if (file.sizeBytes > 0) {
                            onProgress((sent.toFloat() / file.sizeBytes).coerceIn(0f, 1f))
                        }
                    }
                } ?: error("That file could not be read.")

                complete(id, file) ?: error("Your computer could not save that file.")
            } catch (e: Exception) {
                // Let the computer release the slot and the bytes rather than leaving
                // a half file sitting there until its idle sweep notices.
                runCatching {
                    socket.invoke(
                        CHANNEL_ABORT,
                        listOf(buildJsonObject { put("id", JsonPrimitive(id)) }),
                    )
                }
                throw e
            }
        }
    }

    /** Tell the computer to forget a file whose message was never sent. */
    suspend fun discard(path: String) {
        runCatching { socket.invoke(CHANNEL_DISCARD, listOf(JsonPrimitive(path))) }
    }

    private suspend fun begin(file: PickedFile): String? {
        val answer = socket.invoke(
            CHANNEL_BEGIN,
            listOf(
                buildJsonObject {
                    put("name", JsonPrimitive(file.name))
                    put("sizeBytes", JsonPrimitive(file.sizeBytes))
                },
            ),
        )
        return (answer as? JsonObject)
            ?.let { it["value"] as? JsonObject }
            ?.get("id")
            ?.jsonPrimitive
            ?.contentOrNull
    }

    private suspend fun complete(id: String, file: PickedFile): UploadedFile? {
        val answer = socket.invoke(
            CHANNEL_COMPLETE,
            listOf(buildJsonObject { put("id", JsonPrimitive(id)) }),
        )
        val value = (answer as? JsonObject)?.get("value") as? JsonObject ?: return null
        val path = value["path"]?.jsonPrimitive?.contentOrNull ?: return null

        return UploadedFile(
            path = path,
            name = value["name"]?.jsonPrimitive?.contentOrNull ?: file.name,
            sizeBytes = file.sizeBytes,
            isImage = value["kind"]?.jsonPrimitive?.contentOrNull == "image",
        )
    }

    private companion object {
        const val CHANNEL_BEGIN = "attachments:begin-upload"
        const val CHANNEL_CHUNK = "attachments:upload-chunk"
        const val CHANNEL_COMPLETE = "attachments:complete-upload"
        const val CHANNEL_ABORT = "attachments:abort-upload"
        const val CHANNEL_DISCARD = "attachments:discard-upload"

        /**
         * 64KB of file, which is about 87KB once base64'd — comfortably inside the
         * 256KB frame cap, and small enough that a chunk in flight delays a control
         * frame behind it by milliseconds rather than by anything a person notices.
         */
        const val CHUNK_BYTES = 64 * 1024
    }
}
