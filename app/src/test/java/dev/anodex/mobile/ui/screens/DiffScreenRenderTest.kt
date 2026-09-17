package dev.anodex.mobile.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.workspace.DiffRow
import dev.anodex.mobile.workspace.TurnDiff
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The screen that makes the phone's own stated condition true.
 *
 * `Checkpoints` refuses to offer restoring from a phone and gives the reason:
 * undoing an afternoon of work needs a diff in front of you, and a filename and a
 * byte count is not that. This is the diff. What it must not do is look like a
 * diff while leaving something out — the failure that matters here is silent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiffScreenRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun diff(
        rows: List<DiffRow>,
        added: Int = 0,
        removed: Int = 0,
        binary: Boolean = false,
        truncated: Boolean = false,
        kind: String = "modified",
    ) = TurnDiff(
        path = "src/sim/useDragBody.ts",
        kind = kind,
        binary = binary,
        added = added,
        removed = removed,
        rows = rows,
        truncated = truncated,
    )

    private fun show(value: TurnDiff?, loading: Boolean = false) {
        compose.setContent {
            AnodexTheme(darkTheme = true) {
                DiffScreen(
                    path = "src/sim/useDragBody.ts",
                    diff = value,
                    loading = loading,
                    onClose = {},
                    onOpenFile = {},
                )
            }
        }
    }

    @Test
    fun `both sides of a change are on screen`() {
        show(
            diff(
                rows = listOf(
                    DiffRow(DiffRow.Kind.REMOVED, "const basis = cameraBasis()"),
                    DiffRow(DiffRow.Kind.ADDED, "const basis = useRef(cameraBasis())"),
                ),
                added = 1,
                removed = 1,
            )
        )
        compose.onNodeWithText("const basis = cameraBasis()", substring = true).assertIsDisplayed()
        compose.onNodeWithText("useRef(cameraBasis())", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the counts are in the chrome, before any scrolling`() {
        show(diff(rows = listOf(DiffRow(DiffRow.Kind.ADDED, "one")), added = 12, removed = 3))
        compose.onNodeWithText("+12").assertIsDisplayed()
        compose.onNodeWithText("−3").assertIsDisplayed()
    }

    @Test
    fun `a collapsed run says how much it is hiding`() {
        // A gap that does not say its size is a diff pretending the file is
        // shorter than it is.
        show(diff(rows = listOf(DiffRow(DiffRow.Kind.GAP, "", collapsed = 12))))
        compose.onNodeWithText("12 unchanged lines", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a cut-short diff says so on the screen`() {
        // The one that would be silent otherwise: rows stop, the screen looks
        // complete, and a person decides what to do with a change they have only
        // seen part of.
        show(
            diff(
                rows = listOf(DiffRow(DiffRow.Kind.ADDED, "one")),
                added = 4000,
                removed = 4000,
                truncated = true,
            )
        )
        compose.onNodeWithText("too big to show in full", substring = true).assertIsDisplayed()
        compose.onNodeWithText("+4000").assertIsDisplayed()
    }

    @Test
    fun `a binary file says what it is rather than drawing nothing`() {
        show(diff(rows = emptyList(), binary = true, kind = "created"))
        compose.onNodeWithText("not a text file", substring = true).assertIsDisplayed()
    }

    @Test
    fun `waiting on the computer says so`() {
        show(null, loading = true)
        compose.onNodeWithText("Reading from your computer", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the file as it stands now is one tap away`() {
        show(diff(rows = listOf(DiffRow(DiffRow.Kind.ADDED, "one"))))
        compose.onNodeWithContentDescription("Open the file as it is now").assertIsDisplayed()
    }
}
