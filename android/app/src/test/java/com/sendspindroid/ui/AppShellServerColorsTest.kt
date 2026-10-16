package com.sendspindroid.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.sendspin.protocol.ColorState
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.main.MainActivityViewModel
import com.sendspindroid.ui.theme.SendSpinTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Where the server's palette shows: the connected Now Playing shell with its
 * top bar, and on a TV the frame around Now Playing while connected. The
 * server list keeps the Material colors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppShellServerColorsTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val viewModel = MainActivityViewModel(ApplicationProvider.getApplicationContext<Application>())

    private val background = Color(0xFF6C291A)
    private val palette = ColorState(
        timestamp = 1,
        backgroundDark = background.toArgb(),
        backgroundLight = 0xFFE2A498.toInt(),
        onDark = 0xFFEED6AA.toInt(),
        onLight = 0xFF221E3E.toInt(),
    )

    private fun show(formFactor: FormFactor, state: AppConnectionState): PixelMap {
        composeTestRule.runOnUiThread { viewModel.updateConnectionState(state) }
        composeTestRule.setContent {
            CompositionLocalProvider(LocalFormFactor provides formFactor) {
                SendSpinTheme(darkTheme = true, dynamicColor = false) {
                    AppShell(
                        viewModel = viewModel,
                        serverListContent = {},
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
                        serverColors = palette,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        return composeTestRule.onRoot().captureToImage().toPixelMap()
    }

    private val connected = AppConnectionState.Connected("Kitchen Server", "192.168.1.4:8927")

    /**
     * Within a step of 8-bit rounding: the top bar is the background at 60%
     * alpha over itself.
     */
    private fun isServerBackground(pixel: Color) =
        abs(pixel.red - background.red) <= 1.5f / 255 &&
            abs(pixel.green - background.green) <= 1.5f / 255 &&
            abs(pixel.blue - background.blue) <= 1.5f / 255

    private fun assertServerBackground(where: String, pixel: Color) =
        assertTrue("$where is $pixel", isServerBackground(pixel))

    private fun assertMaterial(where: String, pixel: Color) =
        assertFalse("$where is $pixel", isServerBackground(pixel))

    @Test
    fun `connected, the top bar and Now Playing take the server background`() {
        val screen = show(FormFactor.PHONE, connected)

        assertServerBackground("top bar", screen[screen.width / 2, 2])
        assertServerBackground("Now Playing", screen[2, screen.height - 2])
    }

    @Test
    fun `the server list keeps the Material colors`() {
        val screen = show(FormFactor.PHONE, AppConnectionState.ServerList)

        assertMaterial("top bar", screen[screen.width / 2, 2])
        assertMaterial("server list", screen[2, screen.height - 2])
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land")
    fun `on a TV, connected, the menu rail and Now Playing take the server background`() {
        val screen = show(FormFactor.TV, connected)

        assertServerBackground("menu rail", screen[2, screen.height / 2])
        assertServerBackground("Now Playing", screen[screen.width - 2, screen.height - 2])
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land")
    fun `on a TV the server list keeps the Material colors`() {
        val screen = show(FormFactor.TV, AppConnectionState.ServerList)

        assertMaterial("menu rail", screen[2, screen.height / 2])
        assertMaterial("server list", screen[screen.width - 2, screen.height - 2])
    }
}
