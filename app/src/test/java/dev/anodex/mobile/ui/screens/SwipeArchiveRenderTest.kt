package dev.anodex.mobile.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import dev.anodex.mobile.email.EmailThread
import dev.anodex.mobile.ui.theme.AnodexTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pushing a message out of the inbox with a thumb.
 *
 * The gesture is worth testing for one reason above the others: it is the only
 * control in the mail list that changes the mailbox without being aimed at. A
 * swipe that reached the wrong action, or reached one in both directions when it
 * was meant to reach one, is a mistake made with a sleeve.
 *
 * So what is pinned here is the *verb*. Both directions archive. Neither
 * deletes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SwipeArchiveRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val archived = mutableListOf<String>()

    private fun thread(id: String = "t1") = EmailThread(
        id = id,
        accountId = "a1",
        subject = "Quarterly report",
        from = "Ada <ada@example.com>",
        snippet = "The numbers are attached.",
        updatedAtEpochMs = 1_760_000_000_000,
        unread = false,
        starred = false,
        attachmentCount = 0,
        messageCount = 1,
    )

    private fun show(onArchive: ((EmailThread) -> Unit)? = { archived += it.id }) {
        compose.setContent {
            AnodexTheme(darkTheme = true) {
                InboxList(
                    threads = listOf(thread()),
                    loading = false,
                    configured = true,
                    onOpen = {},
                    onArchive = onArchive,
                    nowEpochMs = 1_760_000_100_000,
                )
            }
        }
    }

    @Test
    fun `a swipe to the left archives`() {
        show()

        compose.onNodeWithText("Quarterly report").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals(listOf("t1"), archived)
    }

    @Test
    fun `a swipe to the right archives too`() {
        // Both directions do the same thing, which is what Gmail does out of the
        // box. A second verb hidden behind the other direction is a verb nobody
        // discovers and everybody triggers.
        show()

        compose.onNodeWithText("Quarterly report").performTouchInput { swipeRight() }
        compose.waitForIdle()

        assertEquals(listOf("t1"), archived)
    }

    @Test
    fun `rows stay put where there is nothing to archive with`() {
        // Search results and any future read-only listing. A gesture that
        // silently does nothing is worse than no gesture.
        show(onArchive = null)

        compose.onNodeWithText("Quarterly report").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals(emptyList<String>(), archived)
        compose.onNodeWithText("Quarterly report").assertIsDisplayed()
    }
}
