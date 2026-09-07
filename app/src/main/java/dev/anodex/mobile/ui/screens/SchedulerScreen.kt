package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.scheduler.ScheduledTask
import dev.anodex.mobile.scheduler.relativeTime
import dev.anodex.mobile.ui.components.StatusDot
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing

/**
 * What the computer runs on its own, and whether it worked.
 *
 * The reason to open this from a phone is one question: *did the overnight run go
 * through, and what did it say*. So each task leads with its outcome and its timing,
 * and the prompt — the long part — sits underneath in one line.
 *
 * Read-only for now. Creating a task means choosing a recurrence and a tool set,
 * which is an editor rather than a screen, and running one on demand starts work on
 * a machine nobody is watching. Neither channel is denied, so this is a decision
 * about what to build rather than what is permitted.
 */
@Composable
fun SchedulerScreen(
    tasks: List<ScheduledTask>,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(modifier.fillMaxSize().background(colors.bgApp)) {
        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.x2),
                    modifier = Modifier.padding(Spacing.x6),
                ) {
                    Text(
                        text = if (loading) "Asking your computer…" else "Nothing scheduled",
                        style = type.bodyEmphasis,
                        color = colors.textMuted,
                        textAlign = TextAlign.Center,
                    )
                    if (!loading) {
                        Text(
                            text = "Tasks are created at the computer.",
                            style = type.meta,
                            color = colors.textFaint,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(Spacing.x3),
            verticalArrangement = Arrangement.spacedBy(Spacing.x3),
        ) {
            items(tasks, key = { it.id }) { task -> TaskCard(task) }
        }
    }
}

@Composable
private fun TaskCard(task: ScheduledTask) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.xl)
            .background(colors.bgSurface)
            .padding(Spacing.x4),
        verticalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            // The outcome, before the name: what somebody opened this to find out.
            // A task due within the hour ripples, because it is the one whose state
            // is about to change while they are looking at it.
            StatusDot(
                colour = statusColour(task, colors),
                running = task.enabled && isDueSoon(task.nextRunAt),
            )

            Text(
                text = task.name,
                style = type.bodyEmphasis,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (!task.enabled) {
                Text("Paused", style = type.badge, color = colors.textFaint)
            }
        }

        Text(
            text = timingLine(task),
            style = type.meta,
            color = colors.textMuted,
        )

        // Whatever the last run had to say for itself, when it said anything. This is
        // the payload — the rest of the card is context for it.
        task.lastRunSummary?.takeIf { it.isNotBlank() }?.let { summary ->
            Text(
                text = summary,
                style = type.meta,
                color = colors.textFaint,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (task.prompt.isNotBlank()) {
            Text(
                text = task.prompt,
                style = type.meta,
                color = colors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Within the hour, so its state is about to change while somebody watches. */
private fun isDueSoon(nextRunAt: Long?): Boolean {
    val next = nextRunAt ?: return false
    return (next - System.currentTimeMillis()) in 0..(60 * 60 * 1000L)
}

/**
 * When it last ran and when it runs next, in one line.
 *
 * Relative throughout: the useful question from a phone is how long until or how
 * long since, and an absolute time makes the reader do the subtraction — across a
 * timezone, if they are away from home.
 */
private fun timingLine(task: ScheduledTask): String {
    val last = relativeTime(task.lastRunAt)?.let { "Ran $it" }
    val next = relativeTime(task.nextRunAt)?.let { "next $it" }

    return listOfNotNull(last, next)
        .joinToString(" · ")
        .ifBlank { if (task.enabled) "Not run yet" else "Paused, with nothing scheduled" }
}

/**
 * Green for a clean run, red for a failed one, and nothing asserted otherwise.
 *
 * A task that has never run is not "fine" and is not "broken", so it gets the quiet
 * colour rather than the reassuring one.
 */
private fun statusColour(task: ScheduledTask, colors: dev.anodex.mobile.ui.theme.AnodexColors): Color =
    when {
        !task.enabled -> colors.textFaint
        task.lastRunStatus == null -> colors.textFaint
        task.lastRunStatus.equals("ok", ignoreCase = true) ||
            task.lastRunStatus.equals("success", ignoreCase = true) -> colors.success
        task.lastRunStatus.equals("skipped", ignoreCase = true) -> colors.warn
        else -> colors.danger
    }

@Preview(name = "Scheduler", showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun PreviewScheduler() {
    val now = System.currentTimeMillis()
    AnodexTheme(darkTheme = true) {
        SchedulerScreen(
            tasks = listOf(
                ScheduledTask(
                    id = "1", name = "Morning inbox sweep",
                    prompt = "Summarise anything that arrived overnight and needs an answer.",
                    enabled = true,
                    nextRunAt = now + 9 * 3_600_000, lastRunAt = now - 3 * 3_600_000,
                    lastRunStatus = "ok",
                    lastRunSummary = "Four threads, one needs a reply from you — the invoice query.",
                    runCount = 47,
                ),
                ScheduledTask(
                    id = "2", name = "Nightly test run",
                    prompt = "Run the suite and tell me what broke.",
                    enabled = true,
                    nextRunAt = now + 20 * 3_600_000, lastRunAt = now - 26 * 3_600_000,
                    lastRunStatus = "failed",
                    lastRunSummary = "Two failures in the scheduler tests, both monthly recurrence.",
                    runCount = 12,
                ),
                ScheduledTask(
                    id = "3", name = "Weekly backup check",
                    prompt = "Verify the last backup is readable.",
                    enabled = false,
                    nextRunAt = null, lastRunAt = null,
                    lastRunStatus = null, lastRunSummary = null, runCount = 0,
                ),
            ),
            loading = false,
        )
    }
}
