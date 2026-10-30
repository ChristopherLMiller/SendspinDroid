package com.sendspindroid.e2e

import com.sendspindroid.AppSendSpinHost
import com.sendspindroid.coordinator.TransportState
import com.sendspindroid.UserSettings
import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import com.sendspindroid.sendspin.protocol.AudioQuality
import com.sendspindroid.sendspin.protocol.QualityStep
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quality the user chose for the network a connection runs over decides
 * the first entry of its `client/hello`, and every step the device plays is
 * listed after it. A quality changed while connected is asked for at once:
 * with a new connection when its codec changes, else with `format`.
 */
class AudioQualityHelloTest : E2ETestBase() {

    override fun configureUserSettings() {
        every { UserSettings.audioQuality(false) } returns AudioQuality.NORMAL
        every { UserSettings.audioQuality(true) } returns AudioQuality.LOW
    }

    private fun connect(mobileData: Boolean) {
        mockkObject(AppSendSpinHost)
        every { AppSendSpinHost.onMobileData() } returns mobileData
        every { AudioDecoderFactory.isHighResFlacDecodeSupported() } returns true
        connectAndHandshake()
    }

    /** The hello's supported formats at 48 kHz, as codec and bit depth, in order. */
    private fun helloSteps(): List<Pair<String, Int>> {
        assertTrue(fakeTransport.hasSentMessageContaining("\"type\":\"client/hello\""))
        val hello = fakeTransport.sentTextMessages.single { it.contains("\"type\":\"client/hello\"") }
        return Json.parseToJsonElement(hello).jsonObject["payload"]!!.jsonObject["player@v1_support"]!!
            .jsonObject["supported_formats"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["sample_rate"]!!.jsonPrimitive.int == 48000 }
            .map { it["codec"]!!.jsonPrimitive.content to it["bit_depth"]!!.jsonPrimitive.int }
    }

    @Test
    fun `on Wi-Fi the hello leads with the Wi-Fi quality's step`() {
        connect(mobileData = false)

        assertEquals(
            listOf("flac" to 24, "flac" to 16, "opus" to 16, "pcm" to 16, "pcm" to 24, "pcm" to 32),
            helloSteps()
        )
        assertEquals(SendSpin.QualityChoice(false, AudioQuality.NORMAL, QualityStep.FLAC_24), client.qualityChoice)
    }

    @Test
    fun `on mobile data the hello leads with the mobile data quality's step`() {
        connect(mobileData = true)

        assertEquals("opus" to 16, helloSteps().first())
        assertEquals(QualityStep.OPUS, client.qualityChoice?.step)
    }

    @Test
    fun `a quality in another codec connects again, for a hello that leads with it`() {
        // Music Assistant picks a session's format from the hello's first
        // codec, and that outranks `format`, so only a new hello moves it.
        connect(mobileData = false)
        fakeTransport.clearRecordedMessages()

        every { UserSettings.audioQuality(false) } returns AudioQuality.HIGH
        client.onAudioQualityChanged()

        assertTrue(fakeTransport.hasSentMessageContaining("\"reason\":\"restart\""))
        assertTrue(fakeTransport.sentTextMessages.none { it.contains("\"format\"") })
        // The owner of the client reconnects; the next hello reads the new quality
        verify(exactly = 1) { mockCallback.onDisconnected(true) }
        assertTrue(client.connectionState.value is TransportState.Idle)
    }

    @Test
    fun `a quality in the same codec is asked for at once, without connecting again`() {
        // No Opus decoder: Low steps up to FLAC 16, and Normal is FLAC 24
        every { AudioDecoderFactory.isCodecSupported("opus") } returns false
        every { UserSettings.audioQuality(false) } returns AudioQuality.LOW
        connect(mobileData = false)
        assertEquals(QualityStep.FLAC_16, client.qualityChoice?.step)
        fakeTransport.clearRecordedMessages()

        every { UserSettings.audioQuality(false) } returns AudioQuality.NORMAL
        client.onAudioQualityChanged()

        assertTrue(
            fakeTransport.hasSentMessageContaining(
                "\"format\":{\"codec\":\"flac\",\"sample_rate\":48000,\"channels\":2,\"bit_depth\":24}"
            )
        )
        assertEquals(QualityStep.FLAC_24, client.qualityChoice?.step)
        assertTrue(fakeTransport.sentTextMessages.none { it.contains("client/goodbye") })
    }

    @Test
    fun `a change to the other network's quality leaves this connection alone`() {
        connect(mobileData = false)
        fakeTransport.clearRecordedMessages()

        every { UserSettings.audioQuality(true) } returns AudioQuality.HIGH
        client.onAudioQualityChanged()
        // A client/state sent after it, to know anything it sent has landed
        client.setVolume(0.5)

        assertTrue(fakeTransport.hasSentMessageContaining("\"volume\":50"))
        assertEquals(QualityStep.FLAC_24, client.qualityChoice?.step)
        assertTrue(fakeTransport.sentTextMessages.none { it.contains("\"format\"") })
    }
}
