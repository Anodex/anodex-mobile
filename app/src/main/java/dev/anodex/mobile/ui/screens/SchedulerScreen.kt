package dev.anodex.mobile.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.scheduler.ParsedWhen
import dev.anodex.mobile.scheduler.ScheduledTask
import dev.anodex.mobile.scheduler.relativeTime
import dev.anodex.mobile.ui.components.AnodexCard
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.components.EmptyState
import dev.anodex.mobile.ui.components.EmptyTone
import dev.anodex.mobile.ui.components.InlineProblem
import dev.anodex.mobile.ui.components.ListSkeleton
import dev.anodex.mobile.ui.components.ScreenScaffold
import dev.anodex.mobile.ui.components.StatusDot
import dev.anodex.mobile.ui.components.fadingEdges
import dev.anodex.mobile.ui.components.listPadding
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Motion
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch

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
    /** Why the list is empty, when the reason is not "nothing is scheduled". */
    error: String? = null,
    /** Open one task to read its run log. */
    onOpenTask: ((String) -> Unit)? = null,
    /** Ask the computer what a typed phrase means. Called as it is typed. */
    onDraftChanged: (String) -> Unit = {},
    /** What the computer made of it, or null while it has made nothing. */
    parsed: ParsedWhen? = null,
    /** Create the task. Null hides the composer entirely. */
    onCreate: ((String) -> Unit)? = null,
    creating: Boolean = false,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    var draft by rememberSaveable { mutableStateOf("") }

    ScreenScaffold(
        title = "Scheduled",
        modifier = modifier,
        // Where the work happens, said once. A task that only runs while the phone is
        // awake would be a different promise, and people assume the weaker one.
        subtitle = "Runs on your computer, whether or not this is open.",
    ) { topInset ->
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when {
                    loading && tasks.isEmpty() -> ListSkeleton(
                        rows = 3,
                        caption = "Asking your computer…",
                        modifier = Modifier.padding(listPadding(topInset)),
                    )

                    // Nothing to show and nothing to offer: the only case where the
                    // whole screen is the message.
                    tasks.isEmpty() && onCreate == null -> EmptyState(
                        headline = if (error != null) "Could not read your tasks" else "Nothing scheduled",
                        // The reason, when there is one. An empty list and a failed
                        // read looked identical before, so a broken feature was
                        // indistinguishable from a working one with nothing to show.
                        detail = error ?: "Tasks are created at the computer.",
                        tone = if (error != null) EmptyTone.PROBLEM else EmptyTone.QUIET,
                        icon = AnodexIcon.CLOCK,
                        modifier = Modifier.padding(top = topInset),
                    )

                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .fadingEdges(topInset, 0.dp),
                        contentPadding = listPadding(topInset),
                        verticalArrangement = Arrangement.spacedBy(Spacing.x3),
                    ) {
                        // Said here rather than swallowed. This screen has two
                        // sources — the tasks it reads and the starters it offers —
                        // and having something to draw is not evidence the read
                        // worked. Before this, a failed read rendered as a page of
                        // suggestions with no sign anything had gone wrong.
                        if (error != null) {
                            item(key = "read-failed") { InlineProblem(error) }
                        }

                        items(tasks, key = { it.id }) { task ->
                            TaskCard(task, onClick = onOpenTask?.let { open -> { open(task.id) } })
                        }

                        // Offered under whatever is already there, so the screen is never a dead
                        // end — and phrased as things this computer can actually do. Cards
                        // promising what Anodex has no way to carry out would be the worst
                        // version of this screen: an invitation that fails after you accept it.
                        if (onCreate != null) {
                            item(key = "starters-label") {
                                Text(
                                    text = if (tasks.isEmpty()) "START SOMETHING" else "OR START SOMETHING",
                                    style = type.badge,
                                    color = colors.textFaint,
                                    modifier = Modifier.padding(top = Spacing.x2),
                                )
                            }

                            items(STARTERS, key = { it.phrase }) { starter ->
                                StarterCard(starter) {
                                    draft = starter.phrase
                                    onDraftChanged(starter.phrase)
                                }
                            }
                        }
                    }
                }
            }

            if (onCreate != null) {
                Composer(
                    draft = draft,
                    parsed = parsed,
                    creating = creating,
                    onDraftChanged = {
                        draft = it
                        onDraftChanged(it)
                    },
                    onSend = {
                        onCreate(draft)
                        draft = ""
                    },
                )
            }
        }
    }
}

/**
 * Say what you want, and when.
 *
 * One field rather than a form. The computer reads the timing out of the sentence
 * and says so above the input — before anything is created, which is the point: a
 * task that runs at the wrong hour does not fail, it simply happens at the wrong
 * hour, and nothing tells you.
 */
@Composable
private fun Composer(
    draft: String,
    parsed: ParsedWhen?,
    creating: Boolean,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    val ready = parsed != null && draft.isNotBlank() && !creating

    // The edge says whether this is ready to go, exactly as the chat composer's pill
    // does. Two fields on two screens that both send a sentence to the computer
    // should not disagree about how they say they are ready.
    val edge by animateColorAsState(
        targetValue = if (ready) colors.accent else colors.border,
        animationSpec = Motion.fast(),
        label = "composerEdge",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.x3)
            .clip(Radii.xl)
            .background(colors.bgInput)
            .border(1.dp, edge, Radii.xl)
            .padding(Spacing.x3),
        verticalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        BasicTextField(
            value = draft,
            onValueChange = onDraftChanged,
            textStyle = type.body.copy(color = colors.text),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (draft.isEmpty()) {
                    Text(
                        text = "Every weekday at 7am, sweep my mail",
                        style = type.body,
                        color = colors.textFaint,
                    )
                }
                inner()
            },
        )

        // The whole reason this screen can be trusted: what the computer understood,
        // shown while it can still be corrected.
        if (parsed != null) {
            Text(
                text = listOfNotNull(parsed.label, parsed.note).joinToString(" · "),
                style = type.meta,
                color = colors.success,
            )
        } else if (draft.isNotBlank()) {
            Text(
                text = "No timing yet — try “every weekday at 7am” or “in 2 hours”.",
                style = type.meta,
                color = colors.textFaint,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (creating) "Creating…" else "Runs on your computer",
                style = type.meta,
                color = colors.textFaint,
                modifier = Modifier.weight(1f),
            )

            Box(
                modifier = Modifier
                    .size(Touch.minTarget)
                    .clip(Radii.pill)
                    .background(if (ready) colors.accent else colors.bgElevated)
                    .clickable(enabled = ready, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                AnodexIcon(
                    AnodexIcon.SEND,
                    size = 16.dp,
                    tint = if (ready) colors.textOnAccent else colors.textFaint,
                )
            }
        }
    }
}

/** A task worth offering, and the words that make it. */
private data class Starter(val icon: AnodexIcon, val title: String, val phrase: String)

/**
 * Things this computer can actually do.
 *
 * Each one is the sentence the composer would receive, so tapping a card and typing
 * it by hand take the same path — there is no second way to make a task that could
 * behave differently from the one people use.
 */
private val STARTERS = listOf(
    Starter(
        icon = AnodexIcon.MAIL,
        title = "Morning mail sweep",
        phrase = "Every weekday at 7am, read my overnight mail and tell me what needs a reply",
    ),
    Starter(
        icon = AnodexIcon.CLOCK,
        title = "End of day check",
        phrase = "Every weekday at 5pm, remind me of anything I said I would follow up on",
    ),
    Starter(
        icon = AnodexIcon.FOLDER,
        title = "Friday digest",
        phrase = "Every Friday at 4pm, summarise what changed in my project this week",
    ),
)

@Composable
private fun StarterCard(starter: Starter, onClick: () -> Unit) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    AnodexCard(
        // Outlined rather than filled, because it is not a task yet. A live task
        // and an offer that looks identical is the fastest way to make somebody
        // believe something is scheduled when nothing is.
        fill = false,
        edge = colors.borderStrong,
        onClick = onClick,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            AnodexIcon(starter.icon, size = 16.dp, tint = colors.accent)
            Text(
                text = starter.title,
                style = type.bodyEmphasis,
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
        }

        Text(starter.phrase, style = type.meta, color = colors.textFaint)
    }
}

@Composable
private fun TaskCard(task: ScheduledTask, onClick: (() -> Unit)? = null) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    AnodexCard(onClick = onClick) {
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
