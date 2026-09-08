package dev.anodex.mobile.scheduler

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a task's run log off the wire.
 *
 * "Did the 6am run go through, and what did it say" is the question a phone is
 * picked up to answer, and the answer comes out of this. The shapes below are the
 * ones the computer really sends, including the fields it legitimately leaves null.
 */
class TaskRunTest {

    private fun parse(json: String): List<ScheduledTask> =
        parseTasks(Json.parseToJsonElement(json) as JsonElement)

    @Test
    fun `runs come back newest first`() {
        // The computer stores them oldest first, because that is the order they
        // happened in. A log is read from the top, so the phone turns them round —
        // and getting this backwards would put the oldest run where the newest
        // belongs, which is the one thing somebody is looking for.
        val tasks = parse(
            """
            [{
              "id": "t1", "name": "Sweep", "prompt": "go", "enabled": true, "runCount": 3,
              "runs": [
                {"id": "r1", "startedAt": 1000, "durationMs": 10, "status": "success"},
                {"id": "r2", "startedAt": 2000, "durationMs": 20, "status": "success"},
                {"id": "r3", "startedAt": 3000, "durationMs": 30, "status": "failed"}
              ]
            }]
            """.trimIndent()
        )

        assertEquals(listOf("r3", "r2", "r1"), tasks.single().runs.map { it.id })
    }

    @Test
    fun `a task that has never run reads as empty rather than failing`() {
        val tasks = parse(
            """[{"id": "t1", "name": "Sweep", "prompt": "go", "enabled": true, "runCount": 0}]"""
        )

        assertTrue(tasks.single().runs.isEmpty())
    }

    @Test
    fun `a run keeps what it concluded and how late it was`() {
        val tasks = parse(
            """
            [{
              "id": "t1", "name": "Reminder", "prompt": "go", "enabled": true, "runCount": 1,
              "runs": [{
                "id": "r1", "startedAt": 1788534020087, "durationMs": 19413,
                "status": "success", "summary": "Meeting at 9", "delayedMs": 20087
              }]
            }]
            """.trimIndent()
        )

        val run = tasks.single().runs.single()
        assertEquals("success", run.status)
        assertEquals("Meeting at 9", run.summary)
        assertEquals(19413L, run.durationMs)
        assertEquals(20087L, run.delayedMs)
    }

    @Test
    fun `a run with no id is dropped rather than shown as a blank line`() {
        val tasks = parse(
            """
            [{
              "id": "t1", "name": "Sweep", "prompt": "go", "enabled": true, "runCount": 1,
              "runs": [{"startedAt": 1000, "status": "success"}]
            }]
            """.trimIndent()
        )

        assertTrue(tasks.single().runs.isEmpty())
    }

    @Test
    fun `durations read the way somebody would say them`() {
        // Under a minute keeps a decimal, because the difference between a 2 second
        // run and a 19 second one is the interesting part and rounding loses it.
        assertEquals("19.4s", formatDuration(19413))
        assertEquals("under a second", formatDuration(400))
        assertEquals("1m 20s", formatDuration(80_000))
    }

    @Test
    fun `the desktop Result wrapper is unwrapped`() {
        // Some channels answer bare, some inside {ok, value}. Reading it too high
        // gives an empty list rather than an error — the failure that hides.
        val tasks = parse(
            """{"ok": true, "value": [{"id": "t1", "name": "Sweep", "prompt": "go",
               "enabled": true, "runCount": 0}]}"""
        )

        assertEquals("Sweep", tasks.single().name)
    }
}
