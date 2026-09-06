package dev.anodex.mobile.transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The wire frames, mirroring the desktop's `src/main/remote/protocol.ts`.
 *
 * These are hand-written rather than generated, and that is a known, temporary
 * exception to the rule that the phone never hand-writes a channel definition
 * (handoff §4). The rule is about *channels* — the 201 call signatures — which
 * still come from the generated artifact. The half-dozen envelope types below
 * are the protocol's own frame, and generating them would mean generating the
 * generator's own transport. If these two files ever disagree the handshake
 * fails immediately and loudly, which is the cheapest possible symptom.
 */

/** Bump together with the desktop's PROTOCOL_VERSION. Majors must match to connect. */
const val PROTOCOL_VERSION = "1.0.0"

@Serializable
sealed interface ServerFrame {

    /** Authenticated with an existing pairing. */
    @Serializable
    @SerialName("welcome")
    data class Welcome(val deviceId: String, val protocolVersion: String) : ServerFrame

    /** Pairing completed. [deviceKey] is the long-lived credential — store it, never log it. */
    @Serializable
    @SerialName("paired")
    data class Paired(
        val deviceKey: String,
        val deviceId: String,
        val protocolVersion: String,
    ) : ServerFrame

    /** A reply to one invoke. */
    @Serializable
    @SerialName("result")
    data class CallResult(
        val id: String,
        val ok: Boolean,
        val result: JsonElement? = null,
        val error: CallError? = null,
    ) : ServerFrame

    /** A pushed event: a streamed token, a tool activity, an approval request. */
    @Serializable
    @SerialName("event")
    data class Event(
        val channel: String,
        val payload: JsonElement? = null,
        val seq: Long,
    ) : ServerFrame

    /**
     * The desktop declined the connection or the frame, and said why.
     *
     * Always named. A client that is silently ignored waits forever on a reply
     * that is not coming, and the user sees an app that hangs for no reason.
     */
    @Serializable
    @SerialName("refused")
    data class Refused(val code: String, val message: String) : ServerFrame

    @Serializable
    @SerialName("pong")
    data object Pong : ServerFrame
}

@Serializable
data class CallError(val code: String, val message: String)

/**
 * Lenient on unknown keys, strict on everything else.
 *
 * `ignoreUnknownKeys` is what lets a newer desktop add a field within the same
 * protocol major without breaking an older phone — which is the whole point of
 * versioning by major. It is not laxness: an unknown *frame type* still fails.
 */
val AnodexJson: Json = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
    encodeDefaults = true
}

/** Frames the phone sends. Built by hand so the shapes stay obvious at the call site. */
object ClientFrames {

    fun hello(deviceKey: String, deviceName: String): String = buildJsonObject {
        put("type", "hello")
        put("protocolVersion", PROTOCOL_VERSION)
        put("deviceKey", deviceKey)
        put("deviceName", deviceName)
    }.toString()

    fun pair(secret: String, deviceName: String): String = buildJsonObject {
        put("type", "pair")
        put("protocolVersion", PROTOCOL_VERSION)
        put("secret", secret)
        put("deviceName", deviceName)
    }.toString()

    fun invoke(id: String, channel: String, args: List<JsonElement>): String = buildJsonObject {
        put("type", "invoke")
        put("id", id)
        put("channel", channel)
        put("args", buildJsonArray { args.forEach { add(it) } })
    }.toString()

    fun ping(): String = buildJsonObject { put("type", "ping") }.toString()
}

/**
 * Parse an inbound frame, never throwing.
 *
 * A malformed frame from the desktop is not something the phone can act on, but
 * it is also not a reason to tear down a working connection — so it becomes null
 * and the caller logs it.
 */
fun parseServerFrame(text: String): ServerFrame? =
    runCatching { AnodexJson.decodeFromString<ServerFrame>(text) }.getOrNull()

/** Major-version comparison. A mismatch is refused with a message, never a silent hang. */
fun majorOf(version: String): Int? = version.substringBefore('.').toIntOrNull()

fun versionsCompatible(a: String, b: String): Boolean {
    val left = majorOf(a) ?: return false
    val right = majorOf(b) ?: return false
    return left == right
}
