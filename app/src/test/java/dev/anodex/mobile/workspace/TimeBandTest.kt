package dev.anodex.mobile.workspace

import dev.anodex.mobile.ui.screens.timeBandForTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which heading a file lands under.
 *
 * The workspace list doubles as a record of what the computer did while nobody was
 * watching, so the headings are the record. They are computed from elapsed time
 * rather than calendar days on purpose — a phone crossing midnight should not
 * reshuffle the list under somebody reading it.
 */
class TimeBandTest {

    private val now = 1_700_000_000_000L
    private val hour = 60 * 60 * 1000L
    private val day = 24 * hour

    private fun bandAt(ageMs: Long) = timeBandForTest(now - ageMs, now)

    @Test
    fun `something touched an hour ago is today`() {
        assertEquals("Today", bandAt(hour))
    }

    @Test
    fun `the boundaries land where the words say they do`() {
        assertEquals("Today", bandAt(23 * hour))
        assertEquals("Yesterday", bandAt(25 * hour))
        assertEquals("This week", bandAt(3 * day))
        assertEquals("This month", bandAt(10 * day))
        assertEquals("Earlier", bandAt(60 * day))
    }

    @Test
    fun `midnight does not reshuffle the list`() {
        // Elapsed time, not calendar days. A file touched twenty hours ago is
        // "Today" whether the clock has passed midnight since or not — otherwise the
        // list rearranges itself under somebody who is reading it.
        assertEquals("Today", bandAt(20 * hour))
    }

    @Test
    fun `a file with no timestamp is not silently called old`() {
        // Zero is what a missing field parses to. Filing that under "Earlier" would
        // be a claim about when it changed, made from the absence of any claim.
        assertEquals("Undated", timeBandForTest(0L, now))
    }
}
