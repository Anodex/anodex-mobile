package dev.anodex.mobile.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.anodex.mobile.chat.ChatMessage
import dev.anodex.mobile.ui.theme.AnodexTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bug this pins was reported from a real phone, and could not be caught by
 * any test that existed.
 *
 * "When chat is running it stops for a moment like the AI is thinking, then
 * another chat appears, then it seems to be thinking for a moment, then another
 * chat appears." Those pauses were real work, and nothing on screen said so.
 *
 * The sign of life had been tied to what had *arrived* rather than to whether
 * the turn was still running: it was an `else` on "are there words yet", and it
 * also required that no tool had ever run. So a multi-step turn spent most of
 * itself looking frozen.
 *
 * `tailActivity` covers the decision. This covers the thing the user actually
 * sees — and it is the state I could not photograph on a device, because the
 * local model finished writing faster than a screenshot loop could sample it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatActivityLineRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(message: ChatMessage) {
        compose.setContent {
            AnodexTheme(darkTheme = true) {
                ChatScreen(
                    messages = listOf(message),
                    sending = message.streaming,
                    error = null,
                    onSend = {},
                )
            }
        }
    }

    @Test
    fun `a reply still being written says so, even with words already on screen`() {
        // The regression itself. Before the fix this said nothing at all: the
        // words were there, so the line that reports work had already been
        // skipped for the rest of the turn.
        show(
            ChatMessage(
                id = "1",
                role = ChatMessage.Role.ASSISTANT,
                text = "A mutex is a synchronization primitive",
                streaming = true,
            )
        )
        compose.onNodeWithText("Writing…", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a reply with nothing yet says it is thinking`() {
        show(ChatMessage(id = "1", role = ChatMessage.Role.ASSISTANT, text = "", streaming = true))
        compose.onNodeWithText("Thinking…", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a finished reply says nothing`() {
        // The other half of the rule: the line follows the turn, so it goes when
        // the turn does. A reply that keeps reporting work after it has finished
        // would be the same bug wearing the opposite face.
        show(
            ChatMessage(
                id = "1",
                role = ChatMessage.Role.ASSISTANT,
                text = "A mutex is a synchronization primitive.",
                streaming = false,
            )
        )
        compose.onNodeWithText("Writing…", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Thinking…", substring = true).assertDoesNotExist()
        compose.onNodeWithText("A mutex is a synchronization primitive.", substring = true)
            .assertIsDisplayed()
    }
}
