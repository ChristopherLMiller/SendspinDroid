package com.sendspindroid.sendspin.decoder

import android.media.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A decoder's output, from the encoding it puts out to the bit depth the
 * stream plays at. Float from a FLAC decoder asked for high resolution has to
 * come back as exactly the 24-bit samples the server encoded.
 */
class PcmConverterTest {

    private fun floats(vararg values: Float): ByteArray =
        ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach { putFloat(it) } }.array()

    private fun ints16(vararg values: Int): ByteArray =
        ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach { putShort(it.toShort()) } }.array()

    private fun ints32(vararg values: Int): ByteArray =
        ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach { putInt(it) } }.array()

    private fun packed24(vararg values: Int): ByteArray = ByteArray(values.size * 3).also { out ->
        values.forEachIndexed { i, v ->
            out[i * 3] = v.toByte()
            out[i * 3 + 1] = (v shr 8).toByte()
            out[i * 3 + 2] = (v shr 16).toByte()
        }
    }

    private fun read24(bytes: ByteArray): List<Int> = (0 until bytes.size / 3).map { i ->
        (bytes[i * 3].toInt() and 0xFF) or
            ((bytes[i * 3 + 1].toInt() and 0xFF) shl 8) or
            (bytes[i * 3 + 2].toInt() shl 16)
    }

    @Test
    fun `float from a 24-bit stream comes back as the samples it held`() {
        val samples = intArrayOf(0, 1, -1, 123_456, -654_321, 8_388_607, -8_388_608)
        val decoded = floats(*samples.map { it / 8_388_608f }.toFloatArray())

        val out = PcmConverter.convert(decoded, AudioFormat.ENCODING_PCM_FLOAT, 24)

        assertEquals(samples.toList(), read24(out))
    }

    @Test
    fun `float past full scale is clipped and NaN is silence`() {
        val out = PcmConverter.convert(floats(1.5f, -2f, Float.NaN), AudioFormat.ENCODING_PCM_FLOAT, 24)

        assertEquals(listOf(8_388_607, -8_388_608, 0), read24(out))
    }

    @Test
    fun `16-bit output for a 24-bit stream is shifted up`() {
        val out = PcmConverter.convert(ints16(0x1234, -2, Short.MIN_VALUE.toInt()), AudioFormat.ENCODING_PCM_16BIT, 24)

        assertArrayEquals(packed24(0x123400, -2 shl 8, Short.MIN_VALUE.toInt() shl 8), out)
    }

    @Test
    fun `32-bit output for a 24-bit stream keeps the top 24 bits`() {
        val out = PcmConverter.convert(ints32(0x12345678, -256), AudioFormat.ENCODING_PCM_32BIT, 24)

        assertEquals(listOf(0x123456, -1), read24(out))
    }

    @Test
    fun `24-bit output for a 16-bit stream drops the low byte`() {
        val out = PcmConverter.convert(packed24(0x123456, -8_388_608), AudioFormat.ENCODING_PCM_24BIT_PACKED, 16)

        assertArrayEquals(ints16(0x1234, Short.MIN_VALUE.toInt()), out)
    }

    @Test
    fun `float for a 16-bit stream rounds to 16 bits`() {
        val out = PcmConverter.convert(floats(0.5f, -1f), AudioFormat.ENCODING_PCM_FLOAT, 16)

        assertArrayEquals(ints16(16_384, Short.MIN_VALUE.toInt()), out)
    }

    @Test
    fun `output already at the stream's depth is passed through untouched`() {
        val pcm = ints16(1, 2, 3)

        assertSame(pcm, PcmConverter.convert(pcm, AudioFormat.ENCODING_PCM_16BIT, 16))
    }

    @Test
    fun `an encoding or depth it cannot convert is passed through`() {
        val pcm = byteArrayOf(1, 2, 3, 4)

        assertSame(pcm, PcmConverter.convert(pcm, AudioFormat.ENCODING_AC3, 24))
        assertSame(pcm, PcmConverter.convert(pcm, AudioFormat.ENCODING_PCM_16BIT, 8))
    }

    // ---- Gain, for an output Android applies no volume to ----

    @Test
    fun `full gain leaves the samples untouched, the same array`() {
        val pcm = ints16(1, -2, 3)

        assertSame(pcm, PcmConverter.applyGain(pcm, 16, 1f))
    }

    @Test
    fun `no gain is silence`() {
        val out = PcmConverter.applyGain(packed24(8_000_000, -8_000_000), 24, 0f)

        assertArrayEquals(ByteArray(6), out)
    }

    @Test
    fun `a gain scales signed samples at every depth`() {
        assertArrayEquals(ints16(10_000, -10_000), PcmConverter.applyGain(ints16(20_000, -20_000), 16, 0.5f))
        assertEquals(listOf(4_000_000, -4_000_000), read24(PcmConverter.applyGain(packed24(8_000_000, -8_000_000), 24, 0.5f)))
        assertArrayEquals(
            ints32(1_000_000_000, -1_000_000_000),
            PcmConverter.applyGain(ints32(2_000_000_000, -2_000_000_000), 32, 0.5f)
        )
    }
}
