package dev.anodex.mobile.settings

import dev.anodex.mobile.transport.AnodexSocket
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * How much Anodex is allowed to do before it asks.
 *
 * The desktop calls this the permission mode and keeps it at
 * `settings.general.permissionMode`. It is the most consequential setting in the
 * app -- it decides whether a file edit or a shell command runs on the owner's
 * computer with or without a yes in between -- and until now the phone could
 * neither read it nor change it.
 *
 * Not for want of permission. `settings:get` and `settings:update` are both open
 * to a paired phone; several comments in this repo said otherwise and were out of
 * date. See `channelPolicy.ts`, where the only refusals are editing the
 * connection, the terminal, and critical thinking.
 */
enum class PermissionMode(
    /** What the desktop stores. Do not rename: this crosses the wire. */
    val wire: String,
    val label: String,
    val detail: String,
) {
    ASK(
        wire = "ask",
        label = "Ask every time",
        detail = "Nothing is written or run without a yes from you",
    ),

    /**
     * Stored as `full`, shown as "Edits".
     *
     * The wire name is the older one and the desktop's own label has moved on to
     * "Edits: allow file edits and checks, ask commands". Matching the label
     * rather than the value would be a rename of a stored setting, which is not
     * something to do from a phone screen.
     */
    EDITS(
        wire = "full",
        label = "Edits",
        detail = "File edits and checks run on their own; commands still ask",
    ),

    UNTETHERED(
        wire = "untethered",
        label = "Untethered",
        detail = "Everything but destructive actions runs without asking",
    );

    companion object {
        fun fromWire(value: String?): PermissionMode? = entries.find { it.wire == value }
    }
}

/**
 * Reads and writes the parts of the computer's settings this phone has a screen for.
 *
 * Deliberately not a whole-settings object. `settings:get` answers with the entire
 * blob -- every provider key path, every MCP server, the model directory -- and a
 * phone that parses all of it acquires a second definition of the settings shape to
 * keep in step with the desktop's by hand. Reading only the field a screen shows
 * keeps that from starting.
 */
class AgentSettings(private val socket: AnodexSocket) {

    /**
     * The permission mode as the computer currently has it, or null if it could
     * not be read.
     *
     * Null is not "ask". A screen that cannot reach the computer must not draw a
     * tick beside the safest option, because that is the one claim somebody would
     * act on without checking.
     */
    suspend fun permissionMode(): PermissionMode? = modeOf(socket.invoke(CHANNEL_GET))

    /**
     * Sets the permission mode, and answers with what the computer ended up with.
     *
     * The answer is the computer's rather than an echo of the request:
     * `settings:update` returns the settings after the change, so a value the
     * desktop rejected or corrected comes back as what it really is. A screen that
     * assumed its own request took effect would show a mode that is not in force,
     * which on this setting is the difference between "it will ask" and "it will
     * not".
     */
    suspend fun setPermissionMode(mode: PermissionMode): PermissionMode? {
        val patch: JsonElement = buildJsonObject {
            put("general", buildJsonObject { put("permissionMode", JsonPrimitive(mode.wire)) })
        }
        return modeOf(socket.invoke(CHANNEL_UPDATE, listOf(patch)))
    }

    /**
     * Both channels answer with the whole `AppSettings`, and neither is wrapped.
     *
     * `protocol/anodex-protocol.json` records both results as `{"$ref":
     * "AppSettings"}` rather than the Result union most channels use. Calling
     * `unwrap()` out of habit finds no `ok: true`, returns null, and the screen
     * draws nothing selected with no error, because nothing failed --
     * `settings:get-profile` shipped with exactly that bug.
     */
    private fun modeOf(answer: JsonElement?): PermissionMode? {
        val general = (answer as? JsonObject)?.get("general") as? JsonObject ?: return null
        return PermissionMode.fromWire(general["permissionMode"]?.jsonPrimitive?.contentOrNull)
    }

    private companion object {
        const val CHANNEL_GET = "settings:get"
        const val CHANNEL_UPDATE = "settings:update"
    }
}
