package com.sendspindroid

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import com.sendspindroid.playback.PlaybackService
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The welcome comes before the network. Building the MediaController binds
 * PlaybackService, which advertises the player as soon as it is created, so
 * on the first run the controller waits for the welcome's Continue: the
 * user names the player before any server can see it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityWelcomeTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun freshSettings() {
        // UserSettings is a process-wide object: give it this test's
        // preferences, or a name saved by an earlier test would carry over.
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        UserSettings.initializeForTesting(prefs, prefs)
        // Robolectric runs no media session, and by default answers the
        // controller's bind with a null component, which media3 throws on.
        // Refused, the bind fails as it would for a missing service.
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
    }

    private fun welcomeDone() =
        app.getSharedPreferences("sendspindroid_prefs", Context.MODE_PRIVATE)
            .getBoolean("onboarding_shown", false)

    private fun controllerFuture(activity: MainActivity): Any? =
        MainActivity::class.java.getDeclaredField("mediaControllerFuture").run {
            isAccessible = true
            get(activity)
        }

    private fun welcomeDialog(): AlertDialog? =
        (ShadowDialog.getLatestDialog() as? AlertDialog)?.takeIf { it.isShowing }

    private fun confirm(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        // The dialog delivers the click through its handler.
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a first run shows the welcome and does not start the service`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()

        assertNotNull(welcomeDialog())
        assertNull(controllerFuture(activity))
    }

    @Test
    fun `continue starts the service and the welcome is not shown again`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()

        confirm(welcomeDialog()!!)

        assertNotNull(controllerFuture(activity))
        assertTrue(welcomeDone())
    }

    @Test
    fun `after the welcome the service starts at once`() {
        app.getSharedPreferences("sendspindroid_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_shown", true).commit()

        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()

        assertNull(welcomeDialog())
        assertNotNull(controllerFuture(activity))
    }
}
