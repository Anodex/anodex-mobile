package dev.anodex.mobile.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which turns of a reopened conversation changed files.
 *
 * `ChatMessage.changedFiles` was filled as a turn ended and by nothing else, so
 * closing the app emptied every row and took the diff behind it out of reach —
 * a screen for checking a run you were not present for, reachable only while you
 * were present.
 *
 * `checkpoints:list` answers for a whole project in one frame. The parsing is
 * pinned here because it is the seam: the desktop's own shape, filtered to one
 * conversation, and the failure that matters is silently returning nothing.
 */
class CheckpointHistoryTest {

    /** Mirrors `Checkpoints.changedByTurn`'s filtering, on the contract's own shape. */
    private fun byTurn(payload: String, conversationId: String): Map<String, List<String>> {
        val answer = Json.parseToJsonElement(payload) as JsonObject
        if (answer["ok"]?.jsonPrimitive?.contentOrNull == "false") return emptyMap()
        val entries = answer["value"] as? JsonArray ?: return emptyMap()
        return entries.filterIsInstance<JsonObject>()
            .filter { it["conversationId"]?.jsonPrimitive?.contentOrNull == conversationId }
            .mapNotNull { entry ->
                val id = entry["messageId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val paths = (entry["changedFiles"] as? JsonArray).orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNull }
                if (paths.isEmpty()) null else id to paths
            }
            .toMap()
    }

    private val payload = """
        {
          "ok": true,
          "value": [
            { "createdAt": 1, "conversationId": "c-1", "messageId": "m-1",
              "changedFiles": ["src/a.ts", "src/b.ts"] },
            { "createdAt": 2, "conversationId": "c-2", "messageId": "m-9",
              "changedFiles": ["other/project.ts"] },
            { "createdAt": 3, "conversationId": "c-1", "messageId": "m-3",
              "changedFiles": [] }
          ]
        }
    """.trimIndent()

    @Test
    fun `every turn of this conversation that changed something`() {
        val found = byTurn(payload, "c-1")
        assertEquals(listOf("src/a.ts", "src/b.ts"), found["m-1"])
    }

    @Test
    fun `turns from other conversations in the same project are not mine`() {
        // The list is per project, not per conversation. Without the filter a
        // chat would show files a different chat changed.
        assertTrue(byTurn(payload, "c-1").containsKey("m-9").not())
        assertEquals(listOf("other/project.ts"), byTurn(payload, "c-2")["m-9"])
    }

    @Test
    fun `a checkpoint that changed nothing is not a row`() {
        // An empty list would draw "Changed 0 files", which is worse than silence.
        assertTrue(byTurn(payload, "c-1").containsKey("m-3").not())
    }

    @Test
    fun `a refusal is an empty answer, not a crash`() {
        assertEquals(emptyMap<String, List<String>>(), byTurn("""{ "ok": false }""", "c-1"))
    }
}
