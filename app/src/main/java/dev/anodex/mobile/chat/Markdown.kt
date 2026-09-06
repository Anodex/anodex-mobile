package dev.anodex.mobile.chat

/**
 * Just enough Markdown for what a coding agent actually replies with.
 *
 * The phone was rendering replies as plain text, which for this app is close to
 * useless: Anodex is usually answering a question about code, so a reply is mostly
 * fenced blocks, `inline code` and lists — and all the user saw was the backticks
 * and asterisks, with a shell command run together into a paragraph.
 *
 * ## Why this is hand-written
 *
 * No dependency, on purpose. A full CommonMark implementation handles reference
 * links, HTML blocks, setext headings and nested blockquotes; none of that appears
 * in these replies, and all of it is surface area. This handles the six things that
 * do appear and treats everything else as ordinary text, which is the failure mode
 * you want — an unsupported construct reads as what the model typed rather than
 * disappearing.
 *
 * ## Streaming
 *
 * Half a document is the normal case, not an error. A fence that has been opened and
 * not yet closed is a code block still arriving, and it renders as one — treating it
 * as literal text would make every reply containing code flicker from paragraph to
 * block at the moment the model happens to close it.
 */

/** A run of text with the emphasis that applies to it. */
data class Inline(
    val text: String,
    val code: Boolean = false,
    val bold: Boolean = false,
    val italic: Boolean = false,
)

sealed interface MarkdownBlock {
    data class Paragraph(val spans: List<Inline>) : MarkdownBlock

    /** A fenced block. [complete] is false while the closing fence is still to come. */
    data class CodeBlock(
        val language: String?,
        val text: String,
        val complete: Boolean = true,
    ) : MarkdownBlock

    data class Heading(val level: Int, val spans: List<Inline>) : MarkdownBlock

    /** A bullet or numbered list. [ordered] decides which marker is drawn. */
    data class ListBlock(val items: List<List<Inline>>, val ordered: Boolean) : MarkdownBlock
}

private val FENCE = Regex("^\\s{0,3}(`{3,}|~{3,})\\s*([A-Za-z0-9+#._-]*)\\s*$")
private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^\\s{0,3}[-*+]\\s+(.*)$")
private val NUMBERED = Regex("^\\s{0,3}\\d{1,9}[.)]\\s+(.*)$")

/** Split a reply into blocks. Never throws and never drops text. */
fun parseMarkdown(source: String): List<MarkdownBlock> {
    if (source.isBlank()) return emptyList()

    val blocks = mutableListOf<MarkdownBlock>()
    val lines = source.replace("\r\n", "\n").split('\n')
    var index = 0

    while (index < lines.size) {
        val line = lines[index]

        val fence = FENCE.matchEntire(line)
        if (fence != null) {
            val marker = fence.groupValues[1]
            val language = fence.groupValues[2].takeIf { it.isNotBlank() }
            val body = mutableListOf<String>()
            index++

            // Closed only by a fence of the same character and at least as long, so a
            // block that itself contains ``` in prose does not end early.
            var closed = false
            while (index < lines.size) {
                val candidate = lines[index]
                val closing = FENCE.matchEntire(candidate)
                if (closing != null &&
                    closing.groupValues[1].first() == marker.first() &&
                    closing.groupValues[1].length >= marker.length &&
                    closing.groupValues[2].isBlank()
                ) {
                    closed = true
                    index++
                    break
                }
                body += candidate
                index++
            }

            blocks += MarkdownBlock.CodeBlock(
                language = language,
                text = body.joinToString("\n"),
                complete = closed,
            )
            continue
        }

        if (line.isBlank()) {
            index++
            continue
        }

        val heading = HEADING.matchEntire(line)
        if (heading != null) {
            blocks += MarkdownBlock.Heading(
                level = heading.groupValues[1].length,
                spans = parseInline(heading.groupValues[2]),
            )
            index++
            continue
        }

        if (BULLET.matches(line) || NUMBERED.matches(line)) {
            val ordered = NUMBERED.matches(line)
            val items = mutableListOf<List<Inline>>()

            // One kind of list at a time: a bulleted list that turns numbered is two
            // lists, and rendering them as one would renumber the user's content.
            while (index < lines.size) {
                val pattern = if (ordered) NUMBERED else BULLET
                val match = pattern.matchEntire(lines[index]) ?: break
                items += parseInline(match.groupValues[1])
                index++
            }

            blocks += MarkdownBlock.ListBlock(items, ordered)
            continue
        }

        // A paragraph runs until a blank line or the start of another kind of block.
        val paragraph = mutableListOf<String>()
        while (index < lines.size) {
            val next = lines[index]
            if (next.isBlank() ||
                FENCE.matches(next) ||
                HEADING.matches(next) ||
                BULLET.matches(next) ||
                NUMBERED.matches(next)
            ) {
                break
            }
            paragraph += next.trim()
            index++
        }
        blocks += MarkdownBlock.Paragraph(parseInline(paragraph.joinToString(" ")))
    }

    return blocks
}

/**
 * Split one line into emphasised runs.
 *
 * Backticks are handled first and their contents are never scanned again, because
 * `**` inside code is an operator rather than emphasis — and an agent's reply is
 * full of code containing exactly those characters.
 */
fun parseInline(source: String): List<Inline> {
    if (source.isEmpty()) return emptyList()

    val spans = mutableListOf<Inline>()
    val plain = StringBuilder()
    var i = 0

    fun flush() {
        if (plain.isNotEmpty()) {
            spans += Inline(plain.toString())
            plain.clear()
        }
    }

    while (i < source.length) {
        // Indexed rather than substring-per-character: a long reply is scanned once,
        // not copied once per character.
        if (source.startsWith("`", i)) {
            val close = source.indexOf('`', startIndex = i + 1)
            if (close > i) {
                flush()
                spans += Inline(source.substring(i + 1, close), code = true)
                i = close + 1
                continue
            }
        }

        // Bold before italic: `**` would otherwise match the italic rule twice and
        // produce an empty emphasised span between them.
        val bold = delimited(source, i, "**") ?: delimited(source, i, "__")
        if (bold != null) {
            flush()
            spans += Inline(bold.first, bold = true)
            i = bold.second
            continue
        }

        val italic = delimited(source, i, "*") ?: delimited(source, i, "_")
        if (italic != null) {
            flush()
            spans += Inline(italic.first, italic = true)
            i = italic.second
            continue
        }

        plain.append(source[i])
        i++
    }

    flush()
    return spans
}

/**
 * The text between a pair of [marker]s starting at [start], and where to resume.
 *
 * Null when there is no closing marker, which is how an unpaired `*` — a bullet the
 * model typed mid-sentence, or a multiplication sign — stays literal instead of
 * swallowing the rest of the line.
 */
private fun delimited(source: String, start: Int, marker: String): Pair<String, Int>? {
    if (!source.startsWith(marker, start)) return null

    val from = start + marker.length
    val close = source.indexOf(marker, startIndex = from)
    if (close <= from) return null // no closer, or an empty pair like ``**``

    return source.substring(from, close) to close + marker.length
}
