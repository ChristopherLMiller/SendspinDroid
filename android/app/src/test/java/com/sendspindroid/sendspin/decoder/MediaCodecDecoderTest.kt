package com.sendspindroid.sendspin.decoder

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import io.mockk.Ordering
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for [MediaCodecDecoder].
 *
 * Uses a concrete test subclass with a mock MediaCodec to verify:
 * - C-05: Input retry under queue pressure (no silent frame drops)
 * - C-06: flush() does not call start() in synchronous mode
 * - C-07: Output drain handles INFO_OUTPUT_FORMAT_CHANGED mid-stream
 */
class MediaCodecDecoderTest {

    private lateinit var mockCodec: MediaCodec
    private lateinit var decoder: TestMediaCodecDecoder

    /**
     * Concrete subclass that exposes the protected mediaCodec field for testing
     * and provides a no-op configureFormat.
     */
    private class TestMediaCodecDecoder : MediaCodecDecoder("audio/opus") {
        fun injectCodec(codec: MediaCodec) {
            mediaCodec = codec
        }

        override fun configureFormat(
            format: MediaFormat,
            sampleRate: Int,
            channels: Int,
            bitDepth: Int,
            codecHeader: ByteArray?
        ) {
            // No-op for tests
        }
    }

    @Before
    fun setUp() {
        // Mock android.util.Log which is unavailable on JVM
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockCodec = mockk(relaxed = true)
        decoder = TestMediaCodecDecoder()
        decoder.injectCodec(mockCodec)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ---- Helper to create a ByteBuffer wrapping test data ----

    private fun testBuffer(size: Int): ByteBuffer {
        val data = ByteArray(size) { it.toByte() }
        return ByteBuffer.wrap(data)
    }

    private fun setupSingleOutputBuffer(pcmSize: Int) {
        val outBuffer = testBuffer(pcmSize)
        every { mockCodec.dequeueOutputBuffer(any(), any()) } returnsMany listOf(
            0,
            MediaCodec.INFO_TRY_AGAIN_LATER
        )
        every { mockCodec.getOutputBuffer(0) } returns outBuffer
    }

    // =========================================================================
    // C-05: Input retry under queue pressure
    // =========================================================================

    @Test
    fun decode_inputBufferAvailable_submitsFrameImmediately() {
        // Input available on first attempt
        every { mockCodec.dequeueInputBuffer(any()) } returns 0
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)

        // No output
        every { mockCodec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER

        val input = ByteArray(100) { 0x42 }
        decoder.decode(input, 0L)

        verify(exactly = 1) { mockCodec.queueInputBuffer(0, 0, 100, 0, 0) }
    }

    @Test
    fun decode_inputBufferUnavailableThenAvailable_retriesAndSubmits() {
        // First attempt: no buffer. Second attempt: buffer available.
        every { mockCodec.dequeueInputBuffer(any()) } returnsMany listOf(-1, 0)
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)

        // Drain during retry returns nothing
        every { mockCodec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER

        val input = ByteArray(50)
        decoder.decode(input, 0L)

        // Input was submitted on the second attempt
        verify(exactly = 1) { mockCodec.queueInputBuffer(0, 0, 50, 0, 0) }
        // dequeueInputBuffer called twice (initial fail + retry)
        verify(exactly = 2) { mockCodec.dequeueInputBuffer(any()) }
    }

    @Test
    fun decode_inputBufferNeverAvailable_logsErrorAfterMaxRetries() {
        // All attempts fail
        every { mockCodec.dequeueInputBuffer(any()) } returns -1
        every { mockCodec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER

        val input = ByteArray(200)
        decoder.decode(input, 0L)

        // Should have tried MAX_INPUT_RETRIES + 1 = 4 times
        verify(exactly = 4) { mockCodec.dequeueInputBuffer(any()) }
        // Frame was not submitted
        verify(exactly = 0) { mockCodec.queueInputBuffer(any(), any(), any(), any(), any()) }
        // Error was logged (not just a warning)
        verify { Log.e(any(), match { it.contains("Failed to submit input") }) }
    }

    @Test
    fun decode_inputRetry_drainsOutputBetweenAttempts() {
        // First two attempts fail, third succeeds
        every { mockCodec.dequeueInputBuffer(any()) } returnsMany listOf(-1, -1, 0)
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)

        // Output drain yields a buffer on first drain call
        val outBuffer = testBuffer(960)
        var drainCallCount = 0
        every { mockCodec.dequeueOutputBuffer(any(), any()) } answers {
            drainCallCount++
            if (drainCallCount == 1) {
                // Populate BufferInfo so drainOutput collects the data
                val info = firstArg<MediaCodec.BufferInfo>()
                info.offset = 0
                info.size = 960
                0 // buffer index
            } else {
                MediaCodec.INFO_TRY_AGAIN_LATER
            }
        }
        every { mockCodec.getOutputBuffer(0) } returns outBuffer

        val input = ByteArray(100)
        val result = decoder.decode(input, 0L)

        // Input was eventually submitted
        verify(exactly = 1) { mockCodec.queueInputBuffer(0, 0, 100, 0, 0) }
        // Output was collected during the drain-between-retries
        assertTrue("Should have collected PCM output during retry drain", result.isNotEmpty())
    }

    // =========================================================================
    // C-06: flush() does not call start() in synchronous mode
    // =========================================================================

    @Test
    fun flush_doesNotCallStart() {
        decoder.flush()

        verify(exactly = 1) { mockCodec.flush() }
        verify(exactly = 0) { mockCodec.start() }
    }

    @Test
    fun flush_thenDecode_worksWithoutStart() {
        // After flush, decode should work by going directly to dequeueInputBuffer
        every { mockCodec.dequeueInputBuffer(any()) } returns 0
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)
        every { mockCodec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER

        decoder.flush()
        decoder.decode(ByteArray(50), 0L)

        verify(ordering = Ordering.ORDERED) {
            mockCodec.flush()
            mockCodec.dequeueInputBuffer(any())
        }
        // start() is never called
        verify(exactly = 0) { mockCodec.start() }
    }

    @Test
    fun flush_exceptionIsCaughtAndLogged() {
        every { mockCodec.flush() } throws IllegalStateException("test error")

        // Should not throw
        decoder.flush()

        verify { Log.e(any(), match { it.contains("Error flushing") }, any()) }
    }

    // =========================================================================
    // C-07: Output drain handles FORMAT_CHANGED mid-stream
    // =========================================================================

    @Test
    fun decode_formatChangeMidDrain_continuesDrainingAfterFormatChange() {
        every { mockCodec.dequeueInputBuffer(any()) } returns 0
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)

        // Output sequence: buffer0 -> FORMAT_CHANGED -> buffer1 -> TRY_AGAIN
        val buf0 = testBuffer(480)
        val buf1 = testBuffer(480)
        val outputSequence = listOf(
            0,
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
            1,
            MediaCodec.INFO_TRY_AGAIN_LATER,
            // Final drain after input submit also gets TRY_AGAIN
            MediaCodec.INFO_TRY_AGAIN_LATER
        ).iterator()
        every { mockCodec.dequeueOutputBuffer(any(), any()) } answers {
            val idx = outputSequence.next()
            // Populate BufferInfo when returning a valid buffer index
            if (idx >= 0) {
                val info = firstArg<MediaCodec.BufferInfo>()
                info.offset = 0
                info.size = 480
            }
            idx
        }
        every { mockCodec.getOutputBuffer(0) } returns buf0
        every { mockCodec.getOutputBuffer(1) } returns buf1

        val mockFormat = mockk<MediaFormat>(relaxed = true)
        every { mockCodec.outputFormat } returns mockFormat

        val input = ByteArray(100)
        val result = decoder.decode(input, 0L)

        // Both output buffers should have been collected (480 + 480 = 960 bytes)
        assertEquals(960, result.sumOf { it.pcm.size })

        // Both buffers were released
        verify { mockCodec.releaseOutputBuffer(0, false) }
        verify { mockCodec.releaseOutputBuffer(1, false) }
    }

    @Test
    fun decode_formatChangeOnly_updatesOutputFormat() {
        every { mockCodec.dequeueInputBuffer(any()) } returns 0
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)

        every { mockCodec.dequeueOutputBuffer(any(), any()) } returnsMany listOf(
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
            MediaCodec.INFO_TRY_AGAIN_LATER,
            MediaCodec.INFO_TRY_AGAIN_LATER
        )

        val mockFormat = mockk<MediaFormat>(relaxed = true)
        every { mockCodec.outputFormat } returns mockFormat

        decoder.decode(ByteArray(100), 0L)

        verify { Log.d(any(), match { it.contains("Output format changed") }) }
    }

    @Test
    @Suppress("DEPRECATION")
    fun decode_outputBuffersChanged_continuesDraining() {
        every { mockCodec.dequeueInputBuffer(any()) } returns 0
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)

        // Sequence: BUFFERS_CHANGED -> buffer0 -> TRY_AGAIN
        val buf0 = testBuffer(480)
        val outputSequence = listOf(
            MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED,
            0,
            MediaCodec.INFO_TRY_AGAIN_LATER,
            MediaCodec.INFO_TRY_AGAIN_LATER
        ).iterator()
        every { mockCodec.dequeueOutputBuffer(any(), any()) } answers {
            val idx = outputSequence.next()
            if (idx >= 0) {
                val info = firstArg<MediaCodec.BufferInfo>()
                info.offset = 0
                info.size = 480
            }
            idx
        }
        every { mockCodec.getOutputBuffer(0) } returns buf0

        val result = decoder.decode(ByteArray(100), 0L)

        // The buffer after BUFFERS_CHANGED was collected
        assertEquals(480, result.sumOf { it.pcm.size })
        verify { mockCodec.releaseOutputBuffer(0, false) }
    }

    // =========================================================================
    // General decode behavior
    // =========================================================================

    @Test
    fun decode_notConfigured_throwsIllegalState() {
        val unconfigured = TestMediaCodecDecoder()
        // mediaCodec is null (not injected)

        try {
            unconfigured.decode(ByteArray(100), 0L)
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("not configured"))
        }
    }

    @Test
    fun decode_emptyOutput_returnsNothing() {
        every { mockCodec.dequeueInputBuffer(any()) } returns 0
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)
        every { mockCodec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER

        val result = decoder.decode(ByteArray(100), 0L)
        assertEquals(0, result.size)
    }

    // =========================================================================
    // Output is stamped by the decoder, not by the chunk being submitted
    // =========================================================================

    @Test
    fun decode_passesTheChunkTimestampToTheCodec() {
        every { mockCodec.dequeueInputBuffer(any()) } returns 0
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)
        every { mockCodec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER

        decoder.decode(ByteArray(100), 1_234_567L)

        verify(exactly = 1) { mockCodec.queueInputBuffer(0, 0, 100, 1_234_567L, 0) }
    }

    @Test
    fun decode_lateOutputKeepsTheTimestampOfTheChunkItCameFrom() {
        // What a real decoder does when its first output is not ready in time:
        // nothing from the first call, then both chunks from the second.
        every { mockCodec.dequeueInputBuffer(any()) } returns 0
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)
        every { mockCodec.getOutputBuffer(any()) } answers { testBuffer(480) }

        every { mockCodec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER
        assertTrue(decoder.decode(ByteArray(100), 1_000_000L).isEmpty())

        val ready = listOf(0 to 1_000_000L, 1 to 1_096_000L).iterator()
        every { mockCodec.dequeueOutputBuffer(any(), any()) } answers {
            if (ready.hasNext()) {
                val (index, pts) = ready.next()
                val info = firstArg<MediaCodec.BufferInfo>()
                info.offset = 0
                info.size = 480
                info.presentationTimeUs = pts
                index
            } else {
                MediaCodec.INFO_TRY_AGAIN_LATER
            }
        }
        val out = decoder.decode(ByteArray(100), 1_096_000L)

        assertEquals(listOf(1_000_000L, 1_096_000L), out.map { it.timestampUs })
    }

    @Test
    fun decode_stopsWaitingOnceThereIsOutput() {
        every { mockCodec.dequeueInputBuffer(any()) } returns 0
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)
        every { mockCodec.getOutputBuffer(0) } answers { testBuffer(480) }
        val timeouts = mutableListOf<Long>()
        var calls = 0
        every { mockCodec.dequeueOutputBuffer(any(), any()) } answers {
            timeouts += secondArg<Long>()
            if (calls++ == 0) {
                val info = firstArg<MediaCodec.BufferInfo>()
                info.offset = 0
                info.size = 480
                0
            } else {
                MediaCodec.INFO_TRY_AGAIN_LATER
            }
        }

        decoder.decode(ByteArray(100), 0L)

        // Waits for the first buffer, then only polls.
        assertTrue("first wait was ${timeouts[0]}", timeouts[0] > 0L)
        assertEquals(0L, timeouts[1])
    }

    // =========================================================================
    // Output at the stream's bit depth
    // =========================================================================

    @Test
    fun decode_convertsWhatTheCodecPutsOutToTheStreamsBitDepth() {
        // A FLAC decoder asked for high resolution puts out float
        mockkStatic(MediaFormat::class)
        every { MediaFormat.createAudioFormat(any(), any(), any()) } returns mockk(relaxed = true)
        mockkStatic(MediaCodec::class)
        every { MediaCodec.createDecoderByType(any()) } returns mockCodec
        val floatOutput = mockk<MediaFormat> {
            every { containsKey(MediaFormat.KEY_PCM_ENCODING) } returns true
            every { getInteger(MediaFormat.KEY_PCM_ENCODING) } returns AudioFormat.ENCODING_PCM_FLOAT
        }
        every { mockCodec.outputFormat } returns floatOutput
        decoder.configure(sampleRate = 48000, channels = 2, bitDepth = 24, codecHeader = null)

        every { mockCodec.dequeueInputBuffer(any()) } returns 0
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)
        val decoded = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.5f).putFloat(-0.5f).array()
        val outputs = listOf(0, MediaCodec.INFO_TRY_AGAIN_LATER).iterator()
        every { mockCodec.dequeueOutputBuffer(any(), any()) } answers {
            val idx = if (outputs.hasNext()) outputs.next() else MediaCodec.INFO_TRY_AGAIN_LATER
            if (idx >= 0) {
                val info = firstArg<MediaCodec.BufferInfo>()
                info.offset = 0
                info.size = decoded.size
            }
            idx
        }
        every { mockCodec.getOutputBuffer(0) } returns ByteBuffer.wrap(decoded)

        val pcm = decoder.decode(ByteArray(10), 0L).single().pcm

        // +/-0.5 at 24 bits, packed little-endian: 0x400000 and -0x400000
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x40, 0x00, 0x00, 0xC0.toByte()), pcm)
    }

    // =========================================================================
    // Every input buffer taken goes back to the codec
    // =========================================================================

    // What was set on each format configure made, in turn
    private val formatIntegers = mutableListOf<MutableMap<String, Int>>()

    /** Configure [decoder], having it make [codecs] in turn. */
    private fun configureWith(vararg codecs: MediaCodec) {
        mockkStatic(MediaFormat::class)
        every { MediaFormat.createAudioFormat(any(), any(), any()) } answers {
            val integers = mutableMapOf<String, Int>()
            formatIntegers += integers
            mockk<MediaFormat>(relaxed = true) {
                every { setInteger(any(), any()) } answers { integers[firstArg()] = secondArg() }
            }
        }
        mockkStatic(MediaCodec::class)
        every { MediaCodec.createDecoderByType(any()) } returnsMany codecs.toList()
        decoder.configure(sampleRate = 96_000, channels = 2, bitDepth = 24, codecHeader = null)
    }

    /** A codec whose input buffer is [capacity] bytes, and that hands an end of stream straight back. */
    private fun codecWithInputBuffer(index: Int, capacity: Int): MediaCodec = mockk(relaxed = true) {
        every { dequeueInputBuffer(any()) } returns index
        every { getInputBuffer(index) } returns ByteBuffer.allocate(capacity)
        every { getOutputBuffer(any()) } returns ByteBuffer.allocate(0)
        every { dequeueOutputBuffer(any(), any()) } answers {
            firstArg<MediaCodec.BufferInfo>().flags = MediaCodec.BUFFER_FLAG_END_OF_STREAM
            0
        }
    }

    @Test
    fun decode_frameLargerThanTheInputBuffers_setsTheCodecUpAgainWithRoomForIt() {
        // A 96 kHz 24-bit FLAC frame, past the platform decoder's 32 KB
        val small = codecWithInputBuffer(index = 0, capacity = 32_768)
        val large = mockk<MediaCodec>(relaxed = true) {
            every { dequeueInputBuffer(any()) } returns 1
            every { getInputBuffer(1) } returns ByteBuffer.allocate(53_289)
            every { dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER
        }
        configureWith(small, large)

        decoder.decode(ByteArray(35_526), 5_000L)

        // The buffer too small for it went back, as the end of the stream: one
        // kept is lost to the codec, and losing them all stopped it for good
        verify { small.queueInputBuffer(0, 0, 0, any(), MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
        verify { small.release() }
        assertEquals(35_526 + 35_526 / 2, formatIntegers[1][MediaFormat.KEY_MAX_INPUT_SIZE])
        verify(exactly = 1) { large.queueInputBuffer(1, 0, 35_526, 5_000L, 0) }
    }

    @Test
    fun decode_frameTheCodecWillNotMakeRoomFor_givesTheBufferBackAndThrows() {
        val small = codecWithInputBuffer(index = 0, capacity = 32_768)
        val stillSmall = mockk<MediaCodec>(relaxed = true) {
            every { dequeueInputBuffer(any()) } returns 1
            every { getInputBuffer(1) } returns ByteBuffer.allocate(32_768)
            every { dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER
        }
        configureWith(small, stillSmall)

        assertThrows(IllegalStateException::class.java) { decoder.decode(ByteArray(35_526), 0L) }

        // Given back empty, and set up again just the once
        verify { stillSmall.queueInputBuffer(1, 0, 0, any(), 0) }
        verify(exactly = 2) { MediaCodec.createDecoderByType(any()) }
    }

    @Test
    fun decode_codecThatTakesNoInputFrameAfterFrame_throwsSoItIsSetUpAgain() {
        every { mockCodec.dequeueInputBuffer(any()) } returns -1
        every { mockCodec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER

        decoder.decode(ByteArray(10), 0L)
        decoder.decode(ByteArray(10), 0L)

        assertThrows(IllegalStateException::class.java) { decoder.decode(ByteArray(10), 0L) }
    }

    @Test
    fun decode_aFrameTakenStartsTheCountOfFramesRefusedOver() {
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)
        every { mockCodec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER
        // Four attempts for each frame refused: two refused, one taken, two refused
        every { mockCodec.dequeueInputBuffer(any()) } returnsMany List(8) { -1 } + 0 + List(8) { -1 }

        repeat(5) { decoder.decode(ByteArray(10), 0L) }

        verify(exactly = 1) { mockCodec.queueInputBuffer(0, 0, 10, 0L, 0) }
    }
}
