package dev.anodex.mobile.scheduler

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the computer is going to do today, for the line on the home screen.
 *
 * The scheduler screen answers "what is scheduled". This answers a narrower and more
 * useful question: is anything happening *today* that somebody opening the app should
 * know about. Most days the answer is nothing, and on those days the line is absent
 * rather than empty — a home screen that says "0 tasks today" has spent a row to say
 * nothing happened.
 *
 * Today is the phone's today. A task due at 23:00 is today until midnight here, not
 * until midnight wherever the computer is, because the person reading the line is
 * holding the phone.
 */
fun dueToday(tasks: List<ScheduledTask>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<ScheduledTask> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

    return tasks
        .filter { it.enabled }
        .filter { task ->
            val next = task.nextRunAt ?: return@filter false
            // Still to come, and before midnight. A run whose time has passed but
            // which has not fired is not "today" in any sense worth reporting — it is
            // a problem, and the scheduler screen is where that is explained.
            next >= now && Instant.ofEpochMilli(next).atZone(zone).toLocalDate() == today
        }
        .sortedBy { it.nextRunAt }
}

/**
 * That list as a sentence.
 *
 * Names the task when there is one, because "Digest at 18:00" is worth glancing at
 * and "1 task today" is not. Past one it counts, because three names do not fit on a
 * phone and the next one is the only one that matters before the others arrive.
 */
fun dueTodayLine(due: List<ScheduledTask>, zone: ZoneId = ZoneId.systemDefault()): String? {
    val next = due.firstOrNull() ?: return null
    val at = Instant.ofEpochMilli(next.nextRunAt ?: return null)
        .atZone(zone)
        .toLocalTime()
    val time = "%d:%02d".format(
        if (at.hour % 12 == 0) 12 else at.hour % 12,
        at.minute,
    ) + if (at.hour < 12) " am" else " pm"

    return when (due.size) {
        1 -> "${next.name} at $time"
        2 -> "${next.name} at $time, and one more today"
        else -> "${next.name} at $time, and ${due.size - 1} more today"
    }
}

/** Whether [now] falls on [date] in this zone. Exposed for the tests to pin. */
internal fun isSameDay(now: Long, date: LocalDate, zone: ZoneId): Boolean =
    Instant.ofEpochMilli(now).atZone(zone).toLocalDate() == date
