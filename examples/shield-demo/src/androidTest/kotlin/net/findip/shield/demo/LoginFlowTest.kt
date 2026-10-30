package net.findip.shield.demo

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The demo on an emulator: a login, a short trip to the background, another
 * login in the same session. CI runs it against ci/fake-shield.mjs.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LoginFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun loginSurvivesAShortTripToTheBackground() {
        rule.onNodeWithText("Email").performTextInput("ci-user@example.com")
        rule.onNodeWithText("Password (never sent to Shield)").performTextInput("ci-password-value")
        val first = logIn()

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressHome()
        Thread.sleep(3_000)
        // Back to the existing task, as tapping the launcher icon does.
        device.executeShellCommand("monkey -p net.findip.shield.demo -c android.intent.category.LAUNCHER 1")
        rule.waitUntilAtLeastOneExists(hasText("Log out"), 15_000)
        rule.onNodeWithText("Log out").performClick()

        assertEquals("a short trip to the background changed the session", first, logIn())
    }

    private fun logIn(): String {
        rule.onNodeWithText("Log in").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Log out"), 15_000)
        rule.waitUntilAtLeastOneExists(hasText("sess_", substring = true), 5_000)
        val node = rule.onNodeWithText("sess_", substring = true).fetchSemanticsNode()
        return node.config[SemanticsProperties.Text].joinToString("") { it.text }
    }
}
