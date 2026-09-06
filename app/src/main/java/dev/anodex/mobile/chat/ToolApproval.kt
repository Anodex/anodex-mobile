package dev.anodex.mobile.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A tool call waiting for a human answer.
 *
 * The highest-value thing a phone can do. Everything else the app offers can wait
 * until you are back at the computer; a run blocked on approval cannot — it is
 * stopped until somebody answers, and answering from wherever you are is the
 * whole reason to carry this.
 *
 * Fields are those of the desktop's `ToolConfirmRequest`, read from the generated
 * contract. The phone deliberately renders a subset: no diff view and no email
 * draft yet, and the card says so rather than pretending the summary is the whole
 * story.
 */
data class ToolApproval(
    val id: String,
    val conversationId: String,
    val toolName: String,
    val title: String,
    val detail: String?,
    val risk: Risk,
    /** True when this is the turn's own once-per-turn checkpoint. */
    val turnGate: Boolean,
    /** True when the request carries a diff or draft this screen does not render. */
    val hasUnshownDetail: Boolean,
) {
    enum class Risk { SAFE, SENSITIVE, DESTRUCTIVE }

    companion object {
        fun from(payload: JsonObject): ToolApproval? {
            val id = payload.str("id") ?: return null
            return ToolApproval(
                id = id,
                conversationId = payload.str("conversationId") ?: "",
                toolName = payload.str("toolName") ?: "a tool",
                title = payload.str("title") ?: payload.str("toolName") ?: "Run a tool?",
                detail = payload.str("detail"),
                risk = when (payload.str("risk")) {
                    "destructive" -> Risk.DESTRUCTIVE
                    "sensitive" -> Risk.SENSITIVE
                    else -> Risk.SAFE
                },
                turnGate = payload.str("turnGate") == "true",
                // A diff or an email draft is the part a careful person would want to
                // read before approving. Saying it exists and is not shown here is
                // honest; rendering the summary alone and staying quiet is not.
                hasUnshownDetail = payload["diff"] != null || payload["emailDraft"] != null,
            )
        }

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.let { if (it.isString || it.content != "null") it.content else null }
    }
}

/** Parse a `tools:confirm-request` event payload. */
fun parseToolApproval(payload: kotlinx.serialization.json.JsonElement?): ToolApproval? =
    runCatching { ToolApproval.from(payload?.jsonObject ?: return null) }.getOrNull()
