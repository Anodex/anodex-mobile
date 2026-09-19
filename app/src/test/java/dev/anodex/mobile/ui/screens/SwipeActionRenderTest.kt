package dev.anodex.mobile.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import dev.anodex.mobile.email.EmailThread
import dev.anodex.mobile.email.MailSwipeAction
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
 * Worth pinning because this is the only control in the mail list that changes
 * the mailbox without being aimed at — a swipe that reached the wrong action is
 * a mistake made with a sleeve. And the two directions now do *different*
 * things, which is exactly the arrangement where getting them the wrong way
 * round deletes something somebody meant to file.
 *
 * So what is tested is the wiring from direction to verb, including that the
 * reader's own choice is what decides it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SwipeActionRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val done = mutableListOf<Pair<String, MailSwipeAction>>()

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

    private fun show(
        right: MailSwipeAction = MailSwipeAction.DELETE,
        left: MailSwipeAction = MailSwipeAction.ARCHIVE,
        onSwipe: ((EmailThread, MailSwipeAction) -> Unit)? = { t, a -> done += t.id to a },
    ) {
        compose.setContent {
            AnodexTheme(darkTheme = true) {
                InboxList(
                    threads = listOf(thread()),
                    loading = false,
                    configured = true,
                    onOpen = {},
                    onSwipe = onSwipe,
                    swipeRight = right,
                    swipeLeft = left,
                    nowEpochMs = 1_760_000_100_000,
                )
            }
        }
    }

    @Test
    fun `the defaults are throw away one way and file the other`() {
        show()

        compose.onNodeWithText("Quarterly report").performTouchInput { swipeRight() }
        compose.waitForIdle()

        assertEquals(listOf("t1" to MailSwipeAction.DELETE), done)
    }

    @Test
    fun `the other direction files it`() {
        show()

        compose.onNodeWithText("Quarterly report").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals(listOf("t1" to MailSwipeAction.ARCHIVE), done)
    }

    @Test
    fun `the reader's choice is what decides, not the direction`() {
        // The whole reason this is a setting. Somebody who has swiped right to
        // archive in another mail client for five years will set it that way,
        // and the gesture has to follow them rather than the other way round.
        show(right = MailSwipeAction.ARCHIVE, left = MailSwipeAction.DELETE)

        compose.onNodeWithText("Quarterly report").performTouchInput { swipeRight() }
        compose.waitForIdle()

        assertEquals(listOf("t1" to MailSwipeAction.ARCHIVE), done)
    }

    @Test
    fun `a direction set to nothing does nothing`() {
        // Not padding. A swipe is a gesture a sleeve can start, and somebody
        // bitten once should be able to switch a side off rather than choose
        // the lesser of two acts they did not want.
        show(right = MailSwipeAction.NOTHING)

        compose.onNodeWithText("Quarterly report").performTouchInput { swipeRight() }
        compose.waitForIdle()

        assertEquals(emptyList<Pair<String, MailSwipeAction>>(), done)
        compose.onNodeWithText("Quarterly report").assertIsDisplayed()
    }

    @Test
    fun `rows stay put where there is nothing to act with`() {
        // Search results and any future read-only listing. A gesture that
        // silently does nothing is worse than no gesture.
        show(onSwipe = null)

        compose.onNodeWithText("Quarterly report").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals(emptyList<Pair<String, MailSwipeAction>>(), done)
        compose.onNodeWithText("Quarterly report").assertIsDisplayed()
    }
}
