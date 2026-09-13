package dev.anodex.mobile.agents

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reading `agents:list` off the wire.
 *
 * Every run on the phone once said "20684 days ago". The timestamp was parsed as
 * an `Int`, which clamps a real epoch-millisecond value to 24.8 days after 1970 —
 * so the screen looked broken in a way no unit test of the formatter could see,
 * because the formatter was right and the number it was handed was not.
 */
class ParseAgentRunsTest {

    private fun runs(json: String) = parseAgentRuns(Json.parseToJsonElement(json))

    @Test
    fun `a real timestamp survives parsing`() {
        val updatedAt = 1_789_274_000_000L // September 2026

        val run = runs("""[{"id":"r1","status":"running","updatedAt":$updatedAt}]""").single()

        assertEquals(updatedAt, run.updatedAtEpochMs)
    }

    @Test
    fun `runs are ordered by their real times, not all tied at the clamp`() {
        val older = 1_789_000_000_000L
        val newer = 1_789_274_000_000L

        val ids = runs(
            """[
                {"id":"old","status":"completed","updatedAt":$older},
                {"id":"new","status":"completed","updatedAt":$newer}
            ]""",
        ).map { it.id }

        assertEquals(listOf("new", "old"), ids)
    }

    @Test
    fun `a missing timestamp is zero, which the screen already hides`() {
        val run = runs("""[{"id":"r1","status":"running"}]""").single()

        assertEquals(0L, run.updatedAtEpochMs)
    }
}
