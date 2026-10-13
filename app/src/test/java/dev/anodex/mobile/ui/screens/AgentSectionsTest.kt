package dev.anodex.mobile.ui.screens

import dev.anodex.mobile.agents.AgentRun
import dev.anodex.mobile.agents.Plan
import dev.anodex.mobile.agents.PlanStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The phone's run list groups as the desktop's does: what needs you, what works, what is done. */
class AgentSectionsTest {
    private fun run(
        id: String,
        status: AgentRun.Status,
        turnsUsed: Int = 1,
        plan: Plan? = null,
        parentRunId: String? = null,
    ) = AgentRun(
        id = id,
        goal = "Goal $id",
        status = status,
        conversationId = "c-$id",
        turnsUsed = turnsUsed,
        maxTurns = 3,
        limitsEnabled = true,
        summary = null,
        lastError = null,
        plan = plan,
        updatedAtEpochMs = 0,
        parentRunId = parentRunId,
    )

    @Test
    fun `a waiting plan and a run that ran out come first, finished runs last`() {
        val runs = listOf(
            run("done", AgentRun.Status.DONE),
            run("working", AgentRun.Status.RUNNING),
            run("plan", AgentRun.Status.NEEDS_REVIEW),
            run("ranout", AgentRun.Status.STOPPED, turnsUsed = 3),
            run("stopped", AgentRun.Status.STOPPED),
        )
        val sections = runSections(runs).map { (section, list) -> section to list.map { it.id } }
        assertEquals(
            listOf(
                RunSection.NEEDS_YOU to listOf("plan", "ranout"),
                RunSection.WORKING to listOf("working"),
                RunSection.FINISHED to listOf("done", "stopped"),
            ),
            sections,
        )
    }

    @Test
    fun `a sub-agent that ran out is not offered to continue, so it is finished`() {
        assertEquals(
            RunSection.FINISHED,
            runSection(run("sub", AgentRun.Status.STOPPED, turnsUsed = 3, parentRunId = "parent")),
        )
    }

    @Test
    fun `an empty section is left out`() {
        assertEquals(listOf(RunSection.WORKING), runSections(listOf(run("w", AgentRun.Status.RUNNING))).map { it.first })
    }

    @Test
    fun `a working run says which step it is on`() {
        val plan = Plan(
            "Dark mode",
            listOf(
                PlanStep("1", "Find the theme", "completed"),
                PlanStep("2", "Persist the choice", "in_progress"),
                PlanStep("3", "Run the build", "pending"),
            ),
        )
        assertEquals("Persist the choice · step 2 of 3", currentStepLine(run("w", AgentRun.Status.RUNNING, plan = plan)))
        assertNull(currentStepLine(run("w", AgentRun.Status.RUNNING)))
    }
}
