package dev.anodex.mobile.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pictures on messages that live on the computer: what a message says it has, and the bytes. */
class RemotePicturesTest {

    @Test
    fun `attachments read back keep their order, kind and size, with no path`() {
        val files = parseRemoteAttachments(
            Json.parseToJsonElement(
                """[{"name":"shot.png","kind":"image","sizeBytes":1200},{"name":"notes.md","kind":"text","sizeBytes":3}]""",
            ),
        )

        assertEquals(listOf("shot.png", "notes.md"), files.map { it.name })
        assertEquals(listOf(true, false), files.map { it.isImage })
        assertEquals(1200L, files[0].sizeBytes)
        assertTrue(files.all { it.fromComputer && it.path.isEmpty() && it.localUri == null })
    }

    @Test
    fun `a message from an older computer has no attachments rather than failing`() {
        assertTrue(parseRemoteAttachments(null).isEmpty())
        assertTrue(parseRemoteAttachments(Json.parseToJsonElement("\"nope\"")).isEmpty())
    }

    @Test
    fun `a preview decodes, with or without the envelope`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val base64 = java.util.Base64.getEncoder().encodeToString(bytes)

        assertArrayEquals(bytes, parseAttachmentPreview(Json.parseToJsonElement("""{"mimeType":"image/jpeg","base64":"$base64"}""")))
        assertArrayEquals(bytes, parseAttachmentPreview(Json.parseToJsonElement("""{"ok":true,"value":{"base64":"$base64"}}""")))
    }

    @Test
    fun `no picture is null`() {
        assertNull(parseAttachmentPreview(null))
        assertNull(parseAttachmentPreview(Json.parseToJsonElement("""{"ok":true,"value":null}""")))
        assertNull(parseAttachmentPreview(Json.parseToJsonElement("""{"base64":"!!!not base64"}""")))
    }
}
