package dev.anodex.mobile.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.workspace.FileContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The step between a tap and a deleted file.
 *
 * This is the only thing the phone does to a project that is not reading it, and
 * the owner's condition for allowing it at all was that it asks first. That
 * condition lives in one `if` in one composable, so it is exactly the kind of
 * thing a later refactor removes by accident while the feature keeps working —
 * the delete would still delete, and nothing else in the build would notice.
 *
 * So the test is not "does the dialog draw". It is: **the tap alone never
 * reaches the computer.**
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileDeleteRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private var deleted = 0

    private fun show(onDelete: (() -> Unit)? = { deleted++ }) {
        compose.setContent {
            AnodexTheme(darkTheme = true) {
                FileScreen(
                    path = "src/sim/useDragBody.ts",
                    content = FileContent.Text("export function useDragBody() {}\n"),
                    loading = false,
                    onClose = {},
                    onDelete = onDelete,
                )
            }
        }
    }

    @Test
    fun `tapping delete asks, and does not delete`() {
        show()
        compose.onNodeWithContentDescription("Delete this file").performClick()
        compose.waitForIdle()

        assertEquals("the tap must not have reached the computer", 0, deleted)
        compose.onNodeWithText("Delete useDragBody.ts?", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the question says where the file goes`() {
        // Not "are you sure". A person cannot answer that one; they can answer
        // "it goes to the Recycle Bin", because it tells them what being wrong
        // would cost them.
        show()
        compose.onNodeWithContentDescription("Delete this file").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Recycle Bin", substring = true).assertIsDisplayed()
    }

    @Test
    fun `confirming is what deletes`() {
        show()
        compose.onNodeWithContentDescription("Delete this file").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Delete", substring = false).performClick()
        compose.waitForIdle()

        assertEquals(1, deleted)
    }

    @Test
    fun `cancelling leaves the file alone`() {
        show()
        compose.onNodeWithContentDescription("Delete this file").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        assertEquals(0, deleted)
        compose.onNodeWithText("Recycle Bin", substring = true).assertDoesNotExist()
    }

    @Test
    fun `with nowhere to send it, there is no button to press`() {
        // The viewer opened without a socket. A delete control that cannot
        // delete is worse than none: it invites the tap and then explains.
        show(onDelete = null)
        compose.onNodeWithContentDescription("Delete this file").assertDoesNotExist()
    }
}
