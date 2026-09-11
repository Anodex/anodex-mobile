package dev.anodex.mobile.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reading the ring draws, and the one it refuses.
 *
 * The context ring never worked, and the reason was not arithmetic. The phone was
 * reading `EngineState.contextTokensUsed` — the engine's live KV-cache index, which
 * exists only while a generation is in flight and is absent the rest of the time.
 * The meter on the desktop has never used that field; it projects what the *next*
 * turn will see, from renderer state a phone does not have.
 *
 * So the number is read over `chat:context-usage` now. These hold the two things
 * that can still go wrong with it: a reply that is not a complete reading, and a
 * reading about a conversation the screen is not showing.
 */
class ContextUsageTest {

    private fun parse(json: String, conversationId: String = "c1") =
        contextUsageFrom(Json.parseToJsonElement(json), conversationId)

    @Test
    fun `a full reading becomes a fraction`() {
        val usage = parse("""{"usedTokens":2048,"contextSize":8192,"pct":25}""")

        assertEquals(2048, usage?.usedTokens)
        assertEquals(8192, usage?.contextSize)
        assertEquals(0.25f, usage!!.fraction!!, 0f)
    }

    @Test
    fun `the conversation it was measured for is carried`() {
        // The phone is often looking at a different conversation than the desk is.
        // A reading from the wrong one is worse than no reading, so the caller is
        // given what it needs to refuse it.
        assertEquals("c_abc", parse("""{"usedTokens":1,"contextSize":10}""", "c_abc")?.conversationId)
    }

    @Test
    fun `the desktop saying nothing is not a reading of zero`() {
        // It answers null itself whenever there is nothing honest to report — no
        // conversation, no settings, no model loaded. That is an ordinary state, and
        // it must not arrive here as an empty bar.
        assertNull(parse("null"))
        assertNull(contextUsageFrom(null, "c1"))
    }

    @Test
    fun `a partial reading is no reading`() {
        // Half a projection is not a measurement. Either field missing means the
        // fraction would be invented from whichever one arrived.
        assertNull(parse("""{"usedTokens":2048}"""))
        assertNull(parse("""{"contextSize":8192}"""))
        assertNull(parse("""{}"""))
    }

    @Test
    fun `a window of zero is not a window`() {
        // No model loaded. Dividing by it would be a fraction of nothing.
        assertNull(parse("""{"usedTokens":0,"contextSize":0}"""))
    }

    @Test
    fun `a genuinely empty context is still a reading`() {
        // And must stay distinguishable from the absences above: a fresh
        // conversation really has used nothing, and an empty ring says so truthfully.
        val usage = parse("""{"usedTokens":0,"contextSize":8192}""")

        assertEquals(0f, usage!!.fraction!!, 0f)
    }

    @Test
    fun `a full context does not overflow the ring`() {
        // The projection includes reserved reply room and can reach the ceiling; a
        // ring drawn past its own end is a rendering bug rather than a warning.
        assertEquals(1f, parse("""{"usedTokens":9000,"contextSize":8192}""")!!.fraction!!, 0f)
    }

    @Test
    fun `an unreadable reply is refused rather than thrown`() {
        // This arrives from the wire. Anything that escapes here reaches the socket's
        // read loop, where it would tear down a working connection.
        assertNull(parse(""""a string""""))
        assertNull(parse("""[1,2,3]"""))
        assertNull(parse("""42"""))
        assertNull(parse("""{"usedTokens":"lots","contextSize":8192}"""))
    }
}
