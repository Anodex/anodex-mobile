package dev.anodex.mobile.ui.screens

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.agents.AgentRun
import dev.anodex.mobile.agents.Plan
import dev.anodex.mobile.agents.PlanStep
import dev.anodex.mobile.agents.providerVendor
import dev.anodex.mobile.chat.withoutMarkdown
import dev.anodex.mobile.scheduler.relativeTime
import dev.anodex.mobile.ui.components.AnodexCard
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.components.EmptyState
import dev.anodex.mobile.ui.components.EmptyTone
import dev.anodex.mobile.ui.components.InlineProblem
import dev.anodex.mobile.ui.components.ListSkeleton
import dev.anodex.mobile.ui.components.PrimaryButton
import dev.anodex.mobile.ui.components.ScreenScaffold
import dev.anodex.mobile.ui.components.SecondaryButton
import dev.anodex.mobile.ui.components.fadingEdges
import dev.anodex.mobile.ui.components.listPadding
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.LocalReducedMotion
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch
import kotlinx.coroutines.delay

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
    /** Set a run going. Null hides the composer — there is nothing to start against. */
    onStart: ((String, Boolean) -> Unit)? = null,
    starting: Boolean = false,
    /** The project a run would work in, named so nobody starts one in the wrong place. */
    projectName: String? = null,
    error: String? = null,
    /** Project id to name, so each run is labelled with where it works. */
    projectNames: Map<String, String> = emptyMap(),
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    var goal by rememberSaveable { mutableStateOf("") }
    var lookOnly by rememberSaveable { mutableStateOf(false) }

    // The composer folds behind a button once there are runs to read. Open all the
    // time it took the bottom fifth of the screen on a page people mostly open to
    // check on something, and put a sample goal in front of every run they came for.
    // With nothing to read yet it stays open: starting one is the only thing to do.
    var composing by rememberSaveable { mutableStateOf(false) }
    val composerOpen = composing || goal.isNotEmpty() || runs.isEmpty()

    ScreenScaffold(
        title = "Agent runs",
        modifier = modifier,
        subtitle = projectName?.let { "Working in $it" },
    ) { topInset ->
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when {
                    loading && runs.isEmpty() -> ListSkeleton(
                        rows = 3,
                        caption = "Reading from your computer…",
                        modifier = Modifier.padding(listPadding(topInset)),
                    )

                    runs.isEmpty() -> EmptyState(
                        headline = if (error != null) "Could not read your runs" else "Nothing running",
                        detail = error ?: if (onStart != null) {
                            "Describe a job below and your computer will plan it first."
                        } else {
                            "Runs are started at the computer."
                        },
                        tone = if (error != null) EmptyTone.PROBLEM else EmptyTone.QUIET,
                        icon = AnodexIcon.BOT,
                        modifier = Modifier.padding(top = topInset),
                    )

                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .fadingEdges(topInset, 0.dp),
                        contentPadding = listPadding(topInset),
                        verticalArrangement = Arrangement.spacedBy(Spacing.x3),
                    ) {
                        // Said above the runs rather than instead of them. A failed
                        // refresh with a list already on screen is exactly the case
                        // the old placement could not cover: it drew the error, then
                        // drew the stale runs underneath with nothing marking them
                        // as stale.
                        if (error != null) {
                            item(key = "read-failed") { InlineProblem(error) }
                        }

                        items(runs, key = { it.id }) { run ->
                            RunCard(
                                run = run,
                                busy = run.id == busyRunId,
                                onApprove = { onApprove(run.id) },
                                onReject = { onReject(run.id) },
                                onStop = { onStop(run.id) },
                                onOpen = { onOpenConversation(run.conversationId) },
                                projectName = run.projectId?.let { projectNames[it] },
                            )
                        }
                    }
                }
            }

            if (onStart != null && composerOpen) {
                StartRun(
                    goal = goal,
                    lookOnly = lookOnly,
                    starting = starting,
                    projectName = projectName,
                    onGoalChanged = { goal = it },
                    onLookOnlyChanged = { lookOnly = it },
                    onStart = {
                        onStart(goal, lookOnly)
                        goal = ""
                        composing = false
                    },
                    focusOnOpen = composing,
                    onCancel = if (runs.isNotEmpty()) {
                        {
                            goal = ""
                            composing = false
                        }
                    } else {
                        null
                    },
                )
            } else if (onStart != null) {
                SecondaryButton(
                    label = "New agent run",
                    onClick = { composing = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.x3, vertical = Spacing.x2),
                )
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
}

/**
 * Set a job going on the computer.
 *
 * The one thing the phone could not do, and the reason for carrying it: an agent
 * run is what actually builds a project, and starting one meant being at the desk.
 *
 * It says which project it will work in, because that is where real edits land and
 * a run started against the wrong folder is the expensive kind of mistake. And it
 * says the run will plan first, because that is what makes starting one from a
 * phone reasonable — nothing is touched until a plan comes back and you approve it,
 * on this same screen.
 */
@Composable
private fun StartRun(
    goal: String,
    lookOnly: Boolean,
    starting: Boolean,
    projectName: String?,
    onGoalChanged: (String) -> Unit,
    onLookOnlyChanged: (Boolean) -> Unit,
    onStart: () -> Unit,
    /** Opened by tapping "New agent run", so the keyboard should come with it. */
    focusOnOpen: Boolean = false,
    /** Fold the composer away again. Null where it is the only thing on the screen. */
    onCancel: (() -> Unit)? = null,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    val ready = goal.isNotBlank() && !starting && projectName != null
    val focus = remember { FocusRequester() }
    if (focusOnOpen) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.x3)
            .clip(Radii.xl)
            .background(colors.bgInput)
            .padding(Spacing.x3),
        verticalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        BasicTextField(
            value = goal,
            onValueChange = onGoalChanged,
            textStyle = type.body.copy(color = colors.text),
            cursorBrush = SolidColor(colors.accentInk),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            decorationBox = { inner ->
                if (goal.isEmpty()) {
                    Text(
                        text = "Fix the failing tests in the parser",
                        style = type.body,
                        color = colors.textFaint,
                    )
                }
                inner()
            },
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            // Two intentions, not a tool checklist. The desktop shows every tool by
            // name; a phone offering the same would be a list nobody can weigh
            // standing up, and the computer narrows whatever is asked for anyway.
            Chip("Build it", selected = !lookOnly) { onLookOnlyChanged(false) }
            Chip("Look only", selected = lookOnly) { onLookOnlyChanged(true) }

            if (onCancel != null) {
                Box(Modifier.weight(1f))
                Text(
                    text = "Cancel",
                    style = type.label,
                    color = colors.textMuted,
                    modifier = Modifier
                        .clip(Radii.pill)
                        .clickable(role = Role.Button, onClick = onCancel)
                        .padding(horizontal = Spacing.x3, vertical = Spacing.x2),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    starting -> "Starting…"
                    projectName == null -> "Open a project first"
                    lookOnly -> "Reads $projectName and reports back"
                    else -> "Plans first, then waits for you to approve"
                },
                style = type.meta,
                color = if (projectName == null) colors.warnInk else colors.textFaint,
                modifier = Modifier.weight(1f),
            )

            Box(
                modifier = Modifier
                    .size(Touch.minTarget)
                    .clip(Radii.pill)
                    .background(if (ready) colors.accent else colors.bgElevated)
                    .clickable(enabled = ready, role = Role.Button, onClick = onStart),
                contentAlignment = Alignment.Center,
            ) {
                AnodexIcon(
                    AnodexIcon.SEND,
                    size = 16.dp,
                    tint = if (ready) colors.textOnAccent else colors.textFaint,
                    contentDescription = "Start the run",
                )
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Text(
        text = label,
        style = type.label,
        color = if (selected) colors.textOnAccent else colors.textMuted,
        modifier = Modifier
            .clip(Radii.pill)
            .background(if (selected) colors.accent else colors.bgSurface2)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.x3, vertical = Spacing.x2),
    )
}

@Composable
private fun RunCard(
    run: AgentRun,
    busy: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
    projectName: String? = null,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    val waiting = run.status == AgentRun.Status.NEEDS_REVIEW
    val running = run.status == AgentRun.Status.RUNNING

    // The desktop's card, in the desktop's order: a status edge down the left, the
    // status as a pill with the project beside it, the goal, what did the work, the
    // budgets while it works, and the outcome once it has one. The two apps are one
    // product, and a run should be recognisable on either before a word is read.
    Box {
        AnodexCard(
            onClick = onOpen,
            // Room for the edge, so text never runs underneath it.
            padding = PaddingValues(
                start = Spacing.x4 + RUN_EDGE_WIDTH,
                end = Spacing.x4,
                top = Spacing.x3,
                bottom = Spacing.x3,
            ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
            ) {
                StatusBadge(run)

                projectName?.let {
                    Text(
                        text = it,
                        style = type.badge,
                        color = colors.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clip(Radii.pill)
                            .background(colors.bgElevated)
                            .padding(horizontal = Spacing.x2, vertical = 1.dp),
                    )
                }

                Box(Modifier.weight(1f))

                relativeTime(run.updatedAtEpochMs.takeIf { it > 0 })?.let { ago ->
                    Text(ago, style = type.meta, color = colors.textFaint)
                }
            }

            Text(
                // Flattened, because a goal is a pasted prompt and often arrives with
                // its own line breaks. Left alone, a title followed by a stray "…" on a
                // line of its own reads as a rendering fault rather than as text.
                text = oneLine(run.goal),
                style = type.body,
                color = colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            providerLine(run)?.let { line ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
                ) {
                    if (run.provider == "local") {
                        AnodexIcon(AnodexIcon.CPU, size = 12.dp, tint = colors.textFaint)
                    }
                    Text(line, style = type.meta, color = colors.textFaint)
                }
            }

            if (running) BudgetMeters(run)

            // Only a blocked run shows its plan. Everywhere else it is detail nobody
            // asked for on a screen that exists to unblock things.
            if (waiting && run.plan != null) {
                PlanView(run.plan)
            }

            RunOutcome(run)

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

        RunEdge(run.status, Modifier.matchParentSize())
    }
}

/**
 * The card's left edge: which state the run is in, and whether it is moving.
 *
 * Every status has a colour, as on the desktop's `runCard-*` border. Two of them move,
 * each for the reason the desktop gives:
 *
 * - **Running** carries the comet — a white-hot core on the edge with a soft halo
 *   spilling onto the card — travelling down and back up. The reversal is what reads
 *   as "working", and it is the same signal the desktop rides along a busy chat row.
 *   It replaced a blinking status glyph there, and it replaces the rippling dot here.
 * - **Needs review** pulses. The one place an endless loop is honest: the waiting
 *   genuinely is endless, and it stops the moment somebody answers.
 *
 * Under reduce-motion both stand still as a solid edge, which says exactly as much.
 */
@Composable
private fun RunEdge(status: AgentRun.Status, modifier: Modifier = Modifier) {
    val colors = AnodexTheme.colors
    val reducedMotion = LocalReducedMotion.current

    val edge = when (status) {
        AgentRun.Status.RUNNING -> colors.accent
        AgentRun.Status.NEEDS_REVIEW -> colors.info
        AgentRun.Status.DONE -> colors.success
        AgentRun.Status.STOPPED -> colors.warn
        AgentRun.Status.ERROR -> colors.danger
    }

    // Only the card that moves runs a clock. An infinite transition ticks every frame
    // whether or not anything reads it, so one on every finished card in the list
    // would spend the battery drawing nothing.
    val comet = if (!reducedMotion && status == AgentRun.Status.RUNNING) cometProgress() else null
    val pulse = if (!reducedMotion && status == AgentRun.Status.NEEDS_REVIEW) pulseProgress() else null

    val cyan = colors.accentCyan
    val violet = colors.accentViolet
    val accent = colors.accent

    Canvas(modifier.clip(Radii.xl)) {
        val width = RUN_EDGE_WIDTH.toPx()

        if (pulse != null) {
            val alpha = pulse.value
            // The glow the desktop throws with a box-shadow, as a soft band beside it.
            val glow = 16.dp.toPx()
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(edge.copy(alpha = 0.35f * (1f - alpha)), Color.Transparent),
                    startX = width,
                    endX = width + glow,
                ),
                topLeft = Offset(width, 0f),
                size = Size(glow, size.height),
            )
            drawRect(edge.copy(alpha = alpha), size = Size(width, size.height))
            return@Canvas
        }

        drawRect(edge, size = Size(width, size.height))

        if (comet != null) {
            val length = size.height * COMET_LENGTH
            val top = size.height * comet.value

            // The halo first, so the core sits on top of its own light.
            val haloWidth = 26.dp.toPx()
            drawOval(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to accent.copy(alpha = 0.5f),
                        0.38f to accent.copy(alpha = 0.18f),
                        0.72f to Color.Transparent,
                    ),
                    center = Offset(width / 2, top + length / 2),
                    radius = maxOf(haloWidth, length) / 2,
                ),
                topLeft = Offset(width / 2 - haloWidth / 2, top),
                size = Size(haloWidth, length),
            )

            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.26f to cyan,
                        0.5f to COMET_HOT,
                        0.74f to violet,
                        1f to Color.Transparent,
                    ),
                    startY = top,
                    endY = top + length,
                ),
                topLeft = Offset(0f, top),
                size = Size(width, length),
            )
        }
    }
}

/** `cometRun`: from just above the card to its bottom, 2.1s each way, reversing. */
@Composable
private fun cometProgress(): State<Float> =
    rememberInfiniteTransition(label = "comet").animateFloat(
        initialValue = -COMET_LENGTH,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(COMET_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "comet",
    )

/** `waitingEdgePulse`: down to 30% and back over 1.8s. */
@Composable
private fun pulseProgress(): State<Float> =
    rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            tween(PULSE_MS / 2, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

/** The status as the desktop words and colours it, with its glyph. */
@Composable
private fun StatusBadge(run: AgentRun) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    val (ink, soft, icon) = when (run.status) {
        AgentRun.Status.RUNNING -> Triple(colors.accentInk, colors.accentSoft, AnodexIcon.ACTIVITY)
        // `--info` is the accent's hex on the desktop and has no ink step here; the
        // accent's is the same colour made legible on both grounds.
        AgentRun.Status.NEEDS_REVIEW -> Triple(colors.accentInk, colors.infoSoft, AnodexIcon.INFO)
        AgentRun.Status.DONE -> Triple(colors.successInk, colors.successSoft, AnodexIcon.CHECK)
        AgentRun.Status.STOPPED -> Triple(colors.warnInk, colors.warnSoft, AnodexIcon.STOP)
        AgentRun.Status.ERROR -> Triple(colors.dangerInk, colors.dangerSoft, AnodexIcon.ALERT)
    }

    Row(
        modifier = Modifier
            .clip(Radii.pill)
            .background(soft)
            .padding(horizontal = Spacing.x2, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        AnodexIcon(icon, size = 12.dp, tint = ink)
        Text(statusLabel(run.status), style = type.badge, color = ink)
    }
}

/**
 * Turns, tokens and time as three hairlines — the desktop's `BudgetMeters`.
 *
 * A bar is read without being counted, which is the difference between knowing a run
 * has room left and having to work it out. Tinted to warn at 80%, while there is
 * still room to decide something. An unlimited run has no ceiling to fill against, so
 * it shows the counts over a broken rule rather than a bar at some invented fraction.
 */
@Composable
private fun BudgetMeters(run: AgentRun) {
    // Time moves on its own while the run works, so it is re-read on the desktop's
    // cadence rather than only when the computer sends something.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(run.id) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val minutes = run.activeElapsedMs(now) / 60_000

    val meters = if (run.limitsEnabled) {
        listOf(
            Meter("Turns", "${run.turnsUsed}/${run.maxTurns}", fraction(run.turnsUsed.toLong(), run.maxTurns.toLong())),
            Meter("Tokens", "${compactTokens(run.tokensUsed)}/${compactTokens(run.maxTokens)}", fraction(run.tokensUsed, run.maxTokens)),
            Meter("Time", "$minutes/${run.maxDurationMinutes} min", fraction(minutes, run.maxDurationMinutes.toLong())),
        )
    } else {
        listOf(
            Meter("Turns", "${run.turnsUsed}", null),
            Meter("Tokens", compactTokens(run.tokensUsed), null),
            Meter("Time", "$minutes min", null),
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.x1),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        for (meter in meters) {
            MeterView(meter, Modifier.weight(1f))
        }
    }
}

private data class Meter(val label: String, val value: String, val fraction: Float?)

private fun fraction(used: Long, of: Long): Float? =
    if (of > 0) (used.toFloat() / of).coerceIn(0f, 1f) else null

@Composable
private fun MeterView(meter: Meter, modifier: Modifier = Modifier) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    val filled = meter.fraction
    val warn = filled != null && filled >= BUDGET_WARN_AT

    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(meter.label, style = type.badge, color = colors.textFaint, modifier = Modifier.weight(1f))
            Text(meter.value, style = type.badge, color = if (warn) colors.warnInk else colors.textMuted)
        }

        if (filled == null) {
            // `openEndedRule`: dashes, because there is no end to fill towards.
            val rule = colors.borderStrong
            Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                val dash = 4.dp.toPx()
                var x = 0f
                while (x < size.width) {
                    drawRect(rule, topLeft = Offset(x, 0f), size = Size(minOf(dash, size.width - x), size.height))
                    x += dash * 2
                }
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(Radii.pill)
                    .background(colors.border),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(filled)
                        .height(2.dp)
                        .clip(Radii.pill)
                        .background(if (warn) colors.warn else colors.accent.copy(alpha = 0.55f)),
                )
            }
        }
    }
}

/**
 * What the run came to, in a tinted box — the desktop's `runResult`.
 *
 * Red for a failure, amber for a run that stopped with a reason, plain otherwise. A
 * failure says so in words too, so the colour is never the only thing carrying it.
 */
@Composable
private fun RunOutcome(run: AgentRun) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    val text = (run.summary ?: run.lastError ?: return).withoutOutcomeHeading()

    val (ink, ground) = when {
        run.status == AgentRun.Status.ERROR -> colors.dangerInk to colors.dangerSoft
        run.status == AgentRun.Status.STOPPED && run.lastError != null -> colors.warnInk to colors.warnSoft
        else -> colors.textMuted to colors.bgSurface2
    }

    Text(
        text = oneLine(if (run.status == AgentRun.Status.ERROR) "Failed: $text" else text),
        style = type.meta,
        color = ink,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.md)
            .background(ground)
            .padding(horizontal = Spacing.x3, vertical = Spacing.x2),
    )
}

/**
 * A summary without the desktop's "What this reply did" label.
 *
 * The desktop appends its account of a turn under that bold heading, which reads as a
 * heading in a transcript. Flattened into one line on a card it ran straight into the
 * account itself — "What this reply did Changed src/…" — and the box is already the
 * answer to that question.
 */
internal fun String.withoutOutcomeHeading(): String =
    replace(Regex("""(?m)^\s*\*\*What this reply did\*\*\s*$"""), "").trim()

/** "Local", "Claude · claude-sonnet-5" — what did the work, as the desktop labels it. */
private fun providerLine(run: AgentRun): String? {
    val vendor = providerVendor(run.provider) ?: return null
    return run.model?.let { "$vendor · $it" } ?: vendor
}

/** 12400 → "12.4k", the desktop's `formatCompactTokens`. */
internal fun compactTokens(n: Long): String =
    if (n >= 1000) String.format(java.util.Locale.US, "%.1fk", n / 1000.0) else n.toString()

/** The desktop's `STATUS_LABEL`, word for word. */
private fun statusLabel(status: AgentRun.Status): String = when (status) {
    AgentRun.Status.RUNNING -> "Running"
    AgentRun.Status.NEEDS_REVIEW -> "Needs review"
    AgentRun.Status.DONE -> "Done"
    AgentRun.Status.STOPPED -> "Stopped"
    AgentRun.Status.ERROR -> "Error"
}

/** Width of the status edge, the desktop's 3px `border-left`. */
private val RUN_EDGE_WIDTH = 3.dp

/** The comet is 46% of the card tall, as on the desktop. */
private const val COMET_LENGTH = 0.46f
private const val COMET_MS = 2_100
private const val PULSE_MS = 1_800

/** The core's white-hot middle, `#eaf2ff` on the desktop. Light, not a theme colour. */
private val COMET_HOT = Color(0xFFEAF2FF)

/** Where a budget bar turns amber. The desktop's `BUDGET_WARN_AT`. */
private const val BUDGET_WARN_AT = 0.8f

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

/**
 * A pasted prompt or a summary as one plain line.
 *
 * Flattened so a title cannot arrive already broken, and without its markdown marks,
 * because the card draws text rather than rendering it: a goal pasted from another
 * assistant showed its `**bold**` as asterisks, and a summary opened on a literal
 * `---` rule.
 */
private fun oneLine(text: String): String = text.lineSequence()
    .map(::withoutMarkdown)
    .filter { it.isNotBlank() }
    .joinToString(" ")


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
