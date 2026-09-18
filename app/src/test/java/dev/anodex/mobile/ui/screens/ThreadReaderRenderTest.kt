package dev.anodex.mobile.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.anodex.mobile.email.EmailNote
import dev.anodex.mobile.ui.theme.AnodexTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a conversation with nothing in it looks like.
 *
 * It looked like nothing. A header bar, a Back button, and a screen of black
 * underneath — no sentence, no icon, no clue. Two of the five conversations in a
 * real inbox opened exactly like that, and the app's silence is most of why it
 * went unreported for weeks: there was nothing to report except "it doesn't
 * work".
 *
 * The screen is the last place this can be caught. Everything above it — the
 * provider, the wire, the view model — can each answer "nothing" for reasons
 * that look fine from where they stand, and a reader that draws nothing agrees
 * with all of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadReaderRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun note(subject: String = "Quarterly report") = EmailNote(
        id = "m1",
        threadId = "t1",
        from = "Ada <ada@example.com>",
        subject = subject,
        body = "The numbers are attached.",
        bodyHtml = null,
        to = listOf("me@example.com"),
        cc = emptyList(),
        dateEpochMs = 1_760_000_000_000,
        attachmentCount = 0,
    )

    private fun show(notes: List<EmailNote>, loading: Boolean = false, error: String? = null) {
        compose.setContent {
            AnodexTheme(darkTheme = true) {
                ThreadReader(notes = notes, loading = loading, onClose = {}, error = error)
            }
        }
    }

    @Test
    fun `an empty conversation says so`() {
        show(notes = emptyList())

        compose.onNodeWithText("This conversation would not open").assertIsDisplayed()
    }

    @Test
    fun `the computer's own words are what reaches the screen`() {
        // The desktop knows which mailbox, which provider and which account.
        // Replacing that with a sentence written here throws away the only
        // description anybody could act on.
        show(notes = emptyList(), error = "Could not open that conversation.")

        compose.onNodeWithText("Could not open that conversation.").assertIsDisplayed()
    }

    @Test
    fun `waiting is not the same as empty`() {
        // Said differently on purpose: one is a conversation still arriving, the
        // other is one that is not coming. Showing the failure while the request
        // is still in flight is a lie that resolves itself, which is worse than
        // either.
        show(notes = emptyList(), loading = true)

        compose.onNodeWithText("Opening…").assertIsDisplayed()
    }

    @Test
    fun `a conversation that arrived is drawn, not explained`() {
        show(notes = listOf(note()))

        compose.onNodeWithText("Quarterly report").assertIsDisplayed()
        compose.onNodeWithText("The numbers are attached.").assertIsDisplayed()
    }
}
