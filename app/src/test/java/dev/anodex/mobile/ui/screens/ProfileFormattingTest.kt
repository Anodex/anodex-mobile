package dev.anodex.mobile.ui.screens

import dev.anodex.mobile.profile.DayOfUse
import dev.anodex.mobile.profile.UsageProfile
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The arithmetic behind the Profile screen.
 *
 * Pulled out and tested because two of these are the kind of thing that looks right
 * on the machine it was written on and is wrong for somebody else: an hour that is
 * midnight, and a month with a gap in it.
 */
class ProfileFormattingTest {

    @Test
    fun `a lifetime token count is readable at a glance`() {
        // The exact digit is never the point on a phone. 2,418,773 is a number to be
        // counted; 2.4M is an answer.
        assertEquals("2.4M", compactCount(2_418_773))
        assertEquals("1.2K", compactCount(1_234))
        assertEquals("999", compactCount(999))
        assertEquals("0", compactCount(0))
    }

    @Test
    fun `a round number does not carry a pointless decimal`() {
        assertEquals("1K", compactCount(1_000))
        assertEquals("5M", compactCount(5_000_000))
    }

    @Test
    fun `midnight is midnight, not zero`() {
        // Hour 0 is the case that breaks if the parse defaults a missing hour to 0,
        // and the one a developer working in the afternoon never sees.
        assertEquals("12 AM", hour(0))
        assertEquals("12 PM", hour(12))
        assertEquals("9 AM", hour(9))
        assertEquals("11 PM", hour(23))
    }

    @Test
    fun `a duration reads at the coarsest unit that still says something`() {
        assertEquals("8s", duration(8_400))
        assertEquals("2m 5s", duration(125_000))
        assertEquals("1h 2m", duration(3_720_000))
    }

    @Test
    fun `days with no activity are drawn as gaps, not skipped`() {
        // The computer stores only days that had activity. Plotting that list as-is
        // draws a fortnight off work as an unbroken line, which is the opposite of
        // what the chart is for.
        val today = LocalDate.of(2026, 9, 12)
        val usage = profileWith(
            DayOfUse("2026-09-12", 500, 2),
            DayOfUse("2026-09-08", 100, 1),
        )

        val week = usage.recentDays(5, today)

        assertEquals(5, week.size)
        assertEquals(listOf("2026-09-08", "2026-09-09", "2026-09-10", "2026-09-11", "2026-09-12"),
            week.map { it.date })
        assertEquals(listOf(100L, 0L, 0L, 0L, 500L), week.map { it.tokens })
    }

    @Test
    fun `a profile with no activity at all is still the right length`() {
        val week = profileWith().recentDays(7, LocalDate.of(2026, 9, 12))

        assertEquals(7, week.size)
        assertEquals(0L, week.sumOf { it.tokens })
    }

    @Test
    fun `nothing recorded is distinguishable from something recorded`() {
        assertEquals(true, profileWith().isEmpty)
        assertEquals(false, profileWith(DayOfUse("2026-09-12", 1, 1)).isEmpty)
    }

    private fun profileWith(vararg days: DayOfUse) = UsageProfile(
        lifetimeTokens = days.sumOf { it.tokens },
        lifetimeGenerations = days.sumOf { it.generations },
        sessionCount = 0,
        currentStreakDays = 0,
        longestStreakDays = 0,
        peakDay = null,
        peakHour = null,
        longestGenerationMs = 0,
        favouriteModelName = null,
        dailyActivity = days.toList(),
        mostUsedTools = emptyList(),
    )
}
