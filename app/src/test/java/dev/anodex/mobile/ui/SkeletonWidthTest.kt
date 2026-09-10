package dev.anodex.mobile.ui

import dev.anodex.mobile.ui.components.barWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ragged widths of a resting list.
 *
 * The obvious implementation of this is `Random.nextFloat()`, and it is wrong in a
 * way that only shows up on a device: a recomposition — which a loading screen gets
 * plenty of — would redraw every bar at a new width. That is motion, arriving
 * through the back door, in the one composable whose whole argument is that it does
 * not move.
 *
 * So these tests are mostly one test: the same row always comes out the same width.
 */
class SkeletonWidthTest {

    @Test
    fun `the same line is always the same width`() {
        repeat(50) {
            assertEquals(barWidth(2, 1, 3), barWidth(2, 1, 3), 0f)
        }
    }

    @Test
    fun `titles differ from row to row`() {
        val titles = (0 until 4).map { barWidth(it, 0, 2) }
        assertEquals(
            "four rows should not share a title width",
            4,
            titles.toSet().size,
        )
    }

    @Test
    fun `the last line of a row is the shortest`() {
        // What a wrapped paragraph does. Without it a card of equal-length bars
        // reads as a table, which is not the thing being waited for.
        val lines = 3
        val last = barWidth(0, lines - 1, lines)
        for (line in 1 until lines - 1) {
            assertTrue(
                "line $line should be longer than the last",
                barWidth(0, line, lines) > last,
            )
        }
    }

    @Test
    fun `every width is a usable fraction`() {
        // `fillMaxWidth` takes 0 to 1 and a bar at either end is either invisible or
        // a solid block edge to edge. Neither is a resting line of text.
        for (row in 0 until 8) {
            for (lines in 1..4) {
                for (line in 0 until lines) {
                    val width = barWidth(row, line, lines)
                    assertTrue("row $row line $line was $width", width in 0.2f..0.98f)
                }
            }
        }
    }
}
