package dev.anodex.mobile.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where an opened conversation is filed.
 *
 * Seen on the test phone: a plain chat opened from search and renamed moved into the
 * project the computer had open. Opening took the project from the drawer summary
 * *or else the active project*, and a plain chat's project is null — so the fallback
 * always won, the next save refiled the chat, and its turns ran against that
 * project's files. The conversation's own answer is the only one to use.
 */
class OpenedConversationTest {

    private fun open(json: String) = parseOpenedConversation(Json.parseToJsonElement(json).jsonObject) {
        ChatMessage(it["id"].toString(), ChatMessage.Role.USER, "")
    }

    @Test
    fun `a plain chat opens with no project, not an inherited one`() {
        val opened = open("""{"id":"c1","projectId":null,"title":"Check Unread Email Replies","messages":[]}""")

        assertNull(opened.projectId)
        assertEquals("Check Unread Email Replies", opened.storedTitle)
    }

    @Test
    fun `a project chat keeps its project`() {
        assertEquals("p-nebula2", open("""{"id":"c1","projectId":"p-nebula2","messages":[]}""").projectId)
    }

    @Test
    fun `turns and the real creation time come through`() {
        val opened = open(
            """{"id":"c1","createdAt":1789274000000,"messages":[{"id":"a"},{"id":"b"}]}""",
        )

        assertEquals(2, opened.messages.size)
        assertEquals(1_789_274_000_000L, opened.createdAtEpochMs)
    }

    @Test
    fun `missing fields are absent rather than invented`() {
        val opened = open("""{"id":"c1"}""")

        assertNull(opened.projectId)
        assertNull(opened.storedTitle)
        assertNull(opened.createdAtEpochMs)
        assertEquals(0, opened.messages.size)
    }
}
