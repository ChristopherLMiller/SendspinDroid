package com.sendspindroid.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.main.MainActivityViewModel
import com.sendspindroid.ui.main.PlaybackState
import com.sendspindroid.ui.main.ServerListScreen
import com.sendspindroid.ui.main.components.ConnectionModeState
import com.sendspindroid.ui.theme.SendSpinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Focus across the TV app shell as screens change: the server list in the
 * side menu's frame, and Now Playing once a server is connected.
 *
 * The width is a 1080p TV's. The height is more than its 540dp, so that the
 * found server's card is laid out on screen below the welcome, as it is on a
 * TV: at 540dp here, the lazy list leaves it just off the screen, uncomposed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h720dp-land")
class TvAppShellFocusTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val viewModel = MainActivityViewModel(ApplicationProvider.getApplicationContext<Application>())

    private val kitchen = UnifiedServer(
        id = "4",
        name = "Kitchen Server",
        local = LocalConnection("192.168.1.4:8927"),
        isDiscovered = true,
    )

    /** No saved server, and one found on the network while searching. */
    private fun show() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalFormFactor provides FormFactor.TV) {
                SendSpinTheme {
                    AppShell(
                        viewModel = viewModel,
                        serverListContent = {
                            ServerListScreen(
                                savedServers = emptyList(),
                                discoveredServers = listOf(kitchen),
                                onlineSavedServerIds = emptySet(),
                                isScanning = false,
                                serverStatuses = emptyMap(),
                                reconnectInfo = emptyMap(),
                                onServerClick = {},
                                onServerLongClick = {},
                                onQuickConnectClick = {},
                                onAddServerClick = {},
                                mode = ConnectionModeState(true, "Living Room TV", null) {},
                            )
                        },
                        onPreviousClick = {},
                        onPlayPauseClick = {},
                        onNextClick = {},
                        onSwitchGroupClick = {},
                        onVolumeChange = {},
                        onSeek = {},
                        onAllowPairingClick = {},
                        onDisconnectClick = {},
                        onAddServerClick = {},
                        onStatsClick = {},
                        onSettingsClick = {},
                        onEditServerClick = {},
                        onExitAppClick = {},
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun setState(state: AppConnectionState) {
        composeTestRule.runOnUiThread { viewModel.updateConnectionState(state) }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `connecting from the server list puts focus on Play once the controls are ready`() {
        show()
        composeTestRule.onNodeWithText("Kitchen Server").requestFocus()

        // The card goes with the list, and the menu takes the focus: it
        // stays collapsed, so none of its labels show
        setState(AppConnectionState.Connecting("Kitchen Server", "192.168.1.4:8927"))
        composeTestRule.onNodeWithText("Now Playing").assertDoesNotExist()

        // Connected, before the player has a state: the controls are disabled
        setState(AppConnectionState.Connected("Kitchen Server", "192.168.1.4:8927"))
        composeTestRule.onNodeWithText("Now Playing").assertDoesNotExist()

        composeTestRule.runOnUiThread { viewModel.updatePlaybackState(false, PlaybackState.READY) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Play button").assertIsFocused()
        composeTestRule.onNodeWithText("Now Playing").assertDoesNotExist()
    }

    @Test
    fun `left from Add Your First Server opens the menu, past a found server below`() {
        show()
        val add = composeTestRule.onNodeWithText("Add Your First Server")
        add.assertIsFocused()

        add.performKeyInput { pressKey(Key.DirectionLeft) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Servers").assertIsFocused()
    }
}
