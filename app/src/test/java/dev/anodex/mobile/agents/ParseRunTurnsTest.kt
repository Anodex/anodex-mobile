package dev.anodex.mobile.agents

import dev.anodex.mobile.ui.screens.formatDuration
import dev.anodex.mobile.ui.screens.turnGist
import dev.anodex.mobile.ui.screens.turnMeta
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading `agent:turns`, and the lines a folded turn shows. */
class ParseRunTurnsTest {

    private fun turns(json: String) = parseRunTurns(Json.parseToJsonElement(json))

    @Test
    fun `a turn arrives with its tools, numbers and health`() {
        val turn = turns(
            """[{"number":2,"messageId":"m2","text":"Found it.","tools":[
                {"name":"read_file","title":"Read package.json","status":"success"},
                {"name":"search_code","title":"Search for main","status":"error"}],
                "moreTools":3,"tokens":1600,"durationMs":51800,"health":"warn","error":null}]""",
        ).single()

        assertEquals(2, turn.number)
        assertEquals("Found it.", turn.text)
        assertEquals(listOf("Read package.json", "Search for main"), turn.tools.map { it.title })
        assertEquals(listOf(false, true), turn.tools.map { it.failed })
        assertEquals(3, turn.moreTools)
        assertEquals(1600L, turn.tokens)
        assertEquals(51_800L, turn.durationMs)
        assertEquals(RunTurn.Health.WARN, turn.health)
        assertNull(turn.error)
    }

    @Test
    fun `missing stats stay missing instead of reading as zero`() {
        val turn = turns("""[{"number":1,"messageId":"m1","text":"","tools":[],"tokens":null,"durationMs":null}]""").single()

        assertNull(turn.tokens)
        assertNull(turn.durationMs)
        assertEquals(RunTurn.Health.OK, turn.health)
        assertEquals("", turnMeta(turn))
    }

    @Test
    fun `an enveloped reply reads the same and a turn without an id is skipped`() {
        val read = turns("""{"ok":true,"value":[{"number":1,"messageId":"m1"},{"number":2}]}""")

        assertEquals(listOf("m1"), read.map { it.messageId })
    }

    @Test
    fun `anything that is not a list of turns is no turns`() {
        assertTrue(turns("""{"ok":false,"error":"No handler"}""").isEmpty())
        assertTrue(parseRunTurns(null).isEmpty())
    }

    @Test
    fun `a folded turn says what it did when it said nothing`() {
        val silent = turns(
            """[{"number":1,"messageId":"m1","text":"  ","tools":[{"name":"list_directory","title":"List","status":"success"}],"moreTools":1,"tokens":2400,"durationMs":900}]""",
        ).single()

        assertEquals("2 tool calls", turnGist(silent))
        assertEquals("2 tools · 2.4k · 900ms", turnMeta(silent))
    }

    @Test
    fun `durations read the way the desktop writes them`() {
        assertEquals("18.6s", formatDuration(18_600))
        assertEquals("2m 04s", formatDuration(124_000))
    }
}
