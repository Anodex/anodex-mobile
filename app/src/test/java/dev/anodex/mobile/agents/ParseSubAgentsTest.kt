package dev.anodex.mobile.agents

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sub-agents, as the phone reads them off `agents:list`.
 *
 * The desktop nests a run's sub-agents underneath it. This screen has no room
 * to, and the list arrives flat, so without this a three-way delegation shows
 * as four rows with nothing saying they belong together — four unrelated runs,
 * as far as anyone reading a phone can tell.
 */
class ParseSubAgentsTest {

    private fun runs(json: String) = parseAgentRuns(Json.parseToJsonElement(json))

    @Test
    fun `sub-agents are counted onto their parent rather than listed`() {
        val parsed = runs(
            """[
              {"id":"parent","goal":"Find the bugs","status":"done","updatedAt":3},
              {"id":"kid-a","goal":"check auth","status":"done","updatedAt":2,
               "parentRunId":"parent","delegatedTask":"check auth"},
              {"id":"kid-b","goal":"check parsing","status":"done","updatedAt":1,
               "parentRunId":"parent","delegatedTask":"check parsing"}
            ]""",
        )

        assertEquals(listOf("parent"), parsed.map { it.id })
        assertEquals(2, parsed.single().subAgentCount)
    }

    @Test
    fun `an ordinary run reports no sub-agents`() {
        val run = runs("""[{"id":"r1","goal":"Do it","status":"done","updatedAt":1}]""").single()

        assertEquals(0, run.subAgentCount)
        assertNull(run.parentRunId)
    }

    @Test
    fun `a sub-agent whose parent is missing is still shown`() {
        // Its parent may have been deleted, or be outside whatever the desktop
        // sent. A run that exists and appears nowhere is worse than one shown
        // out of place.
        val parsed = runs(
            """[{"id":"orphan","goal":"check auth","status":"done","updatedAt":1,
                 "parentRunId":"gone"}]""",
        )

        assertEquals(listOf("orphan"), parsed.map { it.id })
    }
}
