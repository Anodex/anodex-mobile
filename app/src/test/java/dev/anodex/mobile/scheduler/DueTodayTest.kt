package dev.anodex.mobile.scheduler

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the home screen says about today.
 *
 * The whole value of this line is that it is absent most days. A row that says
 * "nothing scheduled" has spent the most valuable space on the screen reporting that
 * nothing happened, so every case below is really asking the same question: does this
 * earn its row right now.
 */
class DueTodayTest {

    private val zone = ZoneId.of("America/Denver")
    private val now = ZonedDateTime.of(2026, 9, 13, 9, 30, 0, 0, zone).toInstant().toEpochMilli()

    private fun at(hour: Int, minute: Int = 0, day: Int = 13) =
        ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun task(
        name: String,
        next: Long?,
        enabled: Boolean = true,
    ) = ScheduledTask(
        id = name,
        name = name,
        prompt = "",
        enabled = enabled,
        nextRunAt = next,
        lastRunAt = null,
        lastRunStatus = null,
        lastRunSummary = null,
        runCount = 0,
    )

    @Test
    fun `a quiet day says nothing at all`() {
        assertNull(dueTodayLine(dueToday(emptyList(), now, zone), zone))
        assertNull(dueTodayLine(dueToday(listOf(task("Digest", at(9, 0, day = 14))), now, zone), zone))
    }

    @Test
    fun `the next one is named, because a name is worth a glance and a count is not`() {
        val due = dueToday(listOf(task("Morning digest", at(18, 30))), now, zone)

        assertEquals("Morning digest at 6:30 pm", dueTodayLine(due, zone))
    }

    @Test
    fun `a run already past is not today's news`() {
        // It was due at 08:00 and it is 09:30. That is not something to look forward
        // to — it is either done or stuck, and the scheduler screen explains which.
        val due = dueToday(listOf(task("Early", at(8, 0))), now, zone)

        assertTrue(due.isEmpty())
    }

    @Test
    fun `a disabled task is not going to happen`() {
        val due = dueToday(listOf(task("Paused", at(18, 0), enabled = false)), now, zone)

        assertTrue(due.isEmpty())
    }

    @Test
    fun `several today are counted after the first`() {
        val due = dueToday(
            listOf(
                task("Late", at(21, 0)),
                task("Digest", at(18, 0)),
                task("Backup", at(19, 0)),
            ),
            now,
            zone,
        )

        // Soonest first, whatever order they arrived in.
        assertEquals("Digest", due.first().name)
        assertEquals("Digest at 6:00 pm, and 2 more today", dueTodayLine(due, zone))
    }

    @Test
    fun `exactly two reads as one more rather than as a number`() {
        val due = dueToday(listOf(task("Digest", at(18, 0)), task("Backup", at(19, 0))), now, zone)

        assertEquals("Digest at 6:00 pm, and one more today", dueTodayLine(due, zone))
    }

    @Test
    fun `today ends at midnight where the phone is`() {
        // A task at 23:00 is still today. One at 00:30 is tomorrow, even though it is
        // only three hours away — "today" is the word on the screen and it has to mean
        // what a person means by it.
        assertTrue(dueToday(listOf(task("Nightly", at(23, 0))), now, zone).isNotEmpty())
        assertTrue(dueToday(listOf(task("After", at(0, 30, day = 14))), now, zone).isEmpty())
    }

    @Test
    fun `midnight and noon are not both twelve am`() {
        // The classic. 12-hour formatting that maps hour 0 to "0" or hour 12 to "0 pm"
        // is the kind of thing nobody sees until a task is scheduled at exactly one of
        // them.
        val midday = dueToday(listOf(task("Noon", at(12, 0))), now, zone)
        assertEquals("Noon at 12:00 pm", dueTodayLine(midday, zone))

        val lateNight = ZonedDateTime.of(2026, 9, 13, 23, 50, 0, 0, zone).toInstant().toEpochMilli()
        val midnight = dueToday(listOf(task("Sweep", at(23, 59))), lateNight, zone)
        assertEquals("Sweep at 11:59 pm", dueTodayLine(midnight, zone))
    }

    @Test
    fun `a task with no next run is not scheduled`() {
        assertTrue(dueToday(listOf(task("Manual", next = null)), now, zone).isEmpty())
    }
}
