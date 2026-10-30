package com.sendspindroid.sendspin.decoder

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * The platform FLAC decoders put out 16-bit PCM unless asked for float, so a
 * stream deeper than 16 bits asks for float and a 16-bit one does not.
 */
class FlacDecoderTest {

    private val integers = mutableMapOf<String, Int>()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        val format = mockk<MediaFormat>(relaxed = true)
        every { format.setInteger(any(), any()) } answers {
            integers[firstArg()] = secondArg()
        }
        mockkStatic(MediaFormat::class)
        every { MediaFormat.createAudioFormat(any(), any(), any()) } returns format
        mockkStatic(MediaCodec::class)
        every { MediaCodec.createDecoderByType(any()) } returns mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `a 24-bit stream asks the decoder for float`() {
        FlacDecoder().configure(sampleRate = 48000, channels = 2, bitDepth = 24, codecHeader = ByteArray(34))

        assertEquals(AudioFormat.ENCODING_PCM_FLOAT, integers[MediaFormat.KEY_PCM_ENCODING])
    }

    @Test
    fun `a 16-bit stream leaves the decoder at its 16-bit default`() {
        FlacDecoder().configure(sampleRate = 48000, channels = 2, bitDepth = 16, codecHeader = ByteArray(34))

        assertFalse(integers.containsKey(MediaFormat.KEY_PCM_ENCODING))
    }

    @Test
    fun `the decoder's input buffers take the largest frame the stream can carry`() {
        // The codec header Music Assistant sends a 96 kHz 24-bit stream with:
        // "fLaC", then a STREAMINFO of 8192-sample blocks and frames up to
        // 50202 bytes, past the platform decoder's own 32 KB
        val streamInfo = listOf(
            0x20, 0x00, 0x20, 0x00,  // block sizes, least and most
            0x00, 0x00, 0x00,        // least frame size, not known
            0x00, 0xC4, 0x1A,        // most frame size
            0x17, 0x70, 0x03, 0x70,  // 96000 Hz, 2 channels, 24 bits
        ) + List(20) { 0 }
        val header = (listOf(0x66, 0x4C, 0x61, 0x43, 0x80, 0x00, 0x00, 0x22) + streamInfo)
            .map { it.toByte() }.toByteArray()

        FlacDecoder().configure(sampleRate = 96_000, channels = 2, bitDepth = 24, codecHeader = header)

        assertEquals(50_202, integers[MediaFormat.KEY_MAX_INPUT_SIZE])
    }

    @Test
    fun `without a STREAMINFO to read the decoder keeps its own input buffer size`() {
        FlacDecoder().configure(sampleRate = 48000, channels = 2, bitDepth = 16, codecHeader = ByteArray(34))

        assertFalse(integers.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
    }
}
