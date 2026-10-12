package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.scheduler.MonthEntry
import dev.anodex.mobile.scheduler.ScheduleMonth
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.theme.AnodexColors
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * The month: a dot per thing on each day, and the chosen day listed underneath.
 *
 * The computer's month view, cut for a phone. Things that happen every day or every
 * weekday sit once above the grid rather than as a dot on every square, and every
 * row in the list opens its task. Everything here is what the computer worked out
 * (`scheduler:month`); the phone draws, it does not schedule.
 */
@Composable
internal fun MonthCalendar(
    month: ScheduleMonth?,
    chosen: Int,
    onChoose: (Int) -> Unit,
    onMove: (Int) -> Unit,
    onOpenTask: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    if (month == null) {
        Text("Reading the month…", style = type.meta, color = colors.textFaint, modifier = modifier)
        return
    }
    val calendar = Calendar.getInstance()
    val today = if (calendar.get(Calendar.YEAR) == month.year && calendar.get(Calendar.MONTH) == month.month) {
        calendar.get(Calendar.DAY_OF_MONTH)
    } else {
        null
    }
    calendar.set(month.year, month.month, 1)
    val length = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    // Monday first, as the computer's grid is.
    val lead = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val title = java.text.SimpleDateFormat("LLLL yyyy", java.util.Locale.getDefault()).format(calendar.time)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NavButton(AnodexIcon.CHEVRON_LEFT, "Previous month") { onMove(-1) }
            Text(
                title,
                style = type.title,
                color = colors.text,
                modifier = Modifier.weight(1f).padding(horizontal = Spacing.x2),
            )
            NavButton(AnodexIcon.CHEVRON_RIGHT, "Next month") { onMove(1) }
        }

        if (month.every.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                for (entry in month.every) {
                    val time = if (entry.hour != null && entry.minute != null) {
                        " · " + clock(Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, entry.hour); set(Calendar.MINUTE, entry.minute)
                        }.timeInMillis)
                    } else {
                        ""
                    }
                    Text(
                        "${entry.cadence} · ${entry.name}$time",
                        style = type.meta,
                        color = colors.textMuted,
                        modifier = Modifier
                            .clip(Radii.pill)
                            .background(colors.bgSurface2)
                            .padding(horizontal = Spacing.x3, vertical = 4.dp),
                    )
                }
            }
        }

        // The grid: seven columns, Monday first.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row {
                for (day in listOf("M", "T", "W", "T", "F", "S", "S")) {
                    Text(
                        day,
                        style = type.meta,
                        color = colors.textFaint,
                        modifier = Modifier.weight(1f).padding(bottom = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            val cells = (List(lead) { null } + (1..length).toList()).let { list ->
                list + List((7 - list.size % 7) % 7) { null }
            }
            for (week in cells.chunked(7)) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (day in week) {
                        Box(Modifier.weight(1f)) {
                            if (day != null) {
                                DayCell(
                                    day = day,
                                    entries = month.days[day].orEmpty(),
                                    isToday = day == today,
                                    isChosen = day == chosen,
                                    onClick = { onChoose(day) },
                                )
                            }
                        }
                    }
                }
            }
        }

        // The chosen day, everything on it.
        val list = (month.days[chosen].orEmpty() + month.everyByDay[chosen].orEmpty()).sortedBy { it.atEpochMs }
        val dayLabel = DateFormat.getDateInstance(DateFormat.FULL).format(
            Calendar.getInstance().apply { set(month.year, month.month, chosen) }.time,
        )
        Text(
            (if (chosen == today) "Today · " else "") + dayLabel,
            style = type.label,
            color = colors.textMuted,
        )
        if (list.isEmpty()) {
            Text("Nothing scheduled.", style = type.meta, color = colors.textFaint)
        }
        for (entry in list) {
            EntryRow(entry, onClick = onOpenTask?.let { open -> { open(entry.taskId) } })
        }
    }
}

@Composable
private fun NavButton(icon: AnodexIcon, label: String, onClick: () -> Unit) {
    val colors = AnodexTheme.colors
    Box(
        Modifier
            .size(40.dp)
            .clip(Radii.md)
            .border(1.dp, colors.borderStrong, Radii.md)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        AnodexIcon(icon, size = 16.dp, tint = colors.textMuted)
    }
}

@Composable
private fun DayCell(day: Int, entries: List<MonthEntry>, isToday: Boolean, isChosen: Boolean, onClick: () -> Unit) {
    val colors = AnodexTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(shape)
            .then(
                if (isToday) {
                    Modifier.background(
                        Brush.linearGradient(listOf(colors.accentViolet.copy(alpha = 0.55f), colors.accent.copy(alpha = 0.55f))),
                    )
                } else {
                    Modifier
                },
            )
            .then(if (isChosen && !isToday) Modifier.border(1.5.dp, colors.accent, shape) else Modifier)
            .semantics {
                selected = isChosen
                contentDescription = "$day, ${entries.size} scheduled"
            }
            .clickable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            day.toString(),
            style = AnodexTheme.type.body,
            color = if (isToday) Color.White else colors.text,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 2.dp).heightIn(min = 4.dp)) {
            for (entry in entries.take(3)) {
                Box(Modifier.size(4.dp).clip(CircleShape).background(dotColour(entry, colors)))
            }
        }
    }
}

@Composable
private fun EntryRow(entry: MonthEntry, onClick: (() -> Unit)?) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    val (label, ink, ground) = when (entry.state) {
        "success" -> Triple("Done", colors.successInk, colors.successSoft)
        "error", "stopped" -> Triple("Didn't run", colors.dangerInk, colors.dangerSoft)
        else -> Triple("Coming up", colors.accentInk, colors.accentSoft)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.md)
            .background(colors.bgSurface)
            .border(1.dp, colors.border, Radii.md)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(Spacing.x3),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        Text(clock(entry.atEpochMs), style = type.meta, color = colors.textMuted, modifier = Modifier.width(64.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = type.bodyEmphasis, color = colors.text)
            if (entry.repeats.isNotBlank()) Text(entry.repeats, style = type.meta, color = colors.textFaint)
            Text(
                label,
                style = type.badge,
                color = ink,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clip(Radii.pill)
                    .background(ground)
                    .padding(horizontal = Spacing.x2, vertical = 1.dp),
            )
        }
    }
}

private fun dotColour(entry: MonthEntry, colors: AnodexColors): Color = when {
    entry.state == "error" || entry.state == "stopped" -> colors.danger
    entry.reminder -> colors.warn
    else -> colors.accent
}

/** "9:22 PM" or "21:22", as this phone's clock is set. */
private fun clock(epochMs: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMs))
