package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import dev.anodex.mobile.agents.AgentRun
import dev.anodex.mobile.agents.Plan
import dev.anodex.mobile.agents.PlanStep
import dev.anodex.mobile.scheduler.relativeTime
import dev.anodex.mobile.ui.components.PrimaryButton
import dev.anodex.mobile.ui.components.SecondaryButton
import dev.anodex.mobile.ui.components.StatusDot
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing

/**
 * Agent runs, and the plans waiting on a human.
 *
 * The plan-review gate is the reason this screen exists. A run in `needs-review`
 * is doing *nothing* until somebody looks at it, and the person who can unblock it
 * is usually not at the desk — which is most of the argument for carrying the app
 * at all (§8).
 *
 * So a run waiting on an answer is lifted out of the list: it carries the accent
 * edge and is the only kind that shows its plan. Everything else is a card to read
 * at a glance and tap to open.
 *
 * The card is the control. An "Open" button under every row said what the whole
 * card already meant and turned a list into a column of buttons; the actions left
 * are the ones a tap cannot express — approve, reject, stop.
 */
@Composable
fun AgentsScreen(
    runs: List<AgentRun>,
    loading: Boolean,
    busyRunId: String?,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    onStop: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Dismiss, when this is shown over something rather than as its own screen.
     *
     * Null in the app, where the drawer is how you leave and a Close button
     * would be a second, worse answer to the same question.
     */
    onClose: (() -> Unit)? = null,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(modifier = modifier.fillMaxSize().background(colors.bgApp)) {
        Text(
            text = "Agent runs",
            style = type.heading,
            color = colors.text,
            modifier = Modifier.padding(
                start = Spacing.x4,
                end = Spacing.x4,
                top = Spacing.x4,
                bottom = Spacing.x2,
            ),
        )

        when {
            loading && runs.isEmpty() -> Centred("Reading from your computer…", colors.textFaint)

            runs.isEmpty() ->
                Centred("No agent runs. They are started at the computer.", colors.textFaint)

            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(Spacing.x3),
                verticalArrangement = Arrangement.spacedBy(Spacing.x3),
            ) {
                items(runs, key = { it.id }) { run ->
                    RunCard(
                        run = run,
                        busy = run.id == busyRunId,
                        onApprove = { onApprove(run.id) },
                        onReject = { onReject(run.id) },
                        onStop = { onStop(run.id) },
                        onOpen = { onOpenConversation(run.conversationId) },
                    )
                }
            }
        }

        if (onClose != null) {
            SecondaryButton(
                label = "Close",
                onClick = onClose,
                modifier = Modifier.padding(Spacing.x4),
            )
        }
    }
}

@Composable
private fun RunCard(
    run: AgentRun,
    busy: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    val waiting = run.status == AgentRun.Status.NEEDS_REVIEW
    val running = run.status == AgentRun.Status.RUNNING

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.xl)
            .background(colors.bgSurface)
            // Only the blocked one is outlined. A border on every card is a border
            // that says nothing; here it means "this one is waiting on you".
            .then(if (waiting) Modifier.border(1.dp, colors.accent, Radii.xl) else Modifier)
            .clickable(onClick = onOpen)
            .padding(Spacing.x4),
        verticalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            // Ripples only while something is happening or about to: a run that is
            // working, and one holding the whole job up waiting for an answer. A
            // finished run is a fact, not a state worth watching.
            StatusDot(colour = statusColour(run.status), running = running || waiting)

            Text(
                text = statusLabel(run),
                style = type.label,
                color = if (waiting) colors.accent else colors.textMuted,
                modifier = Modifier.weight(1f),
            )

            relativeTime(run.updatedAtEpochMs.takeIf { it > 0 })?.let { ago ->
                Text(ago, style = type.meta, color = colors.textFaint)
            }
        }

        Text(
            // Flattened, because a goal is a pasted prompt and often arrives with
            // its own line breaks. Left alone, a title followed by a stray "…" on a
            // line of its own reads as a rendering fault rather than as text.
            text = oneLine(run.goal),
            style = type.bodyEmphasis,
            color = colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        run.lastError?.let {
            Text(
                text = it,
                style = type.meta,
                color = colors.danger,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        run.summary?.takeIf { run.lastError == null }?.let {
            Text(
                text = it,
                style = type.meta,
                color = colors.textMuted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Only a blocked run shows its plan. Everywhere else it is detail nobody
        // asked for on a screen that exists to unblock things.
        if (waiting && run.plan != null) {
            PlanView(run.plan)
        }

        // Nothing at all for a run that has finished — the card opens it, and a row
        // holding one button under every card was most of what made this a wall.
        when {
            busy -> Text("Working…", style = type.meta, color = colors.textFaint)

            waiting -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                // Reject sits first and carries the calmer weight: approving lets an
                // agent loose on real files, and rejecting only costs a replan.
                SecondaryButton(label = "Reject", onClick = onReject)
                PrimaryButton(label = "Approve", onClick = onApprove)
            }

            running -> SecondaryButton(label = "Stop", onClick = onStop)
        }
    }
}

@Composable
private fun PlanView(plan: Plan) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.lg)
            .background(colors.bgSurface2)
            .padding(Spacing.x3),
        verticalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        Text(plan.title, style = type.label, color = colors.text)

        for ((index, step) in plan.steps.withIndex()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                // Numbered because a plan is a sequence and the order is part of
                // what is being approved — not because a list looks tidier numbered.
                Text("${index + 1}.", style = type.meta, color = colors.textFaint)
                Text(step.title, style = type.meta, color = colors.textMuted)
            }
        }

        if (plan.steps.isEmpty()) {
            Text("No steps listed.", style = type.meta, color = colors.textFaint)
        }
    }
}

@Composable
private fun Centred(text: String, color: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = AnodexTheme.type.body,
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(Spacing.x6),
        )
    }
}

/** A pasted prompt as one line, so a title cannot arrive already broken. */
private fun oneLine(text: String): String = text.replace(Regex("\\s+"), " ").trim()

@Composable
private fun statusColour(status: AgentRun.Status): Color {
    val colors = AnodexTheme.colors
    return when (status) {
        AgentRun.Status.NEEDS_REVIEW -> colors.accent
        AgentRun.Status.RUNNING -> colors.accentCyan
        AgentRun.Status.DONE -> colors.success
        AgentRun.Status.ERROR -> colors.danger
        AgentRun.Status.STOPPED -> colors.textFaint
    }
}

private fun statusLabel(run: AgentRun): String = when (run.status) {
    AgentRun.Status.NEEDS_REVIEW -> "Waiting for you"
    AgentRun.Status.RUNNING ->
        if (run.limitsEnabled) "Running · turn ${run.turnsUsed}/${run.maxTurns}"
        else "Running · turn ${run.turnsUsed}"
    AgentRun.Status.DONE -> "Finished"
    AgentRun.Status.ERROR -> "Failed"
    AgentRun.Status.STOPPED -> "Stopped"
}

@Preview(name = "Agents - dark", showBackground = true, heightDp = 760)
@Composable
private fun PreviewAgents() {
    AnodexTheme(darkTheme = true) {
        AgentsScreen(
            runs = listOf(
                AgentRun(
                    id = "1",
                    goal = "Backfill test coverage for the scheduler's monthly recurrence",
                    status = AgentRun.Status.NEEDS_REVIEW,
                    conversationId = "c1",
                    turnsUsed = 0,
                    maxTurns = 40,
                    limitsEnabled = true,
                    summary = null,
                    lastError = null,
                    plan = Plan(
                        "Plan",
                        listOf(
                            PlanStep("a", "Read the failing cases", "pending"),
                            PlanStep("b", "Add tests for month-end rollover", "pending"),
                            PlanStep("c", "Run the suite", "pending"),
                        ),
                    ),
                    updatedAtEpochMs = System.currentTimeMillis() - 120_000,
                ),
                AgentRun(
                    id = "2",
                    goal = "The `orders` crate in this project has three defects.\n\n…",
                    status = AgentRun.Status.STOPPED,
                    conversationId = "c2",
                    turnsUsed = 25,
                    maxTurns = 40,
                    limitsEnabled = true,
                    summary = null,
                    lastError = "Stopped after 25 context recoveries without completing a " +
                        "single plan step.",
                    plan = null,
                    updatedAtEpochMs = System.currentTimeMillis() - 3_600_000,
                ),
                AgentRun(
                    id = "3",
                    goal = "The file `parser.py` has three failing checks in `test_parser.py`.",
                    status = AgentRun.Status.DONE,
                    conversationId = "c3",
                    turnsUsed = 12,
                    maxTurns = 40,
                    limitsEnabled = true,
                    summary = null,
                    lastError = null,
                    plan = null,
                    updatedAtEpochMs = System.currentTimeMillis() - 86_400_000,
                ),
            ),
            loading = false,
            busyRunId = null,
            onApprove = {},
            onReject = {},
            onStop = {},
            onOpenConversation = {},
        )
    }
}
