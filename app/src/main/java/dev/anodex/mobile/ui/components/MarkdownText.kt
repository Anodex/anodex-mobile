package dev.anodex.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.chat.Inline
import dev.anodex.mobile.chat.MarkdownBlock
import dev.anodex.mobile.chat.parseMarkdown
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing

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
        style = type.body,
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
        style = if (block.level <= 2) type.heading else type.bodyEmphasis,
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
                    style = type.body,
                    color = colors.textFaint,
                )
                Text(
                    text = annotate(spans),
                    style = type.body,
                    color = colors.text,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * A fenced block.
 *
 * Scrolls sideways rather than wrapping. Wrapped code is worse than clipped code —
 * indentation is how you read it, and a soft-wrapped line silently invents structure
 * that is not in the file.
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
        if (block.language != null) {
            Text(block.language, style = type.badge, color = colors.textFaint)
        }

        Text(
            text = block.text,
            style = type.mono,
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

/** Emphasis, as Compose spans. */
@Composable
private fun annotate(spans: List<Inline>): AnnotatedString {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    return buildAnnotatedString {
        for (span in spans) {
            when {
                span.code -> withStyle(
                    SpanStyle(
                        fontFamily = type.mono.fontFamily,
                        fontSize = type.mono.fontSize,
                        color = colors.codeInlineText,
                        background = colors.bgSurface2,
                    )
                ) { append(span.text) }

                span.bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    append(span.text)
                }

                span.italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(span.text)
                }

                else -> append(span.text)
            }
        }
    }
}
