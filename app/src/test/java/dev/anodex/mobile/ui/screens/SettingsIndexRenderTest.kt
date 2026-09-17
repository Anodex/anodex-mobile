package dev.anodex.mobile.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.anodex.mobile.ui.theme.AnodexTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The first screenful of Settings.
 *
 * It used to open on a column of grey cards that could have belonged to any app —
 * the owner's word for it was "dead". What replaced it is a header and a colour
 * ramp, and the part of that worth pinning is not the colour: it is that the
 * header states which computer this phone is attached to, which is a fact people
 * open this screen to check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsIndexRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(hostName: String?, hostStatus: String) {
        compose.setContent {
            AnodexTheme(darkTheme = true) {
                SettingsScreen(
                    installedVersion = "0.80.12",
                    onClose = {},
                    hostName = hostName,
                    hostStatus = hostStatus,
                )
            }
        }
    }

    @Test
    fun `the header names the computer this phone drives`() {
        show(hostName = "Gort", hostStatus = "Connected")
        compose.onNodeWithText("Anodex").assertIsDisplayed()
        // Twice over: the header says it, and so does the Remote row further
        // down. `onNode` fails on two matches rather than passing, and the second
        // is below the fold — so the header's copy is the one checked on screen.
        compose.onAllNodesWithText("Gort · Connected")[0].assertIsDisplayed()
        compose.onAllNodesWithText("Gort · Connected")[1].assertExists()
    }

    @Test
    fun `an unpaired phone says the status alone rather than an empty line`() {
        // The header is drawn before anything is known, and a blank second line
        // under the mark reads as a screen that failed to load.
        show(hostName = null, hostStatus = "Not connected")
        compose.onAllNodesWithText("Not connected", substring = true)[0].assertIsDisplayed()
    }

    @Test
    fun `every door is still on the index`() {
        // Existence rather than display: the index scrolls, and the last three
        // rows sit below the fold on a phone-sized screen. What is being checked
        // is that adding a header did not push a section off the list entirely.
        show(hostName = "Gort", hostStatus = "Connected")
        for (section in SettingsSection.entries) {
            compose.onNodeWithText(section.label).assertExists()
        }
    }
}
