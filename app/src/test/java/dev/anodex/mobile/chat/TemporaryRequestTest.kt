package dev.anodex.mobile.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** What a turn tells the computer about being temporary. */
class TemporaryRequestTest {

    @Test
    fun `a temporary turn asks the computer to keep nothing`() {
        val request = chatRequest("c1", "m1", "hi", JsonArray(emptyList()), projectId = null, temporary = true)
        assertEquals(JsonPrimitive(true), request["temporary"])
    }

    @Test
    fun `an ordinary turn sends no temporary key at all`() {
        val request = chatRequest("c1", "m1", "hi", JsonArray(emptyList()), projectId = null)
        assertFalse(request.containsKey("temporary"))
    }
}
