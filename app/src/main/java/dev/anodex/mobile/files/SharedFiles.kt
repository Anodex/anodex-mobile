package dev.anodex.mobile.files

import dev.anodex.mobile.email.AttachmentChunk
import dev.anodex.mobile.transport.AnodexSocket
import dev.anodex.mobile.transport.unwrapOrThrow
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A file the computer sent to this phone, waiting on its shelf to be collected. */
data class SharedFile(
    val id: String,
    val name: String,
    val size: Long,
    val mimeType: String,
    val sentAtEpochMs: Long,
)

/** What this phone advertises, and the computer too, when they can pass files this way. */
const val FILES_CAPABILITY = "files.1"

/**
 * Files the computer sent here: "send it to my phone" from a chat.
 *
 * They wait on the computer (its `phoneShares.ts`) until this phone collects them,
 * a chunk at a time as email attachments come, and are taken off once saved.
 * Only used against a computer that announced [FILES_CAPABILITY].
 */
class SharedFiles(private val socket: AnodexSocket) {
    suspend fun list(): List<SharedFile> =
        parseSharedFiles(socket.invoke(CHANNEL_LIST, emptyList()).unwrapOrThrow("list the files it sent"))

    suspend fun chunk(id: String, offset: Long): AttachmentChunk? {
        val request = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("offset", JsonPrimitive(offset))
        }
        val value = socket.invoke(CHANNEL_CHUNK, listOf(request), timeout = 120.seconds)
            .unwrapOrThrow("send that file") as? JsonObject ?: return null
        return AttachmentChunk(
            filename = value["filename"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            mimeType = value["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            size = value["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            offset = value["offset"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            base64 = value["base64"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            done = value["done"]?.jsonPrimitive?.contentOrNull == "true",
        )
    }

    suspend fun remove(id: String) {
        socket.invoke(CHANNEL_REMOVE, listOf(buildJsonObject { put("id", JsonPrimitive(id)) }))
            .unwrapOrThrow("let go of that file")
    }

    companion object {
        const val CHANNEL_LIST = "attachments:list-shared"
        const val CHANNEL_CHUNK = "attachments:shared-chunk"
        const val CHANNEL_REMOVE = "attachments:remove-shared"
    }
}

/** The shelf as the computer lists it. Anything malformed is skipped, not fatal. */
internal fun parseSharedFiles(value: JsonElement?): List<SharedFile> =
    (value as? JsonArray).orEmpty().mapNotNull { entry ->
        val fields = entry as? JsonObject ?: return@mapNotNull null
        val id = (fields["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        SharedFile(
            id = id,
            name = (fields["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: "file",
            size = (fields["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L,
            mimeType = (fields["mimeType"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            sentAtEpochMs = (fields["sentAt"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L,
        )
    }
