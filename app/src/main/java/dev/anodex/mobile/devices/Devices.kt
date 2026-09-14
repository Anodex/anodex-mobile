package dev.anodex.mobile.devices

import dev.anodex.mobile.transport.AnodexSocket
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A device paired with the computer, as the computer describes it to this phone. */
data class PairedDeviceInfo(
    val deviceId: String,
    val name: String,
    val pairedAtEpochMs: Long,
    val lastSeenEpochMs: Long,
    /** This phone. Unpairing it disconnects the app you are using. */
    val isThisDevice: Boolean,
    /**
     * Connected to the computer right now. False from a computer too old to say, where
     * only this phone is known to be connected.
     */
    val connected: Boolean = false,
    /** How it reaches the computer while connected: "home", "vpn" or "internet". */
    val route: String? = null,
) {
    /** "Connected now", with how, when the computer says. */
    val connectedLabel: String
        get() = "Connected now" + when (route) {
            "home" -> " · home network"
            "vpn" -> " · over VPN"
            "internet" -> " · from outside"
            else -> ""
        }
}

/**
 * The devices paired with the computer: list, rename, unpair.
 *
 * Adding one is not here, and cannot be — a new device pairs with a code shown on the
 * computer's own screen. Everything returns the list as it stands afterwards.
 */
class Devices(private val socket: AnodexSocket) {

    suspend fun list(): List<PairedDeviceInfo> = parsePairedDevices(socket.invoke(CHANNEL_LIST))

    suspend fun rename(deviceId: String, name: String): List<PairedDeviceInfo> =
        parsePairedDevices(socket.invoke(CHANNEL_RENAME, listOf(JsonPrimitive(deviceId), JsonPrimitive(name))))

    suspend fun unpair(deviceId: String): List<PairedDeviceInfo> =
        parsePairedDevices(socket.invoke(CHANNEL_UNPAIR, listOf(JsonPrimitive(deviceId))))

    private companion object {
        const val CHANNEL_LIST = "devices:list"
        const val CHANNEL_RENAME = "devices:rename"
        const val CHANNEL_UNPAIR = "devices:unpair"
    }
}

/** The computer's device list, most recently seen first as it sends it; skips anything malformed. */
internal fun parsePairedDevices(element: JsonElement?): List<PairedDeviceInfo> {
    val rows = (element as? JsonArray)
        ?: ((element as? JsonObject)?.get("value") as? JsonArray)
        ?: return emptyList()
    return rows.filterIsInstance<JsonObject>().mapNotNull { row ->
        fun text(key: String) = (row[key] as? JsonPrimitive)?.contentOrNull
        val id = text("deviceId") ?: return@mapNotNull null
        PairedDeviceInfo(
            deviceId = id,
            name = text("name") ?: "Device",
            pairedAtEpochMs = text("pairedAtEpochMs")?.toDoubleOrNull()?.toLong() ?: 0L,
            lastSeenEpochMs = text("lastSeenEpochMs")?.toDoubleOrNull()?.toLong() ?: 0L,
            isThisDevice = row["isThisDevice"]?.jsonPrimitive?.contentOrNull == "true",
            connected = row["connected"]?.jsonPrimitive?.contentOrNull == "true",
            route = text("route"),
        )
    }
}
