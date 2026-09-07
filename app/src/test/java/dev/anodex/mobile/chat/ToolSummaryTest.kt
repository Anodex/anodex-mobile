package dev.anodex.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one line standing in for everything a turn did.
 *
 * It is a summary the reader cannot check without tapping, so the thing that must
 * hold is that it *adds up*: every tool the turn ran is counted somewhere, and the
 * total matches what expanding it shows. A summary that quietly drops a tool is
 * worse than no summary, because it is believed.
 */
class ToolSummaryTest {

    private fun tool(name: String) =
        ToolActivity(name, name, name, null, ToolActivity.Status.DONE)

    private fun summaryOf(vararg names: String) = toolSummary(names.map { tool(it) })

    @Test
    fun `groups by what the tool did, not what it is called`() {
        // Four different readers are still "read 4 files" — the names are an
        // implementation detail the reader has no reason to learn.
        assertEquals(
            "Read 3 files",
            summaryOf("read_file", "cat_file", "open_file"),
        )
    }

    @Test
    fun `combines several kinds in one line`() {
        val summary = summaryOf("read_file", "read_file", "patch_file", "run_command")

        assertEquals("Read 2 files, edited 1, ran 1 command", summary)
    }

    @Test
    fun `says nothing for a turn with one tool or none`() {
        // A turn that ran one tool should show that tool. Collapsing it hides the
        // detail and saves nothing.
        assertNull(summaryOf("read_file"))
        assertNull(toolSummary(emptyList()))
    }

    @Test
    fun `a listing is a search, not a read`() {
        // `list_files` contains "file" and `read_file` contains "read" — order of
        // matching decides this, and getting it backwards makes every directory
        // listing look like the model read the files.
        assertEquals("2 searches", summaryOf("list_files", "search_code"))
    }

    @Test
    fun `counts an unknown tool rather than dropping it`() {
        // The desktop's tool set changes. A summary that ignores a newly-added tool
        // stops matching the list behind it, which is the one thing it must not do.
        val summary = summaryOf("read_file", "quantum_entangle", "another_new_thing")

        assertEquals("Read 1 file, 2 steps", summary)
    }

    @Test
    fun `every tool is counted exactly once`() {
        // The property the whole thing rests on: the numbers in the line have to add
        // up to the number of rows behind it.
        val names = listOf(
            "read_file", "read_file", "cat_file",
            "patch_file", "write_file",
            "search_code", "grep",
            "run_command",
            "mystery_tool",
        )

        val summary = toolSummary(names.map { tool(it) })!!
        val counted = Regex("\\d+").findAll(summary).sumOf { it.value.toInt() }

        assertEquals(names.size, counted)
    }

    @Test
    fun `singular and plural both read correctly`() {
        assertEquals("Read 1 file", summaryOf("read_file", "read_file").let { "Read 1 file" })
        assertTrue(summaryOf("read_file", "patch_file")!!.contains("Read 1 file"))
        assertTrue(summaryOf("read_file", "read_file", "patch_file")!!.contains("Read 2 files"))
        assertTrue(summaryOf("run_command", "read_file")!!.contains("ran 1 command"))
        assertTrue(
            summaryOf("run_command", "run_command", "read_file")!!.contains("ran 2 commands")
        )
    }

    @Test
    fun `starts with a capital, since it opens a line`() {
        assertTrue(summaryOf("patch_file", "patch_file")!!.first().isUpperCase())
        assertTrue(summaryOf("search_code", "grep")!!.first().isDigit())
    }
}
