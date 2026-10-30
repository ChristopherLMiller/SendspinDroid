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
}
