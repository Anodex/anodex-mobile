package dev.anodex.mobile.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeRight
import dev.anodex.mobile.email.EmailThread
import dev.anodex.mobile.email.MailSwipeAction
import dev.anodex.mobile.ui.theme.AnodexTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Acting on several messages at once.
 *
 * The selection is the only state in this app that names rows the user is
 * about to do something irreversible-looking to, so what is pinned here is the
 * set of ways it can point at the wrong ones: a tap that opens a message
 * instead of picking it, a swipe arriving mid-selection, a selection outliving
 * the rows it was built from, and a back press that leaves the mail rather than
 * the mode.
 *
 * Driven through the interface rather than through a hoisted flag, because the
 * selection deliberately lives inside the list -- a test that set it directly
 * would be testing a thing no user can reach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelectManyRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val archived = mutableListOf<List<String>>()
    private val deleted = mutableListOf<List<String>>()
    private val readState = mutableListOf<Pair<List<String>, Boolean>>()
    private val opened = mutableListOf<String>()
    private val swiped = mutableListOf<Pair<String, MailSwipeAction>>()

    private fun thread(id: String, subject: String, unread: Boolean = false) = EmailThread(
        id = id,
        accountId = "a1",
        subject = subject,
        from = "Ada <ada@example.com>",
        snippet = "The numbers are attached.",
        updatedAtEpochMs = 1_760_000_000_000,
        unread = unread,
        starred = false,
        attachmentCount = 0,
        messageCount = 1,
    )

    private fun show(threads: List<EmailThread>) {
        compose.setContent {
            AnodexTheme(darkTheme = true) {
                InboxList(
                    threads = threads,
                    loading = false,
                    configured = true,
                    onOpen = { opened += it.id },
                    onSwipe = { t, a -> swiped += t.id to a },
                    onArchiveMany = { archived += it.map { t -> t.id } },
                    onDeleteMany = { deleted += it.map { t -> t.id } },
                    onSetManyUnread = { t, u -> readState += t.map { it.id } to u },
                    nowEpochMs = 1_760_000_100_000,
                )
            }
        }
    }

    private val two = listOf(thread("t1", "Quarterly report"), thread("t2", "Lunch Tuesday"))

    @Test
    fun `a long press starts a selection and the header counts it`() {
        show(two)

        compose.onNodeWithText("Quarterly report").performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithText("1 selected").assertIsDisplayed()
    }

    @Test
    fun `once selecting, a tap adds instead of opening`() {
        // The bug this exists for: tapping the second message to add it and
        // being taken into it instead, losing the selection to go and read
        // something nobody asked to read.
        show(two)

        compose.onNodeWithText("Quarterly report").performTouchInput { longClick() }
        compose.onNodeWithText("Lunch Tuesday").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("2 selected").assertIsDisplayed()
        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun `archiving takes everything selected, once`() {
        show(two)

        compose.onNodeWithText("Quarterly report").performTouchInput { longClick() }
        compose.onNodeWithText("Lunch Tuesday").performClick()
        compose.onNodeWithContentDescription("Archive").performClick()
        compose.waitForIdle()

        assertEquals(listOf(listOf("t1", "t2")), archived)
    }

    @Test
    fun `deleting takes everything selected, once`() {
        show(two)

        compose.onNodeWithText("Quarterly report").performTouchInput { longClick() }
        compose.onNodeWithText("Lunch Tuesday").performClick()
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.waitForIdle()

        assertEquals(listOf(listOf("t1", "t2")), deleted)
    }

    @Test
    fun `the read control offers whichever way changes all of them`() {
        // A mixed selection reads as unread, because clearing a screenful is
        // what this is for and the goal is to end with no dots. Only when
        // every one of them is already read does it offer the other way.
        show(listOf(thread("t1", "Quarterly report", unread = true), thread("t2", "Lunch Tuesday")))

        compose.onNodeWithText("Quarterly report").performTouchInput { longClick() }
        compose.onNodeWithText("Lunch Tuesday").performClick()
        compose.onNodeWithContentDescription("Mark read").performClick()
        compose.waitForIdle()

        assertEquals(listOf(listOf("t1", "t2") to false), readState)
    }

    @Test
    fun `all of them already read offers unread instead`() {
        show(two)

        compose.onNodeWithText("Quarterly report").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Mark unread").performClick()
        compose.waitForIdle()

        assertEquals(listOf(listOf("t1") to true), readState)
    }

    @Test
    fun `acting ends the selection`() {
        show(two)

        compose.onNodeWithText("Quarterly report").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Archive").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Inbox").assertIsDisplayed()
    }

    @Test
    fun `done leaves the selection and puts the header back`() {
        show(two)

        compose.onNodeWithText("Quarterly report").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Done selecting").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Inbox").assertIsDisplayed()
        assertEquals(emptyList<List<String>>(), archived)
    }

    @Test
    fun `a swipe does nothing while a selection is live`() {
        // Two controls answering different questions at once: the swipe acts on
        // the row under the thumb, the header acts on the set. A swipe landing
        // here would archive one message and leave the selection standing,
        // which is not what either gesture meant.
        show(two)

        compose.onNodeWithText("Quarterly report").performTouchInput { longClick() }
        compose.onNodeWithText("Lunch Tuesday").performTouchInput { swipeRight() }
        compose.waitForIdle()

        assertEquals(emptyList<Pair<String, MailSwipeAction>>(), swiped)
        compose.onNodeWithText("1 selected").assertIsDisplayed()
    }

    @Test
    fun `starting a selection does not move the rows`() {
        // The one this file exists for, and the only bug here no test found.
        //
        // The first version of this screen dropped the search field and the
        // folder strip while a selection was live, on the reasoning that
        // neither is what anyone is about to use. Driving it on the phone, the
        // whole list jumped up by the height of the search field the instant
        // the long press landed -- and the next tap, aimed at the row below,
        // landed on the row after it. Selecting the wrong message while the
        // toolbar is offering Delete.
        //
        // Measured rather than inferred. Asserting that the search field is
        // still on screen would pass just as well with the rows an inch higher,
        // and it is the rows that the thumb is aiming at.
        compose.setContent {
            AnodexTheme(darkTheme = true) {
                InboxList(
                    threads = two,
                    loading = false,
                    configured = true,
                    onOpen = {},
                    query = "",
                    onQueryChange = {},
                    // Configured the way the app configures it, Write button
                    // and all. A header with no control in it is shorter than
                    // one with a 48dp control, so a test that leaves the button
                    // out measures a screen nobody has and reports a 13dp shift
                    // that does not exist.
                    onCompose = {},
                    onArchiveMany = { archived += it.map { t -> t.id } },
                    nowEpochMs = 1_760_000_100_000,
                )
            }
        }

        val before = compose.onNodeWithText("Lunch Tuesday").getBoundsInRoot().top

        compose.onNodeWithText("Quarterly report").performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithText("1 selected").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithText("Lunch Tuesday").getBoundsInRoot().top)
    }

    @Test
    fun `a selection cannot outlive the rows it points at`() {
        // The failure mode a set of ids has and a list of rows does not: the
        // mailbox refreshes, a message is gone, and the toolbar is still
        // offering to delete it. Here the ids are resolved against the rows on
        // every pass, so the count falls and the mode ends on its own.
        val rows = androidx.compose.runtime.mutableStateOf(two)
        compose.setContent {
            AnodexTheme(darkTheme = true) {
                InboxList(
                    threads = rows.value,
                    loading = false,
                    configured = true,
                    onOpen = {},
                    onArchiveMany = { archived += it.map { t -> t.id } },
                    nowEpochMs = 1_760_000_100_000,
                )
            }
        }

        compose.onNodeWithText("Quarterly report").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("1 selected").assertIsDisplayed()

        rows.value = listOf(thread("t2", "Lunch Tuesday"))
        compose.waitForIdle()

        compose.onNodeWithText("Inbox").assertIsDisplayed()
    }

    @Test
    fun `no bulk handlers means no selection at all`() {
        // The read-only inbox the previews and the unpaired states draw. A long
        // press there should do nothing rather than open a toolbar whose
        // buttons are all missing.
        compose.setContent {
            AnodexTheme(darkTheme = true) {
                InboxList(
                    threads = two,
                    loading = false,
                    configured = true,
                    onOpen = { opened += it.id },
                    nowEpochMs = 1_760_000_100_000,
                )
            }
        }

        compose.onNodeWithText("Quarterly report").performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithText("Inbox").assertIsDisplayed()
        assertTrue(archived.isEmpty() && deleted.isEmpty())
    }
}
