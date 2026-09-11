package dev.anodex.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.chat.Align
import dev.anodex.mobile.chat.Inline
import dev.anodex.mobile.chat.MarkdownBlock
import dev.anodex.mobile.chat.parseMarkdown
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * An assistant reply, rendered rather than printed.
 *
 * The phone was showing raw text, which for a coding agent means backticks, asterisks
 * and shell commands run together into a paragraph — the reply arrives formatted and
 * the app was throwing that away.
 */
@Composable
fun MarkdownText(source: String, modifier: Modifier = Modifier) {
    // Re-parsed only when the text changes. During streaming it changes on every
    // token, which is exactly why the parser is a single pass over the string.
    val blocks = remember(source) { parseMarkdown(source) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Paragraph -> Body(block.spans)
                is MarkdownBlock.Heading -> HeadingBlock(block)
                is MarkdownBlock.ListBlock -> ListBlockView(block)
                is MarkdownBlock.CodeBlock -> CodeBlockView(block)
                is MarkdownBlock.TableBlock -> TableView(block)
            }
        }
    }
}

@Composable
private fun Body(spans: List<Inline>) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    Text(
        text = annotate(spans),
        style = type.chatBody,
        color = colors.text,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun HeadingBlock(block: MarkdownBlock.Heading) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    // Two sizes, not six. A phone-width reply has no room for a heading hierarchy,
    // and the distinction that matters is "this starts a section" versus "this does
    // not" — six barely-different sizes convey neither.
    Text(
        text = annotate(block.spans),
        style = if (block.level <= 2) type.chatHeading else type.chatBodyEmphasis,
        color = colors.text,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ListBlockView(block: MarkdownBlock.ListBlock) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
        block.items.forEachIndexed { index, spans ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                Text(
                    text = if (block.ordered) "${index + 1}." else "\u2022",
                    style = type.chatBody,
                    color = colors.textFaint,
                )
                Text(
                    text = annotate(spans),
                    style = type.chatBody,
                    color = colors.text,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * A pipe table.
 *
 * **It fits the width, and cells wrap.** The code block beside it scrolls sideways
 * and that is right for code, where indentation is structure and a wrapped line
 * invents some. A table's cells are prose, so wrapping costs nothing — and a
 * sideways-scrolling thing inside a vertically scrolling conversation is a gesture
 * fight the reader loses every time they try to scroll past it.
 *
 * Columns are weighted by their longest cell rather than shared out evenly, because
 * a table of "Option / What it costs / Why" is mostly the third column, and three
 * equal thirds would wrap the explanation to six lines beside two words of white
 * space. The weight is clamped at both ends so a single long column cannot crush
 * the others into a letter apiece.
 *
 * Wide tables do get cramped. Five columns on a narrow phone is several lines per
 * cell, which is legible but not pretty — and still better than the alternative,
 * which is that the reader never sees the last two columns at all.
 */
@Composable
private fun TableView(block: MarkdownBlock.TableBlock) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    val weights = remember(block) { columnWeights(block) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.md)
            .background(colors.bgSurface2),
    ) {
        TableRow(block.header, block.alignments, weights, type.chatBodyEmphasis)

        // The header's rule is the strong one and the rest are faint. A grid of
        // equally weighted lines reads as a spreadsheet; what is wanted here is one
        // line that says "labels above, values below" and just enough after it to
        // keep the eye on a row.
        Hairline(color = colors.borderStrong)

        block.rows.forEachIndexed { index, row ->
            if (index > 0) Hairline(color = colors.border)
            TableRow(row, block.alignments, weights, type.chatBody)
        }
    }
}

@Composable
private fun TableRow(
    cells: List<List<Inline>>,
    alignments: List<Align>,
    weights: List<Float>,
    style: TextStyle,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.x3, vertical = Spacing.x2),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        cells.forEachIndexed { column, spans ->
            Text(
                text = annotate(spans),
                style = style,
                color = AnodexTheme.colors.text,
                textAlign = when (alignments.getOrElse(column) { Align.START }) {
                    Align.START -> TextAlign.Start
                    Align.CENTER -> TextAlign.Center
                    Align.END -> TextAlign.End
                },
                modifier = Modifier.weight(weights.getOrElse(column) { 1f }),
            )
        }
    }
}

/**
 * How much of the width each column gets.
 *
 * Character counts, which is crude — proportional text makes "IIII" and "MMMM" very
 * different widths — but it is measuring the right thing for the decision being
 * made, which is only ever "this column holds sentences and that one holds ticks".
 *
 * Clamped at both ends. The floor stops a column of `✓` from being squeezed to
 * nothing; the ceiling stops one paragraph-shaped cell from taking the whole row.
 */
private fun columnWeights(block: MarkdownBlock.TableBlock): List<Float> =
    block.header.indices.map { column ->
        val header = block.header[column].sumOf { it.text.length }
        val widest = block.rows.maxOfOrNull { row -> row[column].sumOf { it.text.length } } ?: 0
        maxOf(header, widest).coerceIn(COLUMN_FLOOR, COLUMN_CEILING).toFloat()
    }

private const val COLUMN_FLOOR = 4
private const val COLUMN_CEILING = 28

/**
 * A fenced block.
 *
 * Scrolls sideways rather than wrapping. Wrapped code is worse than clipped code —
 * indentation is how you read it, and a soft-wrapped line silently invents structure
 * that is not in the file.
 *
 * It has its own copy control, which the desktop's blocks do not. That is a
 * difference in the input rather than in the design: on a computer you drag across
 * the four words you want, and on a phone you long-press a horizontally scrolling
 * monospace block and fight the selection handles. The reply already has a Copy
 * under it, but that one takes the whole answer — and what somebody wants from a
 * reply full of prose is usually the one command in the middle of it.
 */
@Composable
private fun CodeBlockView(block: MarkdownBlock.CodeBlock) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.md)
            .background(colors.bgSurface2)
            .padding(Spacing.x3),
        verticalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        // The header row exists even with no language to name, because the copy
        // control lives in it. Hiding it when the fence is unlabelled would take the
        // control away in exactly the case — a bare ``` — where the contents are most
        // likely to be a command somebody wants to run.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = block.language.orEmpty(),
                style = type.badge,
                color = colors.textFaint,
                modifier = Modifier.weight(1f),
            )

            // Nothing to copy until the fence closes. Half an arrived command on the
            // clipboard is the sort of thing somebody pastes into a shell.
            if (block.complete && block.text.isNotBlank()) {
                CopyControl(block.text)
            }
        }

        Text(
            text = block.text,
            style = type.chatMono,
            color = colors.text,
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )

        if (!block.complete) {
            // Still arriving. Without this an unfinished block looks like a finished
            // one that happens to be truncated, which is a much worse thing to
            // believe about code you are about to copy.
            Text("\u2026", style = type.meta, color = colors.textFaint)
        }
    }
}

/**
 * Take this block, as a tick that admits it happened.
 *
 * Android 13 and up shows its own clipboard confirmation, so the tick is redundant
 * on a new phone and the only feedback on an older one — and a control that looks
 * identical before and after a tap is one people press twice. It reverts, because a
 * tick that stays stops meaning "just now" and starts meaning "this block is
 * special", which is not a thing.
 */
@Composable
private fun CopyControl(text: String) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    val clipboard = LocalClipboardManager.current

    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_MS)
            copied = false
        }
    }

    Row(
        modifier = Modifier
            // Clipped before it is clickable, so the press wash stops at the corner
            // radius instead of painting a square over the block's shoulder.
            .clip(Radii.sm)
            .clickable(onClickLabel = "Copy this code") {
                clipboard.setText(AnnotatedString(text))
                copied = true
            }
            // Padded rather than sized. A 48dp square in the corner of every fence
            // would put a hole in the top of each block; the padding still makes the
            // target comfortably larger than the glyph inside it.
            .padding(horizontal = Spacing.x2, vertical = Spacing.x1),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnodexIcon(
            icon = if (copied) AnodexIcon.CHECK else AnodexIcon.COPY,
            size = 13.dp,
            tint = if (copied) colors.successInk else colors.textFaint,
        )
        Text(
            text = if (copied) "Copied" else "Copy",
            style = type.badge,
            color = if (copied) colors.successInk else colors.textFaint,
        )
    }
}

/** How long the tick stays before the control goes back to offering. */
private const val COPIED_MS = 1_600L

/**
 * Emphasis and destination, as Compose spans.
 *
 * A link is a wrapper around whatever styling the run already had rather than a
 * style of its own, because the two are independent: `[**the PR**](url)` is bold
 * *and* tappable, and picking one would drop the other.
 */
@Composable
private fun annotate(spans: List<Inline>): AnnotatedString {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    return buildAnnotatedString {
        for (span in spans) {
            val emphasis = when {
                span.code -> SpanStyle(
                    fontFamily = type.chatMono.fontFamily,
                    fontSize = type.chatMono.fontSize,
                    // A link's own colour would fight the code background, so a
                    // fenced label keeps reading as code and takes underline alone.
                    color = if (span.link == null) colors.codeInlineText else colors.accentInk,
                    background = colors.bgSurface2,
                )

                span.bold -> SpanStyle(fontWeight = FontWeight.SemiBold)
                span.italic -> SpanStyle(fontStyle = FontStyle.Italic)
                span.link != null -> SpanStyle(color = colors.accentInk)
                else -> null
            }

            // Underlined as well as coloured. Colour alone is not an affordance for
            // a reader who cannot see this one, and a link that only looks different
            // is a link only some people can find.
            val styled = when {
                span.link != null && emphasis == null ->
                    SpanStyle(color = colors.accentInk, textDecoration = TextDecoration.Underline)

                span.link != null ->
                    emphasis!!.merge(
                        SpanStyle(
                            color = colors.accentInk,
                            textDecoration = TextDecoration.Underline,
                        )
                    )

                else -> emphasis
            }

            val write: AnnotatedString.Builder.() -> Unit = {
                if (styled == null) append(span.text) else withStyle(styled) { append(span.text) }
            }

            // `withLink` hands the tap to the platform's URI handler, which is why
            // the parser refuses anything but http, https and mailto: this is the
            // seam where a model's text becomes something the phone acts on.
            if (span.link != null) {
                withLink(LinkAnnotation.Url(span.link)) { write() }
            } else {
                write()
            }
        }
    }
}
