package com.sendspindroid.e2e

import com.sendspindroid.AppSendSpinHost
import com.sendspindroid.UserSettings
import com.sendspindroid.sendspin.audio.BitPerfectFormats
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import com.sendspindroid.sendspin.protocol.AudioQuality
import io.mockk.every
import io.mockk.mockkObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On a USB DAC played bit-perfect, `client/hello` offers rates other than
 * 48 kHz only where the DAC takes them untouched, so a server that follows a
 * track's native rate (or the next one up) picks one it does. A change of
 * those formats connects again, for a hello that offers the new ones.
 */
class BitPerfectHelloTest : E2ETestBase() {

    // 32-bit slots at three rates: every depth fits
    private val dac = BitPerfectFormats(setOf(44_100 to 32, 48_000 to 32, 96_000 to 32))

    override fun configureUserSettings() {
        every { UserSettings.audioQuality(any()) } returns AudioQuality.NORMAL
    }

    private fun connect(formats: BitPerfectFormats?) {
        mockkObject(AppSendSpinHost)
        every { AppSendSpinHost.onMobileData() } returns false
        every { AppSendSpinHost.bitPerfectFormats() } returns formats
        every { AudioDecoderFactory.isHighResFlacDecodeSupported() } returns true
        every { AudioDecoderFactory.isCodecSupportedAtSampleRate(any(), any()) } returns true
        every { AudioDecoderFactory.isPcmOutputSupported(any(), any()) } returns true
        connectAndHandshake()
    }

    /** The hello's supported formats as codec, sample rate and bit depth, in order. */
    private fun helloFormats(): List<Triple<String, Int, Int>> {
        assertTrue(fakeTransport.hasSentMessageContaining("\"type\":\"client/hello\""))
        val hello = fakeTransport.sentTextMessages.single { it.contains("\"type\":\"client/hello\"") }
        return Json.parseToJsonElement(hello).jsonObject["payload"]!!.jsonObject["player@v1_support"]!!
            .jsonObject["supported_formats"]!!.jsonArray
            .map { it.jsonObject }
            .map {
                Triple(
                    it["codec"]!!.jsonPrimitive.content,
                    it["sample_rate"]!!.jsonPrimitive.int,
                    it["bit_depth"]!!.jsonPrimitive.int,
                )
            }
    }

    @Test
    fun `only the bit-perfect output's rates are offered beside 48 kHz`() {
        connect(dac)

        val formats = helloFormats()
        assertEquals("flac" to 24, formats.first().let { it.first to it.third })
        assertEquals(setOf(44_100, 48_000, 96_000), formats.map { it.second }.toSet())
        assertTrue(formats.contains(Triple("flac", 96_000, 24)))
        assertTrue(formats.contains(Triple("pcm", 44_100, 32)))
    }

    @Test
    fun `without a bit-perfect output the device probe decides`() {
        connect(null)

        // The probe accepts every candidate rate here
        assertTrue(helloFormats().any { it.second == 192_000 })
    }

    @Test
    fun `a bit-perfect output appearing connects again`() {
        connect(null)
        fakeTransport.clearRecordedMessages()

        every { AppSendSpinHost.bitPerfectFormats() } returns dac
        client.onOutputChanged()

        assertTrue(fakeTransport.hasSentMessageContaining("\"reason\":\"restart\""))
    }

    @Test
    fun `the same bit-perfect output leaves the connection alone`() {
        connect(dac)
        fakeTransport.clearRecordedMessages()

        client.onOutputChanged()
        // A client/state sent after it, to know anything it sent has landed
        client.setVolume(0.5)

        assertTrue(fakeTransport.hasSentMessageContaining("\"volume\":50"))
        assertTrue(fakeTransport.sentTextMessages.none { it.contains("client/goodbye") })
    }
}
