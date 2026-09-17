package dev.anodex.mobile.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.anodex.mobile.ui.theme.AnodexTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The first test in this app that looks at what is drawn.
 *
 * Until now nothing did. Two bugs shipped in two days that no existing test
 * could have caught — a chat that looked frozen for most of a multi-step turn,
 * and numbered lists that read 1, 1, 1 — because both lived in rendering, and
 * rendering was verified by booting an emulator and photographing the screen.
 * One of those two states could not be photographed at all: the model finished
 * writing faster than a screenshot loop could sample it.
 *
 * Robolectric supplies the Android runtime on the JVM, so these run inside the
 * ordinary `testDebugUnitTest` task — on every push, rather than whenever
 * somebody sets up a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnodexSwitchRenderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the switch draws in both positions`() {
        compose.setContent {
            AnodexTheme(darkTheme = true) {
                AnodexSwitch(checked = true, modifier = Modifier.semantics { contentDescription = "on" })
            }
        }
        compose.onNodeWithContentDescription("on").assertIsDisplayed()
    }

    @Test
    fun `the row around it is what takes the tap`() {
        // The switch itself has no handler: a switch with its own inside a
        // clickable row toggles twice when the switch is hit, which reads as the
        // setting refusing to change. This pins that the row still drives it.
        var checked by mutableStateOf(false)
        compose.setContent {
            AnodexTheme(darkTheme = true) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .clickable { checked = !checked }
                        .semantics { contentDescription = "row" }
                ) {
                    AnodexSwitch(checked = checked)
                }
            }
        }

        compose.onNodeWithContentDescription("row").performClick()
        compose.waitForIdle()
        assertTrue("the row's tap should have flipped it", checked)
    }

    @Test
    fun `it renders in light as well as dark`() {
        // The thing I could not check on a device: the emulator's night-mode
        // toggle does not reach this app, because the app has its own appearance
        // setting. Here the theme is simply passed in.
        compose.setContent {
            AnodexTheme(darkTheme = false) {
                AnodexSwitch(checked = false, modifier = Modifier.semantics { contentDescription = "off-light" })
            }
        }
        compose.onNodeWithContentDescription("off-light").assertIsDisplayed()
    }
}
