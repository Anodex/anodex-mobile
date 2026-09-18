package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.scheduler.ScheduledTask
import dev.anodex.mobile.scheduler.TaskRun
import dev.anodex.mobile.scheduler.formatDuration
import dev.anodex.mobile.scheduler.relativeTime
import dev.anodex.mobile.scheduler.timeAgo
import dev.anodex.mobile.ui.components.AnodexCard
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.components.InlineProblem
import dev.anodex.mobile.ui.components.ScreenScaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.anodex.mobile.ui.components.SecondaryButton
import dev.anodex.mobile.ui.components.StatusDot
import dev.anodex.mobile.ui.components.fadingEdges
import dev.anodex.mobile.ui.components.listPadding
import dev.anodex.mobile.ui.theme.AnodexColors
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Spacing

/**
 * One task, and what it has actually done.
 *
 * The list answers "is anything scheduled". This answers the question somebody
 * actually picks up a phone for: *did the 6am run go through, and what did it say*.
 * All of it — the summary, how long the run took, how late it started — was already
 * stored on the computer and already crossing the wire. None of it had anywhere to
 * be shown.
 *
 * Running on demand sits at the bottom behind a full-width row rather than an icon
 * in the header. It starts work on a machine nobody is watching, which is not a
 * thing to put under a thumb reaching for the back arrow.
 */
@Composable
fun TaskScreen(
    task: ScheduledTask,
    modifier: Modifier = Modifier,
    onRunNow: (() -> Unit)? = null,
    /** Set while a run started from here is still going. */
    running: Boolean = false,
    /** Why the last attempt to run it did not work. */
    error: String? = null,
    /** Leave it in place, stopped. Null hides the control. */
    onSetEnabled: ((Boolean) -> Unit)? = null,
    /** Remove it for good. */
    onDelete: (() -> Unit)? = null,
    /** Back to the list. This screen had no way out but the system gesture. */
    onClose: (() -> Unit)? = null,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    var confirmingDelete by rememberSaveable(task.id) { mutableStateOf(false) }

    ScreenScaffold(
        title = task.name,
        modifier = modifier,
        subtitle = timingSentence(task),
        // A way out that is visible. This screen was reachable by tapping a task
        // and leavable only by the system back gesture, which is invisible and is
        // not the same thing as an affordance -- on a screen whose one button used
        // to be "Run it now", the absence of a Back was a trap.
        leading = onClose?.let { { SecondaryButton(label = "Back", onClick = it) } },
        trailing = { StatusDot(colour = colors.accentInk, running = running) },
    ) { topInset ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .fadingEdges(topInset, 0.dp),
            contentPadding = listPadding(topInset),
            verticalArrangement = Arrangement.spacedBy(Spacing.x3),
        ) {
            item(key = "prompt") {
                AnodexCard {
                    Text("WHAT IT ASKS FOR", style = type.badge, color = colors.textFaint)
                    // In full. The list truncates this to one line, and reading the part
                    // the list could not show is most of why anyone opens a task.
                    Text(task.prompt, style = type.body, color = colors.textMuted)
                }
            }

            if (error != null) {
                item(key = "error") { InlineProblem(error) }
            }

            item(key = "runs-label") {
                Text(
                    text = if (task.runs.isEmpty()) "NO RUNS YET" else "RUNS",
                    style = type.badge,
                    color = colors.textFaint,
                    modifier = Modifier.padding(top = Spacing.x2),
                )
            }

            items(task.runs, key = { it.id }) { run -> RunRow(run) }

            // The computer keeps only the recent ones. Saying so stops a task that has
            // been running for months from looking like it ran four times.
            if (task.runCount > task.runs.size) {
                item(key = "older") {
                    Text(
                        text = "${task.runCount} runs in total. Your computer keeps the most " +
                            "recent ones.",
                        style = type.meta,
                        color = colors.textFaint,
                        modifier = Modifier.padding(horizontal = Spacing.x1),
                    )
                }
            }

            if (onRunNow != null) {
                item(key = "run-now") {
                    AnodexCard(
                        onClick = onRunNow,
                        enabled = !running,
                        verticalArrangement = Arrangement.spacedBy(Spacing.x1),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
                        ) {
                            AnodexIcon(AnodexIcon.CLOCK, size = 18.dp, tint = colors.accentInk)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = if (running) "Running…" else "Run it now",
                                    style = type.bodyEmphasis,
                                    color = if (running) colors.textMuted else colors.accentInk,
                                )
                                Text(
                                    text = "Starts on your computer immediately, whatever the " +
                                        "schedule says.",
                                    style = type.meta,
                                    color = colors.textFaint,
                                )
                            }
                        }
                    }
                }
            }

            // Below "Run it now", deliberately. The destructive pair sits at the
            // bottom of the screen rather than beside the thing somebody opened
            // this to do -- a blind tap near the top of this screen once started a
            // job on somebody's computer, and the two must not be neighbours.
            if (onSetEnabled != null) {
                item(key = "pause") {
                    AnodexCard(onClick = { onSetEnabled(!task.enabled) }) {
                        Text(
                            text = if (task.enabled) "Pause it" else "Start it again",
                            style = type.bodyEmphasis,
                            color = colors.text,
                        )
                        Text(
                            // The useful middle. A daily job you do not want this
                            // week is not a job you want to write again next week.
                            text = if (task.enabled) {
                                "Keeps the task and its history, and stops it running."
                            } else {
                                "Paused. It will not run until you start it again."
                            },
                            style = type.meta,
                            color = colors.textFaint,
                        )
                    }
                }
            }

            if (onDelete != null) {
                item(key = "delete") {
                    AnodexCard(
                        onClick = { if (confirmingDelete) onDelete() else confirmingDelete = true },
                    ) {
                        Text(
                            text = if (confirmingDelete) "Tap again to delete" else "Delete it",
                            style = type.bodyEmphasis,
                            color = colors.dangerInk,
                        )
                        Text(
                            text = if (confirmingDelete) {
                                "This cannot be undone from here."
                            } else {
                                "Removes the task and its run history from your computer."
                            },
                            style = type.meta,
                            color = colors.textFaint,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RunRow(run: TaskRun) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    val outcome = runColour(run.status, colors)

    AnodexCard(
        padding = PaddingValues(Spacing.x3),
        verticalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            StatusDot(colour = outcome)

            Text(
                text = outcomeWord(run.status),
                style = type.label,
                color = outcome,
                modifier = Modifier.weight(1f),
            )

            Text(
                text = listOfNotNull(
                    formatDuration(run.durationMs),
                    timeAgo(run.startedAtEpochMs),
                ).joinToString(" · "),
                style = type.meta,
                color = colors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        run.summary?.takeIf { it.isNotBlank() }?.let { summary ->
            Text(summary, style = type.body, color = colors.textMuted)
        }

        // Only when it is worth remarking on. Every run is a little late, and saying
        // so every time trains the reader to stop reading it.
        if (run.delayedMs >= LATE_ENOUGH_MS) {
            Text(
                text = "Started ${formatDuration(run.delayedMs)} after its slot — your " +
                    "computer was probably asleep.",
                style = type.meta,
                color = colors.warnInk,
            )
        }
    }
}

/** The computer's own word, turned into something readable at a glance. */
private fun outcomeWord(status: String?): String = when (status) {
    "success" -> "Succeeded"
    "failed" -> "Failed"
    "skipped" -> "Skipped"
    null -> "Ran"
    // A status this build has not heard of is still worth showing as itself, rather
    // than flattened into "Ran" and quietly losing what the computer said.
    else -> status.replaceFirstChar { it.uppercase() }
}

private fun runColour(status: String?, colors: AnodexColors): Color = when (status) {
    "success" -> colors.successInk
    "failed" -> colors.dangerInk
    "skipped" -> colors.warnInk
    else -> colors.textMuted
}

/** What happens next, as a sentence rather than a timestamp to subtract from. */
private fun timingSentence(task: ScheduledTask): String {
    if (!task.enabled) return "Paused. It will not run until it is switched on at the computer."
    val next = relativeTime(task.nextRunAt)
    return if (next != null) "Runs $next" else "No further runs scheduled"
}

/**
 * Five minutes.
 *
 * Below this, lateness is the ordinary cost of a scheduler that wakes on an
 * interval. Above it something kept the computer from running the task, and that is
 * the part worth saying out loud.
 */
private const val LATE_ENOUGH_MS = 5 * 60 * 1000L

@Preview(name = "Task with a run", showBackground = true, heightDp = 700)
@Composable
private fun TaskPreview() {
    AnodexTheme {
        TaskScreen(
            task = ScheduledTask(
                id = "task_1",
                name = "Reminder: meeting with James",
                prompt = "Remind the user: You have a meeting with James at 9:00 AM.",
                enabled = true,
                nextRunAt = null,
                lastRunAt = System.currentTimeMillis() - 86_400_000,
                lastRunStatus = "success",
                lastRunSummary = "Meeting with James today at 9:00 AM MDT",
                runCount = 1,
                runs = listOf(
                    TaskRun(
                        id = "run_1",
                        startedAtEpochMs = System.currentTimeMillis() - 86_400_000,
                        durationMs = 19_413,
                        status = "success",
                        summary = "Meeting with James today at 9:00 AM MDT regarding the " +
                            "Anodex verify-loop test",
                        delayedMs = 20_087,
                    ),
                ),
            ),
            onRunNow = {},
        )
    }
}
