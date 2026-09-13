package dev.anodex.mobile.chat

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading a personality's picture off the wire, and naming it on disk.
 *
 * Custom personalities used to draw as initials on the phone whatever face they had
 * at the computer. The picture now arrives as a key in the list and bytes on request;
 * these pin both halves, and that a key from the computer cannot name a file outside
 * the cache.
 */
class PersonalityPicturesTest {

    @Test
    fun `the list carries a picture key when there is one`() {
        val state = parsePersonalityState(
            Json.parseToJsonElement(
                """
                {
                  "active": null,
                  "personalities": [
                    { "id": "mine", "name": "Trevor", "role": "", "tint": "accent", "image": "3f2a.png" },
                    { "id": "builtin:direct", "name": "Vale", "role": "Direct.", "tint": "accent", "image": null },
                    { "id": "old", "name": "Pip", "role": "", "tint": "violet" }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("3f2a.png", null, null), state.personalities.map { it.image })
    }

    @Test
    fun `picture bytes are read from the object or an envelope`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        val encoded = Base64.getEncoder().encodeToString(png)

        val bare = Json.parseToJsonElement("""{"mimeType":"image/png","base64":"$encoded"}""")
        val wrapped = Json.parseToJsonElement(
            """{"ok":true,"value":{"mimeType":"image/png","base64":"$encoded"}}""",
        )

        assertArrayEquals(png, parsePersonalityPicture(bare))
        assertArrayEquals(png, parsePersonalityPicture(wrapped))
    }

    @Test
    fun `no picture, or a garbled one, is null rather than a crash`() {
        assertNull(parsePersonalityPicture(JsonNull))
        assertNull(parsePersonalityPicture(null))
        assertNull(parsePersonalityPicture(Json.parseToJsonElement("""{"base64":""}""")))
        assertNull(parsePersonalityPicture(Json.parseToJsonElement("""{"base64":"%%%not base64"}""")))
    }

    @Test
    fun `a key becomes a file name only when it cannot escape the cache`() {
        assertEquals("3f2a-91.png", cacheFileName("3f2a-91.png"))

        assertNull(cacheFileName("../settings.json"))
        assertNull(cacheFileName("a/b.png"))
        assertNull(cacheFileName("a\\b.png"))
        assertNull(cacheFileName(".hidden"))
        assertNull(cacheFileName(""))
    }
}
