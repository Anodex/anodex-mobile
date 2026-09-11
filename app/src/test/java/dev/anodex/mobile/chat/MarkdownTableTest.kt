package dev.anodex.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comparison an agent draws when it is weighing two things.
 *
 * A table was the last thing a reply could contain that the phone could not read.
 * Fences, lists and emphasis were all handled; a table arrived as every one of its
 * lines joined into a single paragraph, which is the worst of the failure modes
 * because the content is still all there and none of it is legible.
 *
 * The delicate half is *not* seeing tables that are not there. A pipe is a common
 * character — shell output, a regex, a union type — so recognition hangs entirely
 * on the `|---|` rule underneath, and these tests care as much about what stays a
 * paragraph as about what becomes a grid.
 */
class MarkdownTableTest {

    private fun table(source: String) = parseMarkdown(source).single() as MarkdownBlock.TableBlock

    private fun text(cell: List<Inline>) = cell.joinToString("") { it.text }

    private fun row(block: MarkdownBlock.TableBlock, index: Int) = block.rows[index].map(::text)

    // --- what is a table ----------------------------------------------------------

    @Test
    fun `a plain table keeps its headers and its cells`() {
        val block = table(
            """
            | Option | Cost |
            |--------|------|
            | Keep   | None |
            | Rewrite| A week |
            """.trimIndent()
        )

        assertEquals(listOf("Option", "Cost"), block.header.map(::text))
        assertEquals(listOf("Keep", "None"), row(block, 0))
        assertEquals(listOf("Rewrite", "A week"), row(block, 1))
    }

    @Test
    fun `outer pipes are optional and may be inconsistent`() {
        // Models write all three styles, sometimes inside one reply.
        val block = table(
            """
            Option | Cost
            -------|-----
            | Keep | None |
            Rewrite | A week
            """.trimIndent()
        )

        assertEquals(listOf("Option", "Cost"), block.header.map(::text))
        assertEquals(listOf("Keep", "None"), row(block, 0))
        assertEquals(listOf("Rewrite", "A week"), row(block, 1))
    }

    @Test
    fun `alignment is read from the rule`() {
        val block = table(
            """
            | Left | Middle | Right |
            |:-----|:------:|------:|
            | a | b | c |
            """.trimIndent()
        )

        assertEquals(listOf(Align.START, Align.CENTER, Align.END), block.alignments)
    }

    @Test
    fun `a rule with no colons is all start`() {
        val block = table("a | b\n---|---\n1 | 2")

        assertEquals(listOf(Align.START, Align.START), block.alignments)
    }

    @Test
    fun `cells carry their own markup`() {
        // A table of files is a table of `code`, and a table of options is a table
        // of links. Both are the normal case rather than a flourish.
        val block = table(
            """
            | File | Where |
            |------|-------|
            | `ChatScreen.kt` | [the PR](https://example.com/94) |
            """.trimIndent()
        )

        assertTrue(block.rows[0][0].single().code)
        assertEquals("https://example.com/94", block.rows[0][1].single().link)
    }

    @Test
    fun `an escaped pipe stays inside its cell`() {
        // A table of shell commands is a table full of pipes.
        val block = table(
            """
            | What | Command |
            |------|---------|
            | Count | `ls \| wc -l` |
            """.trimIndent()
        )

        assertEquals(2, block.rows[0].size)
        assertEquals("ls | wc -l", text(block.rows[0][1]))
    }

    // --- ragged input -------------------------------------------------------------

    @Test
    fun `a short row is padded rather than shifted`() {
        // Models drop trailing cells often. A row silently shifted left puts the
        // wrong value under the wrong heading, which is worse than a blank.
        val block = table(
            """
            | A | B | C |
            |---|---|---|
            | 1 | 2 |
            """.trimIndent()
        )

        assertEquals(3, block.rows[0].size)
        assertEquals(listOf("1", "2", ""), row(block, 0))
    }

    @Test
    fun `a long row is trimmed to the columns that exist`() {
        val block = table(
            """
            | A | B |
            |---|---|
            | 1 | 2 | 3 |
            """.trimIndent()
        )

        assertEquals(listOf("1", "2"), row(block, 0))
    }

    @Test
    fun `a header with no rows is still a table`() {
        val block = table("| A | B |\n|---|---|")

        assertEquals(listOf("A", "B"), block.header.map(::text))
        assertTrue(block.rows.isEmpty())
    }

    // --- the rule row models keep forgetting --------------------------------------

    @Test
    fun `a table with no rule row is still a table`() {
        // Observed on the first table a real phone asked a real model for, verbatim
        // from what the desktop stored. The `| --- |` line simply was not there.
        //
        // Rendering this as a paragraph is the worst available outcome: every cell
        // joined end to end with the pipes still in, all of the content and none of
        // it legible. The reader is not the one who got the format wrong.
        val block = table(
            """
            | Option | Cost | Why |
            | A | ${'$'}10 | A is the cheaper route because it only takes an afternoon. |
            | B | ${'$'}50 | B costs more up front but pays for itself within a quarter. |
            """.trimIndent()
        )

        assertEquals(listOf("Option", "Cost", "Why"), block.header.map(::text))
        assertEquals(2, block.rows.size)
        assertEquals("A", text(block.rows[0][0]))
        assertEquals("${'$'}50", text(block.rows[1][1]))
    }

    @Test
    fun `a ruleless table is left-aligned, because nothing said otherwise`() {
        // Inventing an alignment out of an omission would be reading intent into a
        // mistake.
        val block = table("| A | B |\n| 1 | 2 |")

        assertEquals(listOf(Align.START, Align.START), block.alignments)
    }

    @Test
    fun `a ruleless table still needs both lines to agree on their columns`() {
        val blocks = parseMarkdown("| A | B | C |\n| 1 | 2 |")

        assertTrue(blocks.none { it is MarkdownBlock.TableBlock })
    }

    @Test
    fun `without a rule, both lines must be piped at both ends`() {
        // The whole discriminator. Shell output and union types have pipes in the
        // middle; a row of a table has them at the edges too.
        for (prose in listOf(
            "ls | wc -l\ngrep foo | sort",
            "The type is A | B\nThe other is C | D",
            "| A | B |\nplain prose underneath",
            "leading only | a | b\n| 1 | 2 |",
        )) {
            val blocks = parseMarkdown(prose)
            assertTrue(
                "made a table out of <${prose.replace("\n", "\\n")}>",
                blocks.none { it is MarkdownBlock.TableBlock },
            )
        }
    }

    @Test
    fun `a lone piped line is not a table`() {
        // One row is a coincidence. Two agreeing rows is a shape.
        assertTrue(parseMarkdown("| A | B |").none { it is MarkdownBlock.TableBlock })
        assertTrue(parseMarkdown("| A | B |\n").none { it is MarkdownBlock.TableBlock })
        assertTrue(parseMarkdown("| A | B |\n\n| C | D |").none { it is MarkdownBlock.TableBlock })
    }

    @Test
    fun `a ruleless single column is still not a table`() {
        assertTrue(parseMarkdown("| A |\n| 1 |").none { it is MarkdownBlock.TableBlock })
    }

    @Test
    fun `a rule that is present still wins over the ruleless path`() {
        // The rule line must never be read as a row of dashes.
        val block = table("| A | B |\n|---|---|\n| 1 | 2 |")

        assertEquals(1, block.rows.size)
        assertEquals(listOf("1", "2"), row(block, 0))
    }

    // --- what is not a table ------------------------------------------------------

    @Test
    fun `a sentence containing a pipe stays a sentence`() {
        for (prose in listOf(
            "Run it with ls | wc -l and see.",
            "The type is A | B | C.",
            "a | b",
        )) {
            val blocks = parseMarkdown(prose)
            assertTrue(
                "made a table out of <$prose>",
                blocks.none { it is MarkdownBlock.TableBlock },
            )
        }
    }

    @Test
    fun `a horizontal rule is not a table rule`() {
        // `---` under a line of prose is the shape that would turn every section
        // break in a reply into a one-column table.
        val blocks = parseMarkdown("Some heading\n---\nand then more text")

        assertTrue(blocks.none { it is MarkdownBlock.TableBlock })
    }

    @Test
    fun `a single column is not a table`() {
        val blocks = parseMarkdown("| A |\n|---|\n| 1 |")

        assertTrue(blocks.none { it is MarkdownBlock.TableBlock })
    }

    @Test
    fun `a header that does not match its own rule is not a table`() {
        val blocks = parseMarkdown("| A | B | C |\n|---|---|\n| 1 | 2 | 3 |")

        assertTrue(blocks.none { it is MarkdownBlock.TableBlock })
    }

    @Test
    fun `pipes inside a fenced block are left alone`() {
        val blocks = parseMarkdown("```sh\nls | wc -l\n---|---\n```")

        assertTrue(blocks.single() is MarkdownBlock.CodeBlock)
    }

    // --- tables among everything else ---------------------------------------------

    @Test
    fun `a paragraph directly above a table does not swallow it`() {
        // The case with no blank line between, which is how most replies are typed.
        // Without the lookahead the paragraph runs on through every row and prints
        // the whole grid as one line of pipes.
        val blocks = parseMarkdown(
            """
            Here is the comparison:
            | Option | Cost |
            |--------|------|
            | Keep | None |
            """.trimIndent()
        )

        assertEquals(2, blocks.size)
        val paragraph = blocks[0] as MarkdownBlock.Paragraph
        assertEquals("Here is the comparison:", text(paragraph.spans))
        assertTrue(blocks[1] is MarkdownBlock.TableBlock)
    }

    @Test
    fun `text after a table is its own paragraph`() {
        val blocks = parseMarkdown(
            """
            | A | B |
            |---|---|
            | 1 | 2 |

            So I would keep it.
            """.trimIndent()
        )

        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.TableBlock)
        assertEquals("So I would keep it.", text((blocks[1] as MarkdownBlock.Paragraph).spans))
    }

    @Test
    fun `two tables in one reply stay separate`() {
        val blocks = parseMarkdown(
            """
            | A | B |
            |---|---|
            | 1 | 2 |

            | C | D |
            |---|---|
            | 3 | 4 |
            """.trimIndent()
        )

        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it is MarkdownBlock.TableBlock })
    }

    // --- streaming ----------------------------------------------------------------

    @Test
    fun `every prefix of a table parses without throwing or losing text`() {
        // A table arrives a token at a time, and for the frames before the `|---|`
        // line lands it is honestly just a paragraph with a pipe in it. What must
        // not happen is a throw, or a row of the table going missing on the way.
        val whole = """
            Here is the comparison:
            | Option | Cost |
            |--------|------|
            | Keep | None |
            | Rewrite | A week |
        """.trimIndent()

        for (length in 1..whole.length) {
            val prefix = whole.take(length)
            val blocks = parseMarkdown(prefix)

            val rendered = blocks.joinToString(" ") { block ->
                when (block) {
                    is MarkdownBlock.Paragraph -> text(block.spans)
                    is MarkdownBlock.TableBlock ->
                        (listOf(block.header) + block.rows).joinToString(" ") { row ->
                            row.joinToString(" ", transform = ::text)
                        }

                    is MarkdownBlock.Heading -> text(block.spans)
                    is MarkdownBlock.ListBlock -> block.items.joinToString(" ", transform = ::text)
                    is MarkdownBlock.CodeBlock -> block.text
                }
            }

            // "Keep" is in the last complete row of every prefix that contains it.
            if (prefix.contains("| Keep | None |")) {
                assertTrue("lost a row from the prefix of length $length", rendered.contains("Keep"))
            }
            assertFalse("dropped everything at length $length", rendered.isEmpty())
        }
    }
}
