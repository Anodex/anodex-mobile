package dev.anodex.mobile.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Test

/** Reading `conversations:search`: which chats matched on what was said, and where. */
class MessageMatchTest {

    @Test
    fun `matches come through in the computer's order with their passages`() {
        val matches = parseMessageMatches(
            Json.parseToJsonElement(
                """[
                    {"conversationId":"nebula","excerpt":"Added a volumetric\n fog pass"},
                    {"conversationId":"email","excerpt":"Neither needs a reply."}
                ]""",
            ),
        )

        assertEquals(
            listOf(
                MessageMatch("nebula", "Added a volumetric fog pass"),
                MessageMatch("email", "Neither needs a reply."),
            ),
            matches,
        )
    }

    @Test
    fun `an envelope is read too, and a row without an id is skipped`() {
        val matches = parseMessageMatches(
            Json.parseToJsonElement("""{"ok":true,"value":[{"excerpt":"orphan"},{"conversationId":"c1"}]}"""),
        )

        assertEquals(listOf(MessageMatch("c1", "")), matches)
    }

    @Test
    fun `nothing readable is no matches`() {
        assertEquals(emptyList<MessageMatch>(), parseMessageMatches(JsonNull))
        assertEquals(emptyList<MessageMatch>(), parseMessageMatches(null))
    }
}
