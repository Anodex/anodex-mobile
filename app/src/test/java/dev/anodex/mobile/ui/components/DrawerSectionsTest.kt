package dev.anodex.mobile.ui.components

import dev.anodex.mobile.chat.ConversationSummary
import dev.anodex.mobile.chat.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the drawer files conversations.
 *
 * The drawer used to list every conversation under one CHATS heading while the Chat
 * index, on the same data, filed the same conversation under its workspace. Which of
 * the two a message goes to decides whether a turn edits real files, so the two lists
 * disagreeing about it is not a cosmetic difference.
 */
class DrawerSectionsTest {

    private fun conversation(
        id: String,
        projectId: String? = null,
        updatedAt: Long = 0,
        origin: String? = null,
    ) = ConversationSummary(
        id = id,
        title = "Conversation $id",
        createdAtEpochMs = 0,
        updatedAtEpochMs = updatedAt,
        messageCount = 1,
        projectId = projectId,
        origin = origin,
    )

    private val projects = listOf(
        Project("p1", "Universe Sandbox", "/work/universe"),
        Project("p2", "AnodexWeb", "/work/web"),
    )

    @Test
    fun `a workspace chat is listed under its workspace and not under chats`() {
        val sections = drawerSections(
            listOf(conversation("a", projectId = "p1")),
            projects,
            activeProjectId = null,
        )

        assertEquals(listOf("a"), sections.workspaces.single().conversations.map { it.id })
        assertEquals("Universe Sandbox", sections.workspaces.single().name)
        assertTrue(sections.chats.isEmpty())
    }

    @Test
    fun `chats holds only what belongs to no workspace`() {
        val sections = drawerSections(
            listOf(
                conversation("work", projectId = "p1"),
                conversation("talk"),
            ),
            projects,
            activeProjectId = null,
        )

        assertEquals(listOf("talk"), sections.chats.map { it.id })
    }

    @Test
    fun `the workspace the computer is in comes first`() {
        // It is the answer to "where would a message land", which is worth a glance
        // before any of the ordering by recency.
        val sections = drawerSections(
            listOf(
                conversation("recent", projectId = "p2", updatedAt = 99),
                conversation("older", projectId = "p1", updatedAt = 1),
            ),
            projects,
            activeProjectId = "p1",
        )

        assertEquals(listOf("Universe Sandbox", "AnodexWeb"), sections.workspaces.map { it.name })
    }

    @Test
    fun `the active workspace is listed even with nothing in it`() {
        // An empty row still says where you are. The list says so out loud when
        // opened, rather than expanding onto a blank.
        val sections = drawerSections(
            listOf(conversation("talk")),
            projects,
            activeProjectId = "p2",
        )

        assertEquals(listOf("AnodexWeb"), sections.workspaces.map { it.name })
        assertTrue(sections.workspaces.single().conversations.isEmpty())
    }

    @Test
    fun `the active workspace is not listed twice`() {
        val sections = drawerSections(
            listOf(conversation("a", projectId = "p1")),
            projects,
            activeProjectId = "p1",
        )

        assertEquals(1, sections.workspaces.size)
    }

    @Test
    fun `the rest of the workspaces go most recently touched first`() {
        val sections = drawerSections(
            listOf(
                conversation("old", projectId = "p1", updatedAt = 10),
                conversation("new", projectId = "p2", updatedAt = 99),
            ),
            projects,
            activeProjectId = null,
        )

        assertEquals(listOf("AnodexWeb", "Universe Sandbox"), sections.workspaces.map { it.name })
    }

    @Test
    fun `chats go most recently touched first`() {
        val sections = drawerSections(
            listOf(
                conversation("old", updatedAt = 1),
                conversation("new", updatedAt = 2),
            ),
            projects,
            activeProjectId = null,
        )

        assertEquals(listOf("new", "old"), sections.chats.map { it.id })
    }

    @Test
    fun `a workspace the phone has not heard of still gets its own row`() {
        // Added on the computer since this list was fetched. Folding its chats in
        // with the plain ones would say they cannot touch files, which is false.
        val sections = drawerSections(
            listOf(conversation("a", projectId = "unknown")),
            projects,
            activeProjectId = null,
        )

        assertEquals("Project", sections.workspaces.single().name)
        assertTrue(sections.chats.isEmpty())
    }

    @Test
    fun `an active workspace the phone cannot name is not given an empty row`() {
        // Nothing to label it with and nothing under it: "Project 0" is worse than
        // no row at all.
        val sections = drawerSections(
            listOf(conversation("talk")),
            projects,
            activeProjectId = "unknown",
        )

        assertTrue(sections.workspaces.isEmpty())
    }

    @Test
    fun `scheduled and agent runs stay out of both sections and are counted`() {
        // They write conversations exactly like a real one. Grouping them by
        // workspace would have quietly let them back into the list that was
        // deliberately cleared of them.
        val sections = drawerSections(
            listOf(
                conversation("mine", projectId = "p1"),
                conversation("run", projectId = "p1", origin = "schedule"),
                conversation("agent", origin = "agent"),
            ),
            projects,
            activeProjectId = null,
        )

        assertEquals(listOf("mine"), sections.workspaces.single().conversations.map { it.id })
        assertTrue(sections.chats.isEmpty())
        assertEquals(2, sections.machineMade)
        assertEquals(1, sections.totalMine)
    }

    @Test
    fun `every conversation a person started appears exactly once`() {
        val all = listOf(
            conversation("a", projectId = "p1", updatedAt = 10),
            conversation("b", projectId = "p2", updatedAt = 20),
            conversation("c", updatedAt = 30),
            conversation("d", projectId = "unknown", updatedAt = 40),
        )

        val sections = drawerSections(all, projects, activeProjectId = "p1")
        val placed = sections.workspaces.flatMap { it.conversations } + sections.chats

        assertEquals(all.size, placed.size)
        assertEquals(all.map { it.id }.toSet(), placed.map { it.id }.toSet())
        assertEquals(all.size, sections.totalMine)
    }

    @Test
    fun `nothing at all produces no sections`() {
        val sections = drawerSections(emptyList(), projects, activeProjectId = null)

        assertTrue(sections.workspaces.isEmpty())
        assertTrue(sections.chats.isEmpty())
        assertEquals(0, sections.totalMine)
    }
}
