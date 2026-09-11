package dev.anodex.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Markdown a coding agent actually emits.
 *
 * Every case here is taken from the shape of a real reply rather than from the
 * CommonMark spec, because the failure that matters is not "this document is not
 * standards-compliant" — it is "the user cannot read the command they were told to
 * run". A parser that mangles a fenced block is worse than no parser at all.
 */
class MarkdownTest {

    private fun blocks(source: String) = parseMarkdown(source)

    @Test
    fun `a fenced block keeps its contents exactly`() {
        // Whitespace inside a code block is load-bearing. Trimming, re-joining or
        // collapsing it changes what the user copies out.
        val code = blocks("```kotlin\nfun main() {\n    println(\"hi\")\n}\n```")

        val block = code.single() as MarkdownBlock.CodeBlock
        assertEquals("kotlin", block.language)
        assertEquals("fun main() {\n    println(\"hi\")\n}", block.text)
        assertTrue(block.complete)
    }

    @Test
    fun `a fence with no language is still a code block`() {
        val block = blocks("```\nnpm run build\n```").single() as MarkdownBlock.CodeBlock

        assertEquals(null, block.language)
        assertEquals("npm run build", block.text)
    }

    @Test
    fun `an unclosed fence mid-stream is a code block, not literal text`() {
        // The normal case while a reply is arriving. Treating it as a paragraph makes
        // every code-bearing reply flicker from prose to block at the moment the
        // model happens to close the fence.
        val block = blocks("Here:\n\n```sh\nnpm test").last() as MarkdownBlock.CodeBlock

        assertEquals("npm test", block.text)
        assertFalse(block.complete)
    }

    @Test
    fun `a blank unclosed fence produces an empty block rather than nothing`() {
        // The very first token of a code block. If this collapses to no block at all
        // the reply visibly jumps when the first line of code lands.
        val block = blocks("```sh").single() as MarkdownBlock.CodeBlock

        assertEquals("", block.text)
        assertFalse(block.complete)
    }

    @Test
    fun `backticks inside a longer fence do not end the block early`() {
        val block = blocks("````\nuse ``` to fence\n````").single() as MarkdownBlock.CodeBlock

        assertEquals("use ``` to fence", block.text)
        assertTrue(block.complete)
    }

    @Test
    fun `inline code is not scanned for emphasis`() {
        // An agent's reply is full of code containing the exact characters Markdown
        // uses for emphasis. `a ** b` is an operator, not bold.
        val spans = parseInline("Call `foo(a ** b)` first")

        val code = spans.single { it.code }
        assertEquals("foo(a ** b)", code.text)
        assertTrue(spans.none { it.bold })
    }

    @Test
    fun `bold wins over italic so double stars do not become empty spans`() {
        val spans = parseInline("**really** important")

        assertEquals("really", spans.first().text)
        assertTrue(spans.first().bold)
        assertFalse(spans.first().italic)
    }

    @Test
    fun `an unpaired asterisk stays literal`() {
        // A multiplication sign, or a bullet the model typed mid-sentence. Swallowing
        // the rest of the line would delete text the user needs.
        val spans = parseInline("2 * 3 = 6")

        assertEquals("2 * 3 = 6", spans.joinToString("") { it.text })
        assertTrue(spans.none { it.italic || it.bold })
    }

    @Test
    fun `an unpaired backtick stays literal`() {
        val spans = parseInline("the ` character")

        assertEquals("the ` character", spans.joinToString("") { it.text })
        assertTrue(spans.none { it.code })
    }

    @Test
    fun `bullets become a list`() {
        val list = blocks("- one\n- two\n- three").single() as MarkdownBlock.ListBlock

        assertFalse(list.ordered)
        assertEquals(listOf("one", "two", "three"), list.items.map { it.single().text })
    }

    @Test
    fun `numbered steps stay numbered`() {
        val list = blocks("1. first\n2. second").single() as MarkdownBlock.ListBlock

        assertTrue(list.ordered)
        assertEquals(2, list.items.size)
    }

    @Test
    fun `a bulleted list that turns numbered is two lists`() {
        // Merging them would renumber the user's content, which for a list of steps
        // is a change of meaning rather than a change of style.
        val parsed = blocks("- a\n- b\n1. one\n2. two")

        assertEquals(2, parsed.size)
        assertFalse((parsed[0] as MarkdownBlock.ListBlock).ordered)
        assertTrue((parsed[1] as MarkdownBlock.ListBlock).ordered)
    }

    @Test
    fun `a paragraph ends where a list or fence begins`() {
        val parsed = blocks("Two things:\n- one\n- two")

        assertEquals(2, parsed.size)
        assertTrue(parsed[0] is MarkdownBlock.Paragraph)
        assertTrue(parsed[1] is MarkdownBlock.ListBlock)
    }

    @Test
    fun `wrapped lines join into one paragraph`() {
        val paragraph = blocks("one line\nand its continuation").single()
            as MarkdownBlock.Paragraph

        assertEquals("one line and its continuation", paragraph.spans.joinToString("") { it.text })
    }

    @Test
    fun `headings are recognised at every level`() {
        for (level in 1..6) {
            val hashes = "#".repeat(level)
            val heading = blocks("$hashes Title").single() as MarkdownBlock.Heading
            assertEquals(level, heading.level)
            assertEquals("Title", heading.spans.single().text)
        }
    }

    @Test
    fun `a hash with no space is not a heading`() {
        // `#include`, or a colour like #fff. Both are ordinary text.
        assertTrue(blocks("#include <stdio.h>").single() is MarkdownBlock.Paragraph)
    }

    @Test
    fun `no text is ever dropped`() {
        // The property that matters most. A parser that silently loses a line is
        // worse than one that renders it plainly, because nothing reveals the loss.
        val source = """
            Found it. The fix is in `useDragBody`:

            ```ts
            const basis = useRef(cameraBasis)
            ```

            Then:
            1. run the tests
            2. commit

            ## Note
            This does **not** touch the solver.
        """.trimIndent()

        val text = blocks(source).joinToString(" ") { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> block.spans.joinToString("") { it.text }
                is MarkdownBlock.Heading -> block.spans.joinToString("") { it.text }
                is MarkdownBlock.CodeBlock -> block.text
                is MarkdownBlock.ListBlock ->
                    block.items.joinToString(" ") { item -> item.joinToString("") { it.text } }

                is MarkdownBlock.TableBlock ->
                    (listOf(block.header) + block.rows).joinToString(" ") { row ->
                        row.joinToString(" ") { cell -> cell.joinToString("") { it.text } }
                    }
            }
        }

        for (fragment in listOf(
            "Found it.",
            "useDragBody",
            "const basis = useRef(cameraBasis)",
            "run the tests",
            "commit",
            "Note",
            "solver",
        )) {
            assertTrue("lost \"$fragment\" from the reply", text.contains(fragment))
        }
    }

    @Test
    fun `empty and blank input produce nothing rather than an empty paragraph`() {
        assertEquals(emptyList<MarkdownBlock>(), parseMarkdown(""))
        assertEquals(emptyList<MarkdownBlock>(), parseMarkdown("   \n\n  "))
        assertEquals(emptyList<Inline>(), parseInline(""))
    }

    @Test
    fun `windows line endings do not leak into the text`() {
        // The desktop is a Windows machine, so replies can arrive with CRLF.
        val block = blocks("```\r\na\r\nb\r\n```").single() as MarkdownBlock.CodeBlock

        assertEquals("a\nb", block.text)
    }
}
