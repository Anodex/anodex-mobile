package dev.anodex.mobile.files

import dev.anodex.mobile.transport.PHONE_CAPABILITIES
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedFilesTest {
    @Test
    fun `reads the shelf the computer lists, skipping what it cannot`() {
        val files = parseSharedFiles(
            Json.parseToJsonElement(
                """
                [
                  {"id":"a","name":"Friday budget.xlsx","size":7,"mimeType":"application/x","sentAt":2000},
                  {"name":"no id"},
                  "nonsense"
                ]
                """.trimIndent(),
            ),
        )
        assertEquals(listOf(SharedFile("a", "Friday budget.xlsx", 7, "application/x", 2000)), files)
    }

    @Test
    fun `an answer that is not a list is an empty shelf`() {
        assertEquals(emptyList<SharedFile>(), parseSharedFiles(Json.parseToJsonElement("{}")))
    }

    @Test
    fun `this phone says it can collect files`() {
        assertTrue(FILES_CAPABILITY in PHONE_CAPABILITIES)
        assertEquals("files.1", FILES_CAPABILITY)
    }
}
