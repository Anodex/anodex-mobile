package dev.anodex.mobile.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the ongoing notification says under the host's name.
 *
 * It used to say "Anodex can reach your computer." — every minute of every day,
 * whatever the computer was doing. Which is the one thing the user already knows,
 * because the notification's existence says it. The report was fair: "the
 * notifications are very generic".
 *
 * A phone in a pocket is exactly where "what is it doing right now" is worth
 * knowing, and this line is the only place the app can answer without being opened.
 * So the rule is that it should always be carrying the most specific true thing
 * available — and never a figure nobody measured, which is the defect this app has
 * already had once in the context ring.
 */
class ConnectionDetailTest {

    private fun detail(
        connected: Boolean = true,
        working: Boolean = false,
        modelName: String? = "Qwen3-30B",
        usedTokens: Int? = 4_949,
        contextSize: Int? = 32_768,
    ) = connectionDetail(connected, working, modelName, usedTokens, contextSize)

    @Test
    fun `a turn in flight is the most useful thing it can say`() {
        // The reason to glance at all: a run is going on over there while the phone
        // is in a pocket. It outranks the model and the context, which are not going
        // anywhere.
        assertEquals("Working on a reply.", detail(working = true))
    }

    @Test
    fun `idle, it names the model and how full its context is`() {
        assertEquals("Qwen3-30B · 15% of 32K", detail())
    }

    @Test
    fun `a reachable computer with nothing loaded says so`() {
        // Not an absence to paper over. A computer that cannot answer looks exactly
        // like one that can until you ask it something.
        assertEquals("No model loaded.", detail(modelName = null))
        assertEquals("No model loaded.", detail(modelName = "   "))
    }

    @Test
    fun `no reading means the model alone, never an invented percentage`() {
        // The context ring's whole lesson. A percentage derived from a count nobody
        // took is worse than no percentage.
        assertEquals("Qwen3-30B", detail(usedTokens = null))
        assertEquals("Qwen3-30B", detail(contextSize = null))
        assertEquals("Qwen3-30B", detail(contextSize = 0))
    }

    @Test
    fun `a genuinely empty context still reports zero`() {
        // And must stay distinguishable from the above: a fresh conversation really
        // has used nothing.
        assertEquals("Qwen3-30B · 0% of 32K", detail(usedTokens = 0))
    }

    @Test
    fun `a full context does not exceed a hundred percent`() {
        // The projection includes reserved reply room and can reach the ceiling.
        assertEquals("Qwen3-30B · 100% of 32K", detail(usedTokens = 40_000))
    }

    @Test
    fun `reconnecting says that and nothing else`() {
        // Whatever was loaded a moment ago is not knowable now, and guessing at it
        // while the link is down would be reporting a computer the phone cannot see.
        assertEquals("Trying to reach your computer.", detail(connected = false))
        assertEquals("Trying to reach your computer.", detail(connected = false, working = true))
    }

    @Test
    fun `the window is written short enough for one line`() {
        assertTrue(detail(contextSize = 8_192, usedTokens = 4_096).endsWith("of 8K"))
        assertTrue(detail(contextSize = 131_072, usedTokens = 1).endsWith("of 131K"))
        assertTrue(detail(contextSize = 900, usedTokens = 450).endsWith("of 900"))
    }

    @Test
    fun `it never says the one thing the notification already says`() {
        // The line it replaced. Its existence tells the user the computer is
        // reachable; spending the only line on that again is spending it on nothing.
        for (said in listOf(
            detail(),
            detail(working = true),
            detail(modelName = null),
            detail(usedTokens = null),
            detail(connected = false),
        )) {
            assertTrue("still generic: <$said>", !said.contains("can reach your computer"))
        }
    }
}
