package dev.anodex.mobile.agents

import dev.anodex.mobile.ui.screens.compactTokens
import dev.anodex.mobile.ui.screens.runOutcomeText
import dev.anodex.mobile.ui.screens.withoutOutcomeHeading
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The run fields the card draws the desktop's way: where it works, what did the work,
 * and how far through its budgets it is.
 */
class AgentRunFieldsTest {

    private fun run(json: String) = parseAgentRuns(Json.parseToJsonElement("[$json]")).single()

    @Test
    fun `budgets, provider and project are read`() {
        val parsed = run(
            """
            {
              "id": "r1", "status": "running", "projectId": "p1",
              "provider": "local", "model": null,
              "turnsUsed": 3, "maxTurns": 40,
              "tokensUsed": 12400, "maxTokens": 200000, "maxDurationMinutes": 60,
              "activeMs": 90000, "activeSinceAt": null,
              "updatedAt": 1789274000000
            }
            """.trimIndent(),
        )

        assertEquals("p1", parsed.projectId)
        assertEquals("local", parsed.provider)
        assertNull(parsed.model)
        assertEquals(12_400L, parsed.tokensUsed)
        assertEquals(200_000L, parsed.maxTokens)
        assertEquals(60, parsed.maxDurationMinutes)
        assertNull(parsed.activeSinceAtEpochMs)
    }

    @Test
    fun `elapsed time is banked work plus the segment in flight, not age`() {
        // A run that sat waiting for approval must not have that wait counted: the
        // desktop stops runs on this number, and the card has to agree with it.
        val now = 1_789_274_000_000L
        val parsed = run(
            """{"id":"r1","status":"running","activeMs":60000,"activeSinceAt":${now - 30_000}}""",
        )

        assertEquals(90_000L, parsed.activeElapsedMs(now))
    }

    @Test
    fun `a run from before these fields existed reads as zero rather than failing`() {
        val parsed = run("""{"id":"r1","status":"done"}""")

        assertEquals(0L, parsed.tokensUsed)
        assertEquals(0L, parsed.activeElapsedMs(1_789_274_000_000L))
        assertNull(parsed.provider)
    }

    @Test
    fun `providers are named as the desktop names them`() {
        assertEquals("Local", providerVendor("local"))
        assertEquals("Claude", providerVendor("anthropic"))
        assertEquals("Azure OpenAI", providerVendor("azure"))
        assertEquals("something-new", providerVendor("something-new"))
        assertNull(providerVendor(null))
    }

    @Test
    fun `token counts are compact the desktop's way`() {
        assertEquals("950", compactTokens(950))
        assertEquals("12.4k", compactTokens(12_400))
        assertEquals("200.0k", compactTokens(200_000))
    }
}

class OutcomeHeadingTest {
    @Test
    fun `the desktop's outcome heading is dropped, the account kept`() {
        val summary = "---\n**What this reply did**\nChanged src/post/PostFX.ts (3 edits)."
        assertEquals("---\n\nChanged src/post/PostFX.ts (3 edits).", summary.withoutOutcomeHeading())
    }

    @Test
    fun `the words inside a sentence are left alone`() {
        val summary = "I checked **What this reply did** in the log."
        assertEquals(summary, summary.withoutOutcomeHeading())
    }
}

class RunOutcomeTextTest {
    private fun run(status: AgentRun.Status, summary: String?, lastError: String?) = AgentRun(
        id = "r1", goal = "g", status = status, conversationId = "c", turnsUsed = 8, maxTurns = 8,
        limitsEnabled = true, summary = summary, lastError = lastError, plan = null, updatedAtEpochMs = 0,
    )

    @Test
    fun `a stopped run shows why it stopped, not its last reply`() {
        val stopped = run(AgentRun.Status.STOPPED, "all 2 steps complete", "Stopped after 8 turns without finishing.")
        assertEquals("Stopped after 8 turns without finishing.", runOutcomeText(stopped))
    }

    @Test
    fun `a finished run shows its summary`() {
        assertEquals("Listed the folders.", runOutcomeText(run(AgentRun.Status.DONE, "Listed the folders.", null)))
        assertEquals(null, runOutcomeText(run(AgentRun.Status.DONE, null, null)))
    }
}
