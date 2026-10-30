package com.sendspindroid

import android.content.SharedPreferences
import com.sendspindroid.sendspin.protocol.AudioQuality
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * The audio quality, one setting for Wi-Fi and one for mobile data, and how a
 * codec picked under the single setting they replaced carries over.
 */
class UserSettingsAudioQualityTest {

    private val prefsStore = mutableMapOf<String, String?>()

    @Before
    fun setUp() {
        UserSettings.resetForTesting()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true) {
            every { putString(any(), any()) } answers {
                prefsStore[firstArg()] = secondArg()
                this@mockk
            }
        }
        val prefs = mockk<SharedPreferences> {
            every { getString(any(), any()) } answers { prefsStore[firstArg()] ?: secondArg() }
            every { edit() } returns editor
        }
        UserSettings.initializeForTesting(prefs, mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        UserSettings.resetForTesting()
    }

    @Test
    fun `Wi-Fi is Normal and mobile data Low by default`() {
        assertEquals(AudioQuality.NORMAL, UserSettings.wifiAudioQuality)
        assertEquals(AudioQuality.LOW, UserSettings.mobileAudioQuality)
    }

    @Test
    fun `each network keeps its own quality`() {
        UserSettings.wifiAudioQuality = AudioQuality.HIGH
        UserSettings.mobileAudioQuality = AudioQuality.NORMAL

        assertEquals(AudioQuality.HIGH, UserSettings.audioQuality(mobileData = false))
        assertEquals(AudioQuality.NORMAL, UserSettings.audioQuality(mobileData = true))
    }

    @Test
    fun `a codec picked under the old setting carries over to both networks`() {
        prefsStore["preferred_codec"] = "pcm"
        assertEquals(AudioQuality.HIGH, UserSettings.wifiAudioQuality)
        assertEquals(AudioQuality.HIGH, UserSettings.mobileAudioQuality)

        prefsStore["preferred_codec"] = "flac"
        assertEquals(AudioQuality.NORMAL, UserSettings.mobileAudioQuality)

        prefsStore["preferred_codec"] = "opus"
        assertEquals(AudioQuality.LOW, UserSettings.wifiAudioQuality)
    }

    @Test
    fun `a quality chosen since outranks the old codec`() {
        prefsStore["preferred_codec"] = "opus"
        UserSettings.wifiAudioQuality = AudioQuality.HIGH

        assertEquals(AudioQuality.HIGH, UserSettings.wifiAudioQuality)
        // Mobile data was not chosen again, so the old codec still holds there
        assertEquals(AudioQuality.LOW, UserSettings.mobileAudioQuality)
    }

    @Test
    fun `an unknown stored value falls back to the default`() {
        prefsStore[UserSettings.KEY_WIFI_AUDIO_QUALITY] = "LOSSLESS"
        prefsStore["preferred_codec"] = "aac"

        assertEquals(AudioQuality.NORMAL, UserSettings.wifiAudioQuality)
    }
}
