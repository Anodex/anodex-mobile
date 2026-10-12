package dev.anodex.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolNamesTest {

    private fun call(name: String, title: String, status: ToolActivity.Status = ToolActivity.Status.DONE) =
        ToolActivity("1", name, title, null, status)

    @Test
    fun `a known tool is said in words, past tense once it is done`() {
        assertEquals("Ran a command", toolAction(call("run_command", "Run: echo 2 + 2")))
        assertEquals("Searched the web", toolAction(call("web_search", "Search: weather")))
        assertEquals("Sent an email", toolAction(call("send_email", "Send to sam@example.com")))
    }

    @Test
    fun `and in the present while it is still going`() {
        assertEquals(
            "Running a command",
            toolAction(call("run_command", "Run: npm test", ToolActivity.Status.RUNNING)),
        )
    }

    @Test
    fun `the target is the title without its action word`() {
        assertEquals("echo 2 + 2", toolTarget(call("run_command", "Run: echo 2 + 2")))
        assertEquals("src/App.tsx", toolTarget(call("read_file", "Read src/App.tsx")))
    }

    @Test
    fun `a tool this app has never heard of reads as the computer's own action word`() {
        // The desktop's split, so a tool added there tomorrow is not shown as an identifier.
        val unknown = call("summon_kraken", "Summon: the kraken")
        assertEquals("Summon", toolAction(unknown))
        assertEquals("the kraken", toolTarget(unknown))
    }

    @Test
    fun `a one-word title is said once, not twice`() {
        val bare = call("mystery_tool", "Thinking")
        assertEquals("Thinking", toolAction(bare))
        assertEquals("", toolTarget(bare))
    }
}
