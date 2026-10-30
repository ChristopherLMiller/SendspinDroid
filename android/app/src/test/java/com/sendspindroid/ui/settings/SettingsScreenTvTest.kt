package com.sendspindroid.ui.settings

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.sendspindroid.UserSettings
import com.sendspindroid.sendspin.protocol.AudioQuality
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.theme.SendSpinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings driven by a remote: where focus starts, that Down from the Theme
 * row reaches Album colors and then the sync offset, and which controls are
 * focus stops at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class SettingsScreenTvTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val viewModel = SettingsViewModel(ApplicationProvider.getApplicationContext<Application>())

    // Not hasSetTextAction(): on a TV the field is read-only until OK, which leaves out SetText.
    private val textField = SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)

    /** Input sessions started by the screen's text fields: each one opens the keyboard. */
    private var keyboardsOpened = 0

    private fun show(formFactor: FormFactor, canShare: Boolean = true) {
        composeTestRule.setContent {
            InterceptPlatformTextInput(
                interceptor = { request, nextHandler ->
                    keyboardsOpened++
                    nextHandler.startInputMethod(request)
                }
            ) {
                CompositionLocalProvider(LocalFormFactor provides formFactor) {
                    SendSpinTheme {
                        SettingsScreen(
                            viewModel = viewModel,
                            onNavigateBack = {},
                            onExportLogs = {},
                            onReportProblem = {},
                            onRestartApp = {},
                            canShare = canShare
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `on a TV focus starts on Player Name, not the back arrow`() {
        show(FormFactor.TV)

        composeTestRule.onNodeWithText("Player Name").assertIsFocused()
        composeTestRule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Back"))
        ).assertCountEquals(0)
    }

    @Test
    fun `on a TV Down from the Theme row reaches Album colors, then the sync offset row`() {
        show(FormFactor.TV)
        // The rightmost segment: the one Down used to carry past the - value + buttons.
        val dark = composeTestRule.onNodeWithText("Dark")
        dark.requestFocus()

        dark.performKeyInput { pressKey(Key.DirectionDown) }
        val albumColors = composeTestRule.onNodeWithText("Album colors")
        albumColors.assertIsFocused()

        albumColors.performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.onNodeWithText("Manual Sync Offset").assertIsFocused()
    }

    @Test
    fun `on a TV Left and Right on the sync offset row step the offset`() {
        show(FormFactor.TV)
        val row = composeTestRule.onNodeWithText("Manual Sync Offset")
        row.performScrollTo().requestFocus()
        val before = viewModel.syncOffset.value

        row.performKeyInput { pressKey(Key.DirectionRight) }
        composeTestRule.waitForIdle()
        assertEquals(before + 10, viewModel.syncOffset.value)

        row.performKeyInput { pressKey(Key.DirectionLeft) }
        row.performKeyInput { pressKey(Key.DirectionLeft) }
        composeTestRule.waitForIdle()
        assertEquals(before - 10, viewModel.syncOffset.value)
        // Left and Right adjust; they do not move focus off the row.
        row.assertIsFocused()
    }

    @Test
    @Config(qualifiers = DIALOG_TEXT_FIELD_SCREEN)
    fun `on a TV OK on the sync offset row opens the dialog on its text field`() {
        show(FormFactor.TV)
        val row = composeTestRule.onNodeWithText("Manual Sync Offset")
        row.performScrollTo().requestFocus()

        row.performKeyInput { pressKey(Key.DirectionCenter) }

        composeTestRule.onNodeWithText("Sync Offset (ms)").assertExists()
        composeTestRule.onNode(textField).assertIsFocused()
        assertEquals(0, keyboardsOpened)
    }

    // A SelectionContainer is a focus stop with no focus semantics of its
    // own, so these move focus with the D-pad to find it.

    @Test
    fun `on a TV the pairing token and client id are not focus stops`() {
        show(FormFactor.TV)
        composeTestRule.onNodeWithText("Player Name").assertIsFocused()

        // Down to the QR code, then Down past the token and the client id.
        composeTestRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }

        composeTestRule.onNodeWithText("Dynamic pairing code").assertIsFocused()
        composeTestRule.onAllNodesWithText("Copy pairing token").assertCountEquals(0)
        composeTestRule.onNodeWithText("Scan with your phone", substring = true).assertExists()
    }

    @Test
    fun `on a phone the pairing token can still be selected and copied`() {
        show(FormFactor.PHONE)
        composeTestRule.onNodeWithText("Player Name").requestFocus()

        // The token's SelectionContainer takes focus, and shows in no node.
        composeTestRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.onAllNodes(isFocused()).assertCountEquals(0)

        composeTestRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.onNodeWithText("Copy pairing token").assertIsFocused()
    }

    @Test
    fun `on a TV the Head Unit layout and Full Screen Mode are not offered`() {
        show(FormFactor.TV)

        composeTestRule.onAllNodesWithText("Layout Mode").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Full Screen Mode").assertCountEquals(0)
    }

    @Test
    fun `without a share target Report a problem and Export Debug Logs are hidden`() {
        show(FormFactor.TV, canShare = false)

        composeTestRule.onAllNodesWithText("Report a problem").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Export Debug Logs").assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = DIALOG_TEXT_FIELD_SCREEN)
    fun `on a TV the player name dialog opens on its text field`() {
        show(FormFactor.TV)
        composeTestRule.onNodeWithText("Player Name")
            .performKeyInput { pressKey(Key.DirectionCenter) }

        composeTestRule.onNode(textField).assertIsFocused()
    }

    @Test
    @Config(qualifiers = DIALOG_TEXT_FIELD_SCREEN)
    fun `on a TV the dialog's text field opens the keyboard on OK, not on focus`() {
        show(FormFactor.TV)
        composeTestRule.onNodeWithText("Player Name")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        val field = composeTestRule.onNode(textField)
        field.assertIsFocused()
        // The keyboard would cover the dialog's OK and Cancel.
        assertEquals(0, keyboardsOpened)

        field.performKeyInput { pressKey(Key.DirectionCenter) }
        composeTestRule.waitForIdle()

        assertTrue(keyboardsOpened > 0)
        field.assertIsFocused()
    }

    @Test
    @Config(qualifiers = DIALOG_TEXT_FIELD_SCREEN)
    fun `on a phone the dialog's text field opens the keyboard when it takes focus`() {
        show(FormFactor.PHONE)
        composeTestRule.onNodeWithText("Player Name").performClick()

        composeTestRule.onNode(textField).performClick()
        composeTestRule.waitForIdle()

        assertTrue(keyboardsOpened > 0)
    }

    @Test
    fun `a quality pill saves the quality for its own network`() {
        show(FormFactor.PHONE)
        // Through the click action, as an accessibility service clicks: the
        // end pills have rounded corners on one side only, which Compose
        // hit-tests with Path.op, and Robolectric's graphics have no Path.op,
        // so a touch on them lands nowhere here. The Wi-Fi row comes first.
        fun pick(label: String, row: Int) {
            composeTestRule.onAllNodesWithText(label)[row].performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick)
            composeTestRule.waitForIdle()
        }

        pick("High", row = 0)
        pick("Normal", row = 1)

        assertEquals(AudioQuality.HIGH, viewModel.wifiAudioQuality.value)
        assertEquals(AudioQuality.HIGH, UserSettings.wifiAudioQuality)
        assertEquals(AudioQuality.NORMAL, viewModel.mobileAudioQuality.value)
        assertEquals(AudioQuality.NORMAL, UserSettings.mobileAudioQuality)

        pick("Low", row = 1)
        assertEquals(AudioQuality.LOW, UserSettings.mobileAudioQuality)
        // The other network's choice is left alone
        assertEquals(AudioQuality.HIGH, UserSettings.wifiAudioQuality)
    }

    @Test
    fun `on a TV only the Wi-Fi quality is offered`() {
        show(FormFactor.TV)

        composeTestRule.onNodeWithText("On Wi-Fi").performScrollTo().assertExists()
        composeTestRule.onAllNodesWithText("On mobile data").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Preferred Audio Codec").assertCountEquals(0)
    }

    private companion object {
        /**
         * Robolectric's default screen. On a 960x540dp one, Compose under
         * Robolectric never goes idle once a text field in a dialog has focus,
         * a bare AlertDialog with an OutlinedTextField included.
         */
        const val DIALOG_TEXT_FIELD_SCREEN = "w320dp-h470dp"
    }
}
