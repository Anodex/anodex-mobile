package dev.anodex.mobile.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tool rows read back from history: the desktop's `RemoteToolCall`, one line each. */
class RemoteToolsTest {

    private fun parse(json: String) = parseRemoteTools(Json.parseToJsonElement(json))

    @Test
    fun `a reopened chat shows the command its reply ran`() {
        val tools = parse(
            """[{"id":"t1","name":"run_command","title":"Run: echo hello","detail":"exit 0","status":"success"}]"""
        )
        assertEquals(
            listOf(ToolActivity("t1", "run_command", "Run: echo hello", "exit 0", ToolActivity.Status.DONE)),
            tools,
        )
    }

    @Test
    fun `a refused or failed call reads as failed, as it does live`() {
        val tools = parse("""[{"id":"a","name":"x","title":"X","status":"denied"},{"id":"b","name":"y","title":"Y","status":"error"}]""")
        assertEquals(listOf(ToolActivity.Status.FAILED, ToolActivity.Status.FAILED), tools.map { it.status })
    }

    @Test
    fun `a computer that sends no rows gives none`() {
        assertEquals(emptyList<ToolActivity>(), parseRemoteTools(null))
        assertEquals(emptyList<ToolActivity>(), parse("""[{"name":"no id"}]"""))
    }
}
