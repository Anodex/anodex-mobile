package dev.anodex.mobile.ui.screens

import dev.anodex.mobile.chat.ConversationSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the conversation list is arranged.
 *
 * The grouping is the whole value of this screen. A flat list sorted by time is
 * technically correct and useless: it puts a conversation that edits real files
 * directly next to one that only talks, with nothing to tell them apart — and which
 * of the two you are typing into is the largest difference in the app.
 */
class ConversationGroupingTest {

    private fun conversation(
        id: String,
        projectId: String? = null,
        updatedAt: Long = 0,
    ) = ConversationSummary(
        id = id,
        title = "Conversation $id",
        createdAtEpochMs = 0,
        updatedAtEpochMs = updatedAt,
        messageCount = 1,
        projectId = projectId,
    )

    private val names = mapOf("p1" to "Universe Sandbox", "p2" to "Anodex")

    @Test
    fun `the open conversation comes first, under its own heading`() {
        val groups = groupConversations(
            listOf(
                conversation("a", projectId = "p1", updatedAt = 100),
                conversation("open", projectId = "p1", updatedAt = 1),
            ),
            activeId = "open",
            projectNames = names,
        )

        assertEquals("Active now", groups.first().label)
        assertEquals(listOf("open"), groups.first().conversations.map { it.id })
    }

    @Test
    fun `the open conversation is not also listed under its project`() {
        // Listing it twice makes the list look longer than it is and gives the same
        // conversation two rows that mean different things.
        val groups = groupConversations(
            listOf(conversation("open", projectId = "p1")),
            activeId = "open",
            projectNames = names,
        )

        assertEquals(1, groups.size)
        assertEquals(1, groups.sumOf { it.conversations.size })
    }

    @Test
    fun `projects are grouped and labelled by name`() {
        val groups = groupConversations(
            listOf(
                conversation("a", projectId = "p1", updatedAt = 10),
                conversation("b", projectId = "p2", updatedAt = 20),
                conversation("c", projectId = "p1", updatedAt = 30),
            ),
            activeId = null,
            projectNames = names,
        )

        val universe = groups.single { it.label == "Universe Sandbox" }
        assertEquals(listOf("c", "a"), universe.conversations.map { it.id })
        assertTrue(universe.isProject)
    }

    @Test
    fun `the most recently touched project comes first`() {
        val groups = groupConversations(
            listOf(
                conversation("old", projectId = "p1", updatedAt = 10),
                conversation("new", projectId = "p2", updatedAt = 99),
            ),
            activeId = null,
            projectNames = names,
        )

        assertEquals(listOf("Anodex", "Universe Sandbox"), groups.map { it.label })
    }

    @Test
    fun `plain chats go last, under Chats`() {
        // Last because they cannot change anything. Work outranks talking in a list
        // somebody is scanning to get back to what they were doing.
        val groups = groupConversations(
            listOf(
                conversation("chat", projectId = null, updatedAt = 999),
                conversation("work", projectId = "p1", updatedAt = 1),
            ),
            activeId = null,
            projectNames = names,
        )

        assertEquals("Chats", groups.last().label)
        assertEquals(listOf("chat"), groups.last().conversations.map { it.id })
    }

    @Test
    fun `a project the phone has not heard of still gets its own group`() {
        // Added on the computer since this list was fetched. Folding it in with the
        // plain chats would say it cannot touch files, which is false.
        val groups = groupConversations(
            listOf(conversation("a", projectId = "unknown")),
            activeId = null,
            projectNames = names,
        )

        assertEquals(1, groups.size)
        assertTrue(groups.single().isProject)
        assertEquals("Project", groups.single().label)
    }

    @Test
    fun `no empty groups are emitted`() {
        // A heading with nothing under it reads as something having failed to load.
        val groups = groupConversations(
            listOf(conversation("a", projectId = null)),
            activeId = null,
            projectNames = names,
        )

        assertEquals(listOf("Chats"), groups.map { it.label })
        assertTrue(groups.none { it.conversations.isEmpty() })
    }

    @Test
    fun `an empty list produces no groups at all`() {
        assertEquals(emptyList<ConversationGroup>(), groupConversations(emptyList(), null, names))
    }

    @Test
    fun `an activeId that matches nothing is simply ignored`() {
        // The desktop can have a conversation open that this list has not caught up
        // with. That must not cost the user the rest of the list.
        val groups = groupConversations(
            listOf(conversation("a", projectId = null)),
            activeId = "gone",
            projectNames = names,
        )

        assertEquals(listOf("Chats"), groups.map { it.label })
        assertEquals(listOf("a"), groups.single().conversations.map { it.id })
    }

    @Test
    fun `every conversation appears exactly once`() {
        val all = listOf(
            conversation("open", projectId = "p1", updatedAt = 5),
            conversation("a", projectId = "p1", updatedAt = 10),
            conversation("b", projectId = "p2", updatedAt = 20),
            conversation("c", projectId = null, updatedAt = 30),
            conversation("d", projectId = "unknown", updatedAt = 40),
        )

        val placed = groupConversations(all, "open", names).flatMap { it.conversations }

        assertEquals(all.size, placed.size)
        assertEquals(all.map { it.id }.toSet(), placed.map { it.id }.toSet())
    }
}
