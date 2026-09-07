package dev.anodex.mobile.chat

/**
 * One line describing everything a turn did.
 *
 * A turn can run twenty tools, and a transcript that lists all of them buries the
 * reply that was the point. Every assistant worth measuring against collapses this
 * — "Worked for 1m 6s", "Running" — and expands on a tap.
 *
 * Grouped by what the tool *did* rather than by its name, because the names are an
 * implementation detail the reader has no reason to learn: four different file
 * readers are still "read 4 files". The verbs are chosen to be the ones somebody
 * would use describing the turn out loud.
 *
 * Returns null when there is nothing worth collapsing. A turn that ran one tool
 * should show that tool, not a summary of it.
 */
fun toolSummary(tools: List<ToolActivity>): String? {
    if (tools.size < 2) return null

    var read = 0
    var edited = 0
    var searched = 0
    var ran = 0
    var other = 0

    for (tool in tools) {
        when (bucketOf(tool.name)) {
            Bucket.READ -> read++
            Bucket.EDIT -> edited++
            Bucket.SEARCH -> searched++
            Bucket.RUN -> ran++
            Bucket.OTHER -> other++
        }
    }

    val parts = buildList {
        if (read > 0) add(count(read, "file", "files", verb = "read"))
        if (edited > 0) add("edited $edited")
        if (searched > 0) add(count(searched, "search", "searches"))
        if (ran > 0) add(count(ran, "command", "commands", verb = "ran"))
        if (other > 0) add(count(other, "step", "steps"))
    }

    return parts.joinToString(", ").replaceFirstChar { it.uppercase() }
}

/**
 * "Read 3 files", "2 searches", "ran 1 command".
 *
 * Both forms are given rather than derived. Adding an "s" is right for "file" and
 * wrong for "search", and a summary that says "2 searchs" undermines every other
 * number on the line.
 */
private fun count(n: Int, singular: String, plural: String, verb: String? = null): String {
    val noun = if (n == 1) singular else plural
    return if (verb != null) "$verb $n $noun" else "$n $noun"
}

private enum class Bucket { READ, EDIT, SEARCH, RUN, OTHER }

/**
 * What a tool name amounts to.
 *
 * Matched on substrings rather than an exhaustive list, because the desktop's tool
 * set changes and a summary that silently drops a newly-added tool is worse than one
 * that calls it a "step". Anything unrecognised is counted, never ignored — the
 * count has to add up to what the expanded list shows, or the line is a lie.
 */
private fun bucketOf(name: String): Bucket {
    val n = name.lowercase()
    return when {
        // Checked before READ: `read_file` and `list_files` both contain "file", and
        // a listing is not a read.
        "search" in n || "grep" in n || "find" in n || "list" in n -> Bucket.SEARCH
        "read" in n || "cat" in n || "open" in n -> Bucket.READ
        "write" in n || "patch" in n || "edit" in n || "replace" in n || "append" in n -> Bucket.EDIT
        "run" in n || "exec" in n || "shell" in n || "command" in n || "terminal" in n -> Bucket.RUN
        else -> Bucket.OTHER
    }
}
