package dev.anodex.mobile.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the computer's list of installed models.
 *
 * The list arrives wrapped in the desktop's `Result` shape, so the array is a level
 * down from where it looks like it should be — getting that wrong yields an empty
 * picker rather than an error, which is the failure that hides.
 */
class ModelsTest {

    private fun parse(json: String) = parseModels(Json.parseToJsonElement(json))

    @Test
    fun `reads the models out of the result wrapper`() {
        val models = parse(
            """
            {
              "ok": true,
              "value": [
                { "path": "/m/a.gguf", "name": "Qwen3 30B", "sizeBytes": 18500000000, "quant": "Q4_K_M" },
                { "path": "/m/b.gguf", "name": "Gemma 3 27B", "sizeBytes": 16200000000 }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("Qwen3 30B", "Gemma 3 27B"), models.map { it.name })
        assertEquals("Q4_K_M", models[0].quant)
        assertEquals("", models[1].quant)
    }

    @Test
    fun `drops an entry with no path, because a load is asked for by path`() {
        val models = parse("""{ "ok": true, "value": [{ "name": "Nameless" }, { "path": "/m/a.gguf" }] }""")

        assertEquals(listOf("/m/a.gguf"), models.map { it.path })
    }

    @Test
    fun `falls back to the filename when the computer did not name it`() {
        // Windows and POSIX separators both, since the desktop runs on either.
        val windows = parse("""{ "value": [{ "path": "C:\\models\\qwen.gguf" }] }""")
        val posix = parse("""{ "value": [{ "path": "/home/me/models/qwen.gguf" }] }""")

        assertEquals("qwen.gguf", windows.single().name)
        assertEquals("qwen.gguf", posix.single().name)
    }

    @Test
    fun `an unusable answer is an empty list, not a crash`() {
        assertTrue(parseModels(null).isEmpty())
        assertTrue(parse("""{ "ok": false, "error": "nope" }""").isEmpty())
        assertTrue(parse("[]").isEmpty())
    }

    @Test
    fun `the detail line names the quant and the size, and skips what is missing`() {
        assertEquals("Q4_K_M · 8.0 GB", LocalModel("/a", "A", 8L * 1024 * 1024 * 1024, "Q4_K_M").detailLabel())
        assertEquals("8.0 GB", LocalModel("/a", "A", 8L * 1024 * 1024 * 1024).detailLabel())
        // Size unknown reads as nothing at all rather than "0 B", which would look
        // like a broken file rather than a fact the computer did not report.
        assertEquals("Q4_K_M", LocalModel("/a", "A", 0, "Q4_K_M").detailLabel())
        assertEquals("", LocalModel("/a", "A", 0).detailLabel())
    }
}
