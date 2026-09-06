package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.background
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
import dev.anodex.mobile.agents.AgentRun
import dev.anodex.mobile.agents.Plan
import dev.anodex.mobile.agents.PlanStep
import dev.anodex.mobile.ui.components.PrimaryButton
import dev.anodex.mobile.ui.components.SecondaryButton
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
 * So runs waiting on an answer sort to the top and are the only ones that show
 * their plan expanded. Everything else is a status line: from a phone the useful
 * verbs are unblock and stop, not inspect.
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
     * Dismiss, when this is shown over something rather than as its own tab.
     *
     * Null in the app, where the tab bar is how you leave and a Close button
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
            modifier = Modifier.padding(Spacing.x4),
        )

        when {
            loading && runs.isEmpty() -> Centred("Reading from your computer…", colors.textFaint)
            runs.isEmpty() -> Centred("No agent runs. They are started at the computer.", colors.textFaint)
            else -> LazyColumn(Modifier.weight(1f)) {
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
            SecondaryButton(label = "Close", onClick = onClose, modifier = Modifier.padding(Spacing.x4))
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.x4, vertical = Spacing.x2)
            .clip(Radii.lg)
            .background(if (waiting) colors.bgSurface else colors.bgApp)
            .padding(Spacing.x4),
        verticalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(statusColour(run.status)))
            Text(
                text = statusLabel(run),
                style = type.meta,
                color = colors.textFaint,
            )
        }

        Text(
            text = run.goal,
            style = type.bodyEmphasis,
            color = colors.text,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )

        run.lastError?.let {
            Text(it, style = type.meta, color = colors.danger, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        run.summary?.takeIf { run.lastError == null }?.let {
            Text(it, style = type.meta, color = colors.textMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }

        // Only a run that is blocked shows its plan. Everywhere else it would be
        // detail nobody asked for on a screen that exists to unblock things.
        if (waiting && run.plan != null) {
            PlanView(run.plan)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            when {
                busy -> Text("Working…", style = type.meta, color = colors.textFaint)

                waiting -> {
                    // Reject sits first and is the calmer weight: approving lets an
                    // agent loose on real files, and rejecting costs a replan.
                    SecondaryButton(label = "Reject", onClick = onReject)
                    PrimaryButton(label = "Approve", onClick = onApprove)
                }

                run.status == AgentRun.Status.RUNNING -> {
                    SecondaryButton(label = "Stop", onClick = onStop)
                    SecondaryButton(label = "Open", onClick = onOpen)
                }

                else -> SecondaryButton(label = "Open", onClick = onOpen)
            }
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
            .clip(Radii.md)
            .background(colors.bgSurface2)
            .padding(Spacing.x3),
        verticalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        Text(plan.title, style = type.label, color = colors.text)
        for ((index, step) in plan.steps.withIndex()) {
            Text(
                text = "${index + 1}. ${step.title}",
                style = type.meta,
                color = colors.textMuted,
            )
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

@Composable
private fun statusColour(status: AgentRun.Status): Color {
    val colors = AnodexTheme.colors
    return when (status) {
        AgentRun.Status.NEEDS_REVIEW -> colors.warn
        AgentRun.Status.RUNNING -> colors.accent
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
                    updatedAtEpochMs = 0,
                ),
                AgentRun(
                    id = "2",
                    goal = "Port MessageBubble proportions to Compose",
                    status = AgentRun.Status.RUNNING,
                    conversationId = "c2",
                    turnsUsed = 7,
                    maxTurns = 40,
                    limitsEnabled = true,
                    summary = null,
                    lastError = null,
                    plan = null,
                    updatedAtEpochMs = 0,
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
