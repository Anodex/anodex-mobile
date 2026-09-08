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
    /** True when the request carries something this screen still does not render. */
    val hasUnshownDetail: Boolean,
    /**
     * The edit being approved, when the request is a file write.
     *
     * Present so the prompt can show the change rather than describe it. Approving
     * an edit you have not seen is the one thing this screen should never ask for,
     * and until now it asked for it every time.
     */
    val diff: FileDiff? = null,
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
                diff = (payload["diff"] as? JsonObject)?.let { block ->
                    val path = block.str("path") ?: return@let null
                    FileDiff(
                        path = path,
                        before = block.str("before").orEmpty(),
                        after = block.str("after").orEmpty(),
                    )
                },
                // An email draft is still summarised rather than shown. Saying so is
                // honest; rendering the summary alone and staying quiet is not.
                hasUnshownDetail = payload["emailDraft"] != null,
            )
        }

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.let { if (it.isString || it.content != "null") it.content else null }
    }
}

/** The two versions of a file, for the prompt to show the change between them. */
data class FileDiff(val path: String, val before: String, val after: String)

/** One line of a rendered diff. */
data class DiffLine(val text: String, val kind: Kind) {
    enum class Kind { KEPT, ADDED, REMOVED }
}

/**
 * The change between two versions of a file, as lines.
 *
 * A proper longest-common-subsequence diff, bounded by [MAX_DIFF_LINES] on each
 * side: the algorithm is quadratic, and a phone asked to diff two ten-thousand-line
 * files would stop answering. Past that bound it reports the sizes instead of
 * pretending to have compared them, which is the honest failure.
 *
 * Unchanged lines are kept only near a change. A diff that lists an entire file to
 * show four edited lines is a file listing, and the reader has to find the change
 * themselves — which is what they came here to be shown.
 */
fun diffLines(before: String, after: String): List<DiffLine> {
    val old = before.lines()
    val new = after.lines()

    if (old.size > MAX_DIFF_LINES || new.size > MAX_DIFF_LINES) {
        return listOf(
            DiffLine(
                "Too large to compare here — ${old.size} lines before, ${new.size} after.",
                DiffLine.Kind.KEPT,
            )
        )
    }

    // Standard LCS table, then walked backwards into a script.
    val lcs = Array(old.size + 1) { IntArray(new.size + 1) }
    for (i in old.indices.reversed()) {
        for (j in new.indices.reversed()) {
            lcs[i][j] = if (old[i] == new[j]) {
                lcs[i + 1][j + 1] + 1
            } else {
                maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
    }

    val script = mutableListOf<DiffLine>()
    var i = 0
    var j = 0
    while (i < old.size && j < new.size) {
        when {
            old[i] == new[j] -> {
                script += DiffLine(old[i], DiffLine.Kind.KEPT); i++; j++
            }
            lcs[i + 1][j] >= lcs[i][j + 1] -> {
                script += DiffLine(old[i], DiffLine.Kind.REMOVED); i++
            }
            else -> {
                script += DiffLine(new[j], DiffLine.Kind.ADDED); j++
            }
        }
    }
    while (i < old.size) script += DiffLine(old[i++], DiffLine.Kind.REMOVED)
    while (j < new.size) script += DiffLine(new[j++], DiffLine.Kind.ADDED)

    return withContextOnly(script)
}

/** Keeps unchanged lines only where they sit beside a change. */
private fun withContextOnly(script: List<DiffLine>): List<DiffLine> {
    val keep = BooleanArray(script.size)
    for ((index, line) in script.withIndex()) {
        if (line.kind == DiffLine.Kind.KEPT) continue
        for (near in (index - CONTEXT_LINES)..(index + CONTEXT_LINES)) {
            if (near in script.indices) keep[near] = true
        }
    }

    val out = mutableListOf<DiffLine>()
    var skipping = false
    for ((index, line) in script.withIndex()) {
        if (keep[index]) {
            skipping = false
            out += line
        } else if (!skipping) {
            skipping = true
            out += DiffLine("⋯", DiffLine.Kind.KEPT)
        }
    }
    return out
}

/** Two lines either side of a change: enough to place it, not enough to bury it. */
private const val CONTEXT_LINES = 2

/**
 * Where comparing stops being worth it.
 *
 * The table is `old × new` integers, so ten thousand lines a side is a hundred
 * million cells — minutes of work and a heap the app does not have.
 */
private const val MAX_DIFF_LINES = 4_000

/** Parse a `tools:confirm-request` event payload. */
fun parseToolApproval(payload: kotlinx.serialization.json.JsonElement?): ToolApproval? =
    runCatching { ToolApproval.from(payload?.jsonObject ?: return null) }.getOrNull()
