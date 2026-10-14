package com.sendspindroid.sendspin.decoder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FlacFramesTest {

    private val sampleRate = 48_000

    /**
     * One FLAC frame: header (48 kHz, stereo, 16-bit, the given block size
     * code and frame number), [body], CRC-16 footer.
     */
    private fun frame(blockSizeCode: Int, frameNumber: Int, body: ByteArray, explicitBlockSize: Int? = null): ByteArray {
        val header = mutableListOf(0xFF, 0xF8, (blockSizeCode shl 4) or 0x0A, (1 shl 4) or (4 shl 1), frameNumber)
        if (blockSizeCode == 6) header += explicitBlockSize!! - 1
        val headerBytes = header.map { it.toByte() }.toByteArray()
        val withCrc8 = headerBytes + FlacFrames.crc8(headerBytes, 0, headerBytes.size).toByte()
        val frame = withCrc8 + body
        val crc = FlacFrames.crc16(frame, 0, frame.size)
        return frame + byteArrayOf((crc shr 8).toByte(), crc.toByte())
    }

    private fun body(seed: Int, size: Int = 64) = ByteArray(size) { ((it * 31 + seed * 7) % 251).toByte() }

    @Test
    fun `the checksums are FLAC's`() {
        val check = "123456789".toByteArray()
        assertEquals("CRC-8, polynomial 0x07", 0xF4, FlacFrames.crc8(check, 0, check.size))
        assertEquals("CRC-16, polynomial 0x8005", 0xFEE8, FlacFrames.crc16(check, 0, check.size))
    }

    @Test
    fun `block sizes are read from the frame header`() {
        assertEquals(4096, FlacFrames.headerBlockSize(frame(12, 0, body(1)), 0))
        assertEquals(1152, FlacFrames.headerBlockSize(frame(3, 0, body(1)), 0))
        assertEquals(192, FlacFrames.headerBlockSize(frame(1, 0, body(1)), 0))
        assertEquals(100, FlacFrames.headerBlockSize(frame(6, 0, body(1), explicitBlockSize = 100), 0))
    }

    @Test
    fun `a header with the wrong CRC-8 is no header`() {
        val damaged = frame(12, 0, body(1))
        damaged[5] = (damaged[5] + 1).toByte()  // the CRC-8, after a one-byte frame number
        assertEquals(-1, FlacFrames.headerBlockSize(damaged, 0))
    }

    @Test
    fun `a chunk of one frame is decoded as it is`() {
        val chunk = frame(12, 0, body(1))

        val frames = FlacFrames.split(chunk, 1_000_000L, sampleRate)

        assertEquals(1, frames.size)
        assertSame(chunk, frames[0].data)
        assertEquals(1_000_000L, frames[0].timestampUs)
    }

    @Test
    fun `a chunk of several frames is split, each at its own time`() {
        val a = frame(12, 0, body(1))
        val b = frame(12, 1, body(2))
        val c = frame(3, 2, body(3, size = 40))

        val frames = FlacFrames.split(a + b + c, 1_000_000L, sampleRate)

        assertEquals(3, frames.size)
        assertArrayEquals(a, frames[0].data)
        assertArrayEquals(b, frames[1].data)
        assertArrayEquals(c, frames[2].data)
        assertEquals(1_000_000L, frames[0].timestampUs)
        assertEquals(1_000_000L + 4096 * 1_000_000L / sampleRate, frames[1].timestampUs)
        assertEquals(1_000_000L + 8192 * 1_000_000L / sampleRate, frames[2].timestampUs)
    }

    @Test
    fun `data that looks like a frame header does not split a frame`() {
        // A valid header in the middle of a frame's audio: the bytes before
        // it do not end with a CRC-16 of a frame
        val lookalike = frame(12, 5, body(9)).copyOf(6)
        val a = frame(12, 0, body(1, size = 20) + lookalike + body(4, size = 20))
        val b = frame(12, 1, body(2))

        val frames = FlacFrames.split(a + b, 0L, sampleRate)

        assertEquals(2, frames.size)
        assertArrayEquals(a, frames[0].data)
        assertArrayEquals(b, frames[1].data)
    }

    @Test
    fun `a chunk that does not start with a frame header is decoded as it is`() {
        val chunk = body(1) + frame(12, 0, body(2))

        val frames = FlacFrames.split(chunk, 7L, sampleRate)

        assertEquals(1, frames.size)
        assertSame(chunk, frames[0].data)
    }
}
