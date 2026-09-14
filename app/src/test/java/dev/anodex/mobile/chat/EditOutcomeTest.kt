package dev.anodex.mobile.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** How an edit-in-place answer from the computer is read. */
class EditOutcomeTest {

    private fun read(json: String) = editOutcomeOf(Json.parseToJsonElement(json))

    @Test
    fun `a cut conversation, or one the computer never saved, is edited here`() {
        assertEquals(EditOutcome.CUT, read("""{"ok":true,"value":{"remainingMessages":2}}"""))
        assertEquals(EditOutcome.CUT, read("""{"ok":false,"error":{"code":"conversations.edit-not-found"}}"""))
    }

    @Test
    fun `a project chat and other refusals send as new, each with its own reason`() {
        assertEquals(EditOutcome.PROJECT_CHAT, read("""{"ok":false,"error":{"code":"conversations.edit-project-chat"}}"""))
        assertEquals(EditOutcome.REFUSED, read("""{"ok":false,"error":{"code":"conversations.edit-not-a-question"}}"""))
    }

    @Test
    fun `an answer that is not a result at all is an older computer`() {
        assertEquals(EditOutcome.UNSUPPORTED, editOutcomeOf(null))
        assertEquals(EditOutcome.UNSUPPORTED, read("""{"ok":false}"""))
    }
}
