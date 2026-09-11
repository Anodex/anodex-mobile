package dev.anodex.mobile.chat

import androidx.compose.runtime.Immutable

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
 * in these replies, and all of it is surface area. This handles the handful of things
 * that do appear — fences, inline code, headings, lists, tables, emphasis and links —
 * and treats everything else as ordinary text, which is the failure mode you want:
 * an unsupported construct reads as what the model typed rather than disappearing.
 *
 * ## Streaming
 *
 * Half a document is the normal case, not an error. A fence that has been opened and
 * not yet closed is a code block still arriving, and it renders as one — treating it
 * as literal text would make every reply containing code flicker from paragraph to
 * block at the moment the model happens to close it.
 */

/**
 * A run of text with the emphasis that applies to it.
 *
 * `@Immutable`, and the whole model below with it, because of what streaming does
 * to this screen. A reply arrives a token at a time and every token re-parses the
 * message, so the list of blocks is rebuilt thirty times a second — but almost all
 * of it is identical each time, since only the last paragraph is still growing.
 *
 * Compose cannot know that on its own. `List` is an interface, so a block holding
 * one is treated as unstable and re-rendered whether or not its contents changed:
 * every paragraph of a long answer, laid out and drawn again, on every token. The
 * annotation is the promise that lets equality be trusted and the unchanged
 * paragraphs be skipped.
 *
 * The promise is real — nothing here is ever mutated after `parseMarkdown` returns
 * it. If that stops being true this becomes a rendering bug rather than a slow
 * screen, which is the trade being made deliberately.
 */
@Immutable
data class Inline(
    val text: String,
    val code: Boolean = false,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /**
     * Where this run points, or null for ordinary text.
     *
     * Emphasis and destination are separate axes rather than alternatives: a link
     * can be bold, and a `[`link`](url)` with a code label is a shape agents
     * produce constantly when they point at a file.
     */
    val link: String? = null,
)

@Immutable
sealed interface MarkdownBlock {
    @Immutable
    data class Paragraph(val spans: List<Inline>) : MarkdownBlock

    /** A fenced block. [complete] is false while the closing fence is still to come. */
    @Immutable
    data class CodeBlock(
        val language: String?,
        val text: String,
        val complete: Boolean = true,
    ) : MarkdownBlock

    @Immutable
    data class Heading(val level: Int, val spans: List<Inline>) : MarkdownBlock

    /** A bullet or numbered list. [ordered] decides which marker is drawn. */
    @Immutable
    data class ListBlock(val items: List<List<Inline>>, val ordered: Boolean) : MarkdownBlock

    /**
     * A pipe table.
     *
     * Every row here is guaranteed the same number of cells as [header], padded or
     * trimmed at parse time. A renderer that had to cope with ragged rows would end
     * up guessing which cell belongs to which column, and it would guess wrong on
     * exactly the table that mattered.
     */
    @Immutable
    data class TableBlock(
        val header: List<List<Inline>>,
        val rows: List<List<List<Inline>>>,
        val alignments: List<Align>,
    ) : MarkdownBlock
}

/** How a table column was asked to be aligned, from the `:---:` in its delimiter. */
enum class Align { START, CENTER, END }

private val FENCE = Regex("^\\s{0,3}(`{3,}|~{3,})\\s*([A-Za-z0-9+#._-]*)\\s*$")
private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^\\s{0,3}[-*+]\\s+(.*)$")
private val NUMBERED = Regex("^\\s{0,3}\\d{1,9}[.)]\\s+(.*)$")

/**
 * The `|---|:--:|` line under a table's header.
 *
 * A table is only a table because of this line, which is why detection looks ahead
 * rather than at the row in front of it: `a | b` on its own is a sentence containing
 * a pipe, and plenty of shell output is exactly that.
 */
private val TABLE_RULE = Regex("^\\s{0,3}\\|?(\\s*:?-+:?\\s*\\|)+\\s*:?-*:?\\s*\\|?\\s*$")

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

        // Tables are recognised by the line *after* the one in hand, because the
        // header row of a table and a sentence containing a pipe are the same thing
        // until the `|---|` arrives. While a reply is still streaming that line has
        // not arrived yet, so a table spends a frame or two as a paragraph and then
        // becomes a table — the alternative is guessing, and guessing wrong turns
        // ordinary prose into a one-row grid.
        if (startsTable(lines, index)) {
            val header = splitRow(line)
            val alignments = splitRow(lines[index + 1]).map(::alignOf)
            index += 2

            val rows = mutableListOf<List<List<Inline>>>()
            while (index < lines.size && lines[index].contains('|') && lines[index].isNotBlank()) {
                // Squared off here so the renderer never has to decide which column a
                // missing cell belonged to. Models drop a trailing pipe often enough
                // that ragged rows are the common case rather than the odd one, and a
                // row silently shifted left is worse than a blank cell.
                val cells = splitRow(lines[index])
                rows += List(header.size) { column -> parseInline(cells.getOrElse(column) { "" }) }
                index++
            }

            blocks += MarkdownBlock.TableBlock(
                header = header.map(::parseInline),
                rows = rows,
                alignments = alignments,
            )
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
                NUMBERED.matches(next) ||
                // "Here is the comparison:" directly above a table, with no blank
                // line between. Without this the paragraph swallows the whole grid
                // and renders it as one long line of pipes.
                startsTable(lines, index)
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
 * full of code containing exactly those characters. A URL inside backticks therefore
 * stays a piece of code and does not become a link, which is right: somebody who
 * fenced it wanted it read, not followed.
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

        // Links before emphasis, so a label like `[**the PR**](url)` is one link
        // rather than a stray bracket followed by bold text followed by an address.
        val link = linked(source, i)
        if (link != null) {
            flush()
            spans += link.spans
            i = link.next
            continue
        }

        val bare = bareUrl(source, i)
        if (bare != null) {
            flush()
            spans += Inline(bare.first, link = bare.first)
            i = bare.second
            continue
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
 * Whether the line at [index] is the header of a table.
 *
 * Asked in two places — where a block begins, and where a paragraph must stop —
 * because "Here is the comparison:" sitting directly above a table with no blank
 * line is common, and a paragraph that does not stop there swallows the whole grid
 * and prints it as one long line of pipes.
 */
private fun startsTable(lines: List<String>, index: Int): Boolean {
    if (index + 1 >= lines.size) return false
    if (!lines[index].contains('|')) return false
    if (!TABLE_RULE.matches(lines[index + 1])) return false

    // A single column, or a header that does not match its own rule, is a
    // coincidence rather than a table: prose with a pipe in it, above something that
    // happens to look like a rule.
    val header = splitRow(lines[index])
    return header.size >= 2 && header.size == splitRow(lines[index + 1]).size
}

/**
 * One table row, as its cells.
 *
 * The outer pipes are optional in the format and inconsistent in practice — models
 * write all three styles within a single reply — so they are stripped rather than
 * relied upon. `\|` is an escaped pipe inside a cell and does not divide it, which
 * matters because a table of shell commands is a table full of pipes.
 */
private fun splitRow(line: String): List<String> {
    var body = line.trim()
    if (body.startsWith("|")) body = body.substring(1)
    if (body.endsWith("|") && !body.endsWith("\\|")) body = body.dropLast(1)

    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var i = 0
    while (i < body.length) {
        val c = body[i]
        when {
            c == '\\' && i + 1 < body.length && body[i + 1] == '|' -> {
                cell.append('|')
                i += 2
            }

            c == '|' -> {
                cells += cell.toString().trim()
                cell.clear()
                i++
            }

            else -> {
                cell.append(c)
                i++
            }
        }
    }
    cells += cell.toString().trim()

    return cells
}

/** What one cell of a `:---:` rule asks for. */
private fun alignOf(rule: String): Align {
    val trimmed = rule.trim()
    val left = trimmed.startsWith(":")
    val right = trimmed.endsWith(":")
    return when {
        left && right -> Align.CENTER
        right -> Align.END
        else -> Align.START
    }
}

/** The spans a `[label](target)` produced, and where to resume reading. */
private class Linked(val spans: List<Inline>, val next: Int)

/**
 * A `[label](target)` starting at [start], or null if this bracket is just a bracket.
 *
 * The label is parsed as inline markup in its own right and the destination stamped
 * onto every run it produced, because `[the **failing** test](url)` is one link with
 * emphasis inside it rather than three separate things. Recursion terminates because
 * the label is strictly shorter than what contained it.
 */
private fun linked(source: String, start: Int): Linked? {
    if (!source.startsWith("[", start)) return null

    val close = source.indexOf(']', startIndex = start + 1)
    if (close < 0 || !source.startsWith("](", close)) return null

    // Balanced, because real URLs contain parentheses — a Wikipedia article, a
    // generic in a doc anchor — and stopping at the first `)` would truncate them.
    val open = close + 2
    var depth = 1
    var end = open
    while (end < source.length) {
        when (source[end]) {
            '(' -> depth++
            ')' -> if (--depth == 0) break
        }
        end++
    }
    if (depth != 0) return null

    val target = source.substring(open, end).trim()

    // An unrecognised scheme is not a link at all, and the construct stays as the
    // characters the model typed — the same failure mode as every other thing this
    // parser does not handle, and the one that loses nothing.
    //
    // The check belongs here rather than in the renderer because a tap hands this
    // string to the system to open, which makes it the one place in the app where
    // generated text becomes an action. Pages and addresses are worth opening;
    // `intent:` and `content:` are a way to have the phone do something else.
    if (!openable(target)) return null

    val label = source.substring(start + 1, close)
    val spans = if (label.isEmpty()) listOf(Inline(target)) else parseInline(label)

    return Linked(spans.map { it.copy(link = target) }, end + 1)
}

/**
 * A bare `https://…` starting at [start], and where it ends.
 *
 * Agents write far more bare URLs than bracketed ones, so leaving these as dead text
 * would be linking the rarer half. The delicate part is the end of the run: a URL at
 * the end of a sentence is followed by a full stop that belongs to the sentence, and
 * one quoted in prose often sits inside parentheses that are not its own.
 */
private fun bareUrl(source: String, start: Int): Pair<String, Int>? {
    val scheme = when {
        source.startsWith("https://", start, ignoreCase = true) -> 8
        source.startsWith("http://", start, ignoreCase = true) -> 7
        else -> return null
    }

    val body = start + scheme
    var end = body
    while (end < source.length && !source[end].isWhitespace() && source[end] !in "<>\"'`") end++

    // Trailing sentence punctuation, and a closing bracket that never opened in here.
    while (end > body) {
        val last = source[end - 1]
        val closer = last == ')' &&
            source.substring(start, end).count { it == ')' } >
            source.substring(start, end).count { it == '(' }
        if (last in ".,;:!?" || closer) end-- else break
    }

    if (end <= body) return null // a scheme and nothing after it
    return source.substring(start, end) to end
}

/** Whether a destination is one worth handing to the system to open. */
private fun openable(target: String): Boolean {
    val lower = target.lowercase()
    return lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("mailto:")
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
