package dev.anodex.mobile.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Whether there is a context figure worth drawing.
 *
 * This shipped wrong for a long time and looked like a broken meter. The desktop
 * sends `contextTokensUsed: this.contextSequence?.nextTokenIndex`, which is absent
 * whenever its engine has no live sequence — most of the time a phone is looking at
 * it. The phone parsed that absence as the number zero, so a bar that had no reading
 * drew itself empty, and every report of it said "the context meter doesn't work".
 *
 * It worked perfectly. It was told nought.
 *
 * The distinction these tests hold is the whole fix: **nothing counted and nothing
 * used are different claims.** One draws no bar; the other draws an empty one.
 */
class ContextFractionTest {

    private fun model(
        used: Int? = null,
        total: Int = 0,
        conversation: String? = null,
    ) = ModelStatus(
        name = "Qwen3-30B",
        contextUsedTokens = used,
        contextTotalTokens = total,
        contextConversationId = conversation,
    )

    @Test
    fun `no reading is not a reading of zero`() {
        // The case that was broken. A loaded model with no live sequence: the size
        // is known, the usage is not, and the honest drawing is nothing at all.
        assertNull(model(used = null, total = 32_768).contextFraction)
    }

    @Test
    fun `a genuine zero is still a zero`() {
        // And must stay distinguishable from the above — a fresh conversation on a
        // loaded model really has used nothing, and an empty bar says so truthfully.
        assertEquals(0f, model(used = 0, total = 32_768).contextFraction!!, 0f)
    }

    @Test
    fun `a real reading is the fraction of it`() {
        assertEquals(0.25f, model(used = 8_192, total = 32_768).contextFraction!!, 0f)
        assertEquals(0.5f, model(used = 4_096, total = 8_192).contextFraction!!, 0f)
    }

    @Test
    fun `no model means no figure, whatever the count says`() {
        // `contextSize` absent is the desktop saying nothing is loaded. A usage
        // number alongside it would be left over from something else.
        assertNull(model(used = 5_000, total = 0).contextFraction)
        assertNull(model(used = null, total = 0).contextFraction)
    }

    @Test
    fun `a count larger than the context does not overflow the bar`() {
        // The desktop counts a sequence that can include what it is about to trim,
        // so this is reachable rather than theoretical, and a bar drawn past its own
        // end is a rendering bug rather than a warning.
        assertEquals(1f, model(used = 40_000, total = 32_768).contextFraction!!, 0f)
    }

    @Test
    fun `the conversation a reading belongs to is carried, not assumed`() {
        // The desktop measures one conversation at a time and says which. Carrying
        // it is what lets a screen refuse a number that is not about what it is
        // showing — see the header, which treats another conversation's count the
        // same as no count at all.
        val status = model(used = 8_192, total = 32_768, conversation = "c_abc")
        assertEquals("c_abc", status.contextConversationId)
        assertEquals(0.25f, status.contextFraction!!, 0f)
    }
}
