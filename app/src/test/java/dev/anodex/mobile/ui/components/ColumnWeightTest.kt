package dev.anodex.mobile.ui.components

import dev.anodex.mobile.chat.MarkdownBlock
import dev.anodex.mobile.chat.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How wide each column of a table gets, and why it is not simply how long it is.
 *
 * This exists because the obvious version was shipped and looked wrong on a real
 * phone within a minute. An Option/Cost/Why table put `Option` over a column of `A`
 * and `B`, and `Why` over thirty characters of explanation. Weighting by raw length
 * made that 6 : 4 : 28, the heading got a sixth of the row, and the table rendered
 * its own headings as "Optio / n" and "Cos / t".
 *
 * The rule these hold is that **width demand grows more slowly than text does**. A
 * thirty-character cell does not need seven times the room of a four-character one:
 * wrapping it to a second line costs a line, while wrapping a six-letter heading
 * costs the reader the name of the column. Headings are the shortest text in a table
 * and the least able to survive being squeezed.
 *
 * The numbers below are ratios rather than pixels, since that is all the function
 * decides — the actual width arrives from whatever the row is laid out in.
 */
class ColumnWeightTest {

    private fun weights(source: String): List<Float> =
        columnWeights(parseMarkdown(source).single() as MarkdownBlock.TableBlock)

    /** Each column's share of the row, which is the thing that actually matters. */
    private fun shares(source: String): List<Float> {
        val weights = weights(source)
        val total = weights.sum()
        return weights.map { it / total }
    }

    /** The table that exposed the bug, as the model actually wrote it. */
    private val optionCostWhy = """
        | Option | Cost | Why |
        |--------|------|-----|
        | A | ${'$'}10 | Cheapest, fastest to ship |
        | B | ${'$'}25 | More features, higher maintenance |
    """.trimIndent()

    @Test
    fun `a short heading over a narrow column still gets room to sit on one line`() {
        // The regression. `Option` is six characters over cells of one, against a
        // column holding thirty-three. Raw lengths gave it 6/38 — about a sixth of a
        // phone's width, which is not enough for the word.
        val share = shares(optionCostWhy)

        assertTrue("Option got only ${share[0]} of the row", share[0] > 0.20f)
        assertTrue("Cost got only ${share[1]} of the row", share[1] > 0.15f)
    }

    @Test
    fun `the long column is still the widest`() {
        // Dampening must not flatten the ordering — the explanation column is the one
        // with something to say, and an even split would wrap it to six lines beside
        // two words of white space.
        val share = shares(optionCostWhy)

        assertEquals(2, share.indexOf(share.max()))
        assertTrue("Why got ${share[2]}, which is not the lion's share", share[2] > 0.45f)
    }

    @Test
    fun `a column of ticks is not squeezed to nothing`() {
        // The floor. One character of content against a sentence would otherwise be
        // a sliver too narrow to draw the tick in.
        val share = shares(
            """
            | Feature | Works | Notes |
            |---------|-------|-------|
            | Links | yes | Bracketed and bare, http and https only |
            """.trimIndent()
        )

        assertTrue("the tick column got ${share[1]}", share[1] > 0.15f)
    }

    @Test
    fun `one runaway cell cannot take the whole row`() {
        // The ceiling. A cell holding a paragraph is capped before the root, so a
        // model that puts an essay in one column does not erase the other two.
        val share = shares(
            """
            | A | B |
            |---|---|
            | x | ${"a very long sentence ".repeat(12)} |
            """.trimIndent()
        )

        assertTrue("the runaway column took ${share[1]}", share[1] < 0.80f)
        assertTrue("the other column got ${share[0]}", share[0] > 0.20f)
    }

    @Test
    fun `columns of equal length share the row equally`() {
        val share = shares(
            """
            | One | Two | Six |
            |-----|-----|-----|
            | aaa | bbb | ccc |
            """.trimIndent()
        )

        for (column in share) {
            assertEquals(1f / 3f, column, 0.001f)
        }
    }

    @Test
    fun `there is one weight per column, always`() {
        // A ragged row is squared off at parse time; this is the other half of that
        // promise — the renderer indexes weights by column and must never miss.
        val block = parseMarkdown(
            """
            | A | B | C |
            |---|---|---|
            | 1 | 2 |
            | 1 | 2 | 3 | 4 |
            """.trimIndent()
        ).single() as MarkdownBlock.TableBlock

        assertEquals(3, columnWeights(block).size)
    }

    @Test
    fun `a header with no rows still has weights`() {
        assertEquals(2, weights("| A | B |\n|---|---|").size)
    }

    @Test
    fun `every weight is a positive, usable number`() {
        // `Modifier.weight` throws on zero or negative, so a table that produced one
        // would crash the reply rather than render it oddly.
        for (weight in weights(optionCostWhy)) {
            assertTrue("weight was $weight", weight > 0f && weight.isFinite())
        }
    }
}
