package dev.anodex.mobile.chat

import dev.anodex.mobile.transport.AnodexSocket
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A model file sitting on the computer, ready to be loaded. */
data class LocalModel(
    /** The absolute path on the computer. Its identity, and what a load is asked by. */
    val path: String,
    val name: String,
    /** Size on disk. 0 when the computer did not say. */
    val bytes: Long,
    /**
     * `Q4_K_M` and friends, when the filename carried one.
     *
     * Shown because the same model is often present at two quantisations, and the
     * quant is the only thing telling them apart — without it the picker offers two
     * rows with identical names.
     */
    val quant: String = "",
)

/**
 * Choosing which model the computer runs.
 *
 * Only choosing. A phone can list what is already on the machine and load one of
 * them; it cannot download, add, or delete — those write gigabytes to somebody
 * else's disk from a search of the open internet, or destroy a file for good, and
 * they stay at the desk. `channelPolicy.ts` is where that line is actually enforced
 * and `modelSwitching.test.ts` is where it is pinned.
 *
 * A load takes minutes and moves the machine out from under anyone sitting at it.
 * That is a real cost and it is the same one the desk pays: this is not something
 * the person holding the phone could not do by walking over.
 */
class Models(private val socket: AnodexSocket) {

    suspend fun list(): List<LocalModel> = parseModels(socket.invoke(CHANNEL_LIST))

    /**
     * Load one, by path.
     *
     * Deliberately sends nothing but the path. Context size and GPU layers are tuned
     * per machine and per model in the desktop's settings, which a phone cannot read
     * — so it says nothing and lets the computer fill them in, and the result is the
     * same engine the desk would have got.
     */
    suspend fun load(path: String) {
        socket.invoke(
            CHANNEL_LOAD,
            listOf(buildJsonObject { put("path", JsonPrimitive(path)) }),
            timeout = LOAD_TIMEOUT,
        )
    }

    private companion object {
        const val CHANNEL_LIST = "models:list"
        const val CHANNEL_LOAD = "models:load"

        /**
         * Loading a large model off a spinning disk genuinely takes minutes, and the
         * default 60 seconds would report a failure for something that is working.
         */
        val LOAD_TIMEOUT = kotlin.time.Duration.parse("10m")
    }
}

/**
 * The computer's answer, read defensively.
 *
 * Wrapped in the desktop's `Result` shape — `{ ok, value }` — so the list is a level
 * down. A model with no path is dropped: the path is how a load is asked for, so an
 * entry without one is a row that could not be tapped.
 */
internal fun parseModels(element: JsonElement?): List<LocalModel> {
    val root = element as? JsonObject ?: return emptyList()
    val array = (root["value"] as? JsonArray) ?: (element as? JsonArray) ?: return emptyList()

    return array.filterIsInstance<JsonObject>().mapNotNull { entry ->
        val path = entry["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        LocalModel(
            path = path,
            // Falls back to the filename rather than being dropped: a model the
            // computer could not name is still one the user can recognise and load.
            name = entry["name"]?.jsonPrimitive?.contentOrNull
                ?: path.substringAfterLast('\\').substringAfterLast('/'),
            bytes = entry["sizeBytes"]?.jsonPrimitive?.longOrNull ?: 0L,
            quant = entry["quant"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }
}

/** "Q4_K_M · 8.2 GB", dropping either half the computer did not give. */
fun LocalModel.detailLabel(): String =
    listOf(quant, sizeLabel()).filter { it.isNotBlank() }.joinToString(" · ")

/** "8.2 GB". Blank when the computer did not say, rather than "0 B". */
fun LocalModel.sizeLabel(): String {
    if (bytes <= 0) return ""
    val gb = bytes.toDouble() / (1024.0 * 1024 * 1024)
    return if (gb >= 1) String.format("%.1f GB", gb)
    else String.format("%.0f MB", bytes.toDouble() / (1024.0 * 1024))
}
