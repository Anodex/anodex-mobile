package dev.anodex.mobile.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The computer's "reading" progress, as the phone shows it under a pending reply. */
class ReadingProgressTest {

    private fun working(json: String) = Json.parseToJsonElement(json)

    @Test
    fun `a reading event names the phase and carries how far`() {
        val event = working("""{"conversationId":"c1","messageId":"m1","phase":"reading","since":1,"reading":{"done":4428,"total":9840}}""")

        assertEquals(WorkingPhase.READING, workingPhase(event, "c1"))
        assertEquals(ReadingProgress(4428, 9840), readingProgressOf(event))
        assertEquals("Reading · 45%", readingProgressOf(event)?.label)
    }

    @Test
    fun `a nearly finished or tiny read shows nothing, and never 100 percent`() {
        assertNull(ReadingProgress(9000, 9840).label)
        assertEquals(99, ReadingProgress(9999, 10000).percent)
    }

    @Test
    fun `an older computer's working event has no reading`() {
        val event = working("""{"conversationId":"c1","messageId":"m1","phase":"working","since":1}""")

        assertEquals(WorkingPhase.WORKING, workingPhase(event, "c1"))
        assertNull(readingProgressOf(event))
    }
}
