package dev.anodex.mobile.ui.screens

import dev.anodex.mobile.chat.WebSource

/**
 * Turn the model's `[S1]` markers into links to the pages behind them.
 *
 * Done as a rewrite into ordinary markdown rather than as a new kind of span,
 * because the renderer already knows how to draw and open a link, and already
 * refuses any scheme but http, https and mailto at that seam. A second path to
 * "text the phone will act on" is a second place to get that refusal wrong.
 *
 * Numbering follows the order the sources arrived, which is the order their ids
 * were minted -- so `[S2]` always renders as **2**, both here and in the list
 * under the reply. One numbering scheme, and a marker whose number does not shift
 * when the model cites the same page twice.
 *
 * A marker with no matching source is left exactly as it was written. A model
 * inventing `[S7]` when only three sources exist is the failure this has to
 * survive, and drawing it as a link to something else would be a citation that
 * points at the wrong page -- worse than a citation that does not work.
 */
fun linkCitations(text: String, sources: List<WebSource>): String {
    if (sources.isEmpty() || text.isEmpty()) return text

    val byId = HashMap<String, Pair<Int, WebSource>>(sources.size)
    sources.forEachIndexed { index, source -> byId[source.id] = (index + 1) to source }

    return MARKER.replace(text) { match ->
        val found = byId[match.groupValues[1]] ?: return@replace match.value
        val (number, source) = found
        // The url is escaped only where markdown would end the destination early.
        // These come from a search provider rather than from the model, but a
        // parenthesis in a real url is common enough to break this by accident.
        "[$number](${source.url.replace(")", "%29")})"
    }
}

/**
 * `[S1]`, and nothing looser.
 *
 * Anchored to the whole bracket and to ids that start at one: `[S0]` is not a
 * marker, and neither is `[Section 2]`. Prose that happens to contain a bracketed
 * capital S is not rare enough to guess about.
 */
private val MARKER = Regex("""\[(S[1-9]\d*)]""")
