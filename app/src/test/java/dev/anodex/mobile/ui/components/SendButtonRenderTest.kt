package dev.anodex.mobile.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import dev.anodex.mobile.ui.theme.AnodexTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one round control in the app, and the one copy of it.
 *
 * The user's words, looking at the two screens side by side: the mail
 * composer should "use the same send fractal button as the AI chat composer".
 * They were not the same. Chat wore the mark on a circle; mail drew a plain
 * outline plane in the same corner of the same kind of screen, so the two
 * places this app sends something from looked like two different apps — which
 * is the whole argument for having a control this distinctive at all.
 *
 * What is pinned here is that they cannot drift apart again, and that the
 * states still say what they are: a control that commits work to another
 * machine has to look inert when it cannot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SendButtonRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val taps = mutableListOf<String>()

    @Test
    fun `it sends`() {
        compose.setContent {
            AnodexTheme(darkTheme = true) { SendButton { taps += "send" } }
        }

        compose.onNodeWithContentDescription("Send").performClick()

        assertEquals(listOf("send"), taps)
    }

    @Test
    fun `inert when there is nothing to send`() {
        // Present and plainly dead, rather than gone. A control that vanishes
        // when the field is empty takes the layout with it, and the row
        // reflows on the first keystroke.
        compose.setContent {
            AnodexTheme(darkTheme = true) {
                SendButton(enabled = false) { taps += "send" }
            }
        }

        compose.onNodeWithContentDescription("Send").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").performClick()

        assertEquals(emptyList<String>(), taps)
    }

    @Test
    fun `stopping is still reachable while a turn is running`() {
        // Chat's second state. `enabled` is false mid-stream -- there is
        // nothing to send -- and the button must still take the tap, because
        // stopping is the only thing left to do with it.
        compose.setContent {
            AnodexTheme(darkTheme = true) {
                SendButton(enabled = false, stop = true) { taps += "stop" }
            }
        }

        compose.onNodeWithContentDescription("Stop").performClick()

        assertEquals(listOf("stop"), taps)
    }

    @Test
    fun `it says which state it is in`() {
        // The mail composer arms before it sends, and renames the control
        // rather than only recolouring a glyph nobody is looking at.
        compose.setContent {
            AnodexTheme(darkTheme = true) {
                SendButton(label = "Tap again to send") {}
            }
        }

        compose.onNodeWithContentDescription("Tap again to send").assertIsDisplayed()
    }

    /**
     * Nobody draws their own.
     *
     * The structural half, and the one that answers the original complaint.
     * Two screens quietly drawing their own version of the same control is
     * how they came to disagree, and a render test on the shared component
     * would have passed happily while the mail composer ignored it.
     */
    @Test
    fun `both composers use the shared button`() {
        for (screen in listOf("ChatScreen.kt", "ComposeMailScreen.kt")) {
            val source = read("ui/screens/$screen")
            assertTrue(
                "$screen should use the shared SendButton",
                source.contains("import dev.anodex.mobile.ui.components.SendButton"),
            )
            assertTrue(
                "$screen draws its own send button again",
                !source.contains("private fun SendButton("),
            )
        }
    }

    private fun read(relative: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/java/dev/anodex/mobile/$relative")
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("Could not find $relative from ${File("").absolutePath}")
    }
}
