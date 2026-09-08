package dev.anodex.mobile.ui

import dev.anodex.mobile.ui.components.matchesQuery
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding one thing among four hundred.
 *
 * The whole reason this is word-wise rather than a substring match is that nobody
 * remembers a title. They remember two words out of it, usually not adjacent and
 * usually not in the order they were written — so the cases that matter are the
 * ones a `contains` would miss.
 */
class SearchTest {

    @Test
    fun `finds words that were never next to each other`() {
        // The motivating case: a real title, and the two words somebody would recall.
        assertTrue(matchesQuery("Fix the failing tests in the parser", "parser tests"))
    }

    @Test
    fun `order does not matter`() {
        assertTrue(matchesQuery("Fix the failing tests in the parser", "tests parser"))
    }

    @Test
    fun `every word has to be there`() {
        // Not "any word". A query of two words that matched on one would return most
        // of the list, which is the same as returning nothing useful.
        assertFalse(matchesQuery("Fix the failing tests in the parser", "parser migrations"))
    }

    @Test
    fun `case is ignored in both directions`() {
        assertTrue(matchesQuery("Fix The Parser", "parser"))
        assertTrue(matchesQuery("fix the parser", "PARSER"))
    }

    @Test
    fun `a blank query keeps everything`() {
        // The field starts empty and is cleared with the ×. Either must show the
        // whole list rather than nothing at all.
        assertTrue(matchesQuery("anything", ""))
        assertTrue(matchesQuery("anything", "   "))
    }

    @Test
    fun `extra spaces between words are not words`() {
        // A double space would otherwise become an empty word that nothing contains,
        // so a query would stop matching the moment somebody typed a stray space.
        assertTrue(matchesQuery("Fix the failing tests in the parser", "parser   tests"))
    }

    @Test
    fun `matches inside a word`() {
        // Paths and identifiers run words together, so a prefix has to count:
        // "sim" must find "src/sim/orbit.rs".
        assertTrue(matchesQuery("src/sim/orbit.rs", "sim"))
        assertTrue(matchesQuery("src/sim/orbit.rs", "src orbit"))
    }
}
