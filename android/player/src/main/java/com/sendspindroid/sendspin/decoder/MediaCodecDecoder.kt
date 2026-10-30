package com.sendspindroid.sendspin.decoder

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import java.nio.ByteBuffer

/**
 * Base class for MediaCodec-based audio decoders.
 *
 * Provides synchronous decoding using Android's MediaCodec API.
 * Subclasses implement codec-specific format configuration.
 *
 * This decoder operates MediaCodec in synchronous mode, where:
 * - Input buffers are submitted via dequeueInputBuffer/queueInputBuffer
 * - Output buffers are drained via dequeueOutputBuffer/releaseOutputBuffer
 * - flush() returns to the Flushed sub-state without needing start()
 *
 * What the codec hands back is converted to the stream's bit depth, from
 * the encoding its output format names (see [PcmConverter]): a FLAC decoder
 * asked for high-resolution output puts out float.
 *
 * Every input buffer taken from the codec goes back to it queued, empty if
 * need be: one that does not is lost to it for good, and a codec that has
 * lost them all takes nothing more. A frame larger than the codec's input
 * buffers gets the codec made again with room for it.
 */
abstract class MediaCodecDecoder(
    protected val mimeType: String
) : AudioDecoder {

    companion object {
        private const val TAG = "MediaCodecDecoder"
        private const val TIMEOUT_US = 10_000L  // 10ms timeout for buffer operations

        /**
         * Maximum number of retry attempts when no input buffer is available.
         * Each retry waits TIMEOUT_US (10ms), so 3 retries = up to 40ms total
         * (initial attempt + 3 retries). This keeps latency bounded while giving
         * the codec time to free a buffer by processing output.
         */
        private const val MAX_INPUT_RETRIES = 3

        // Frames in a row a codec may take no input for before it counts as
        // stuck: decode then throws, and the engine sets up a new one
        private const val MAX_REFUSED_FRAMES = 3

        // How long drain() waits for the codec to hand back what it holds
        private const val DRAIN_TIMEOUT_NS = 100_000_000L

        /** The PCM encoding [format] names; 16-bit when it names none, as MediaFormat defines. */
        internal fun pcmEncodingOf(format: MediaFormat?): Int =
            if (format != null && format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
    }

    protected var mediaCodec: MediaCodec? = null
    protected var outputFormat: MediaFormat? = null
    private var _isConfigured = false

    // The bit depth the stream plays at, which the output is converted to
    private var streamBitDepth = SendSpinProtocol.AudioFormat.BIT_DEPTH

    // The stream as configure was given it, to make the codec again from
    private var stream: Stream? = null

    // The input buffer size asked for since a frame did not fit, or 0 for
    // what configureFormat asks
    private var inputRoom = 0

    // Frames in a row the codec has taken no input for
    private var refusedFrames = 0

    private class Stream(val sampleRate: Int, val channels: Int, val bitDepth: Int, val codecHeader: ByteArray?)

    override val isConfigured: Boolean
        get() = _isConfigured

    override fun configure(
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        codecHeader: ByteArray?
    ) {
        streamBitDepth = bitDepth
        stream = Stream(sampleRate, channels, bitDepth, codecHeader)
        inputRoom = 0
        refusedFrames = 0
        createCodec()
    }

    /** Create, configure and start the codec for [stream]. */
    private fun createCodec() {
        val s = stream ?: throw IllegalStateException("Decoder not configured")
        try {
            // Create base MediaFormat
            val format = MediaFormat.createAudioFormat(mimeType, s.sampleRate, s.channels)

            // Apply codec-specific configuration (template method)
            configureFormat(format, s.sampleRate, s.channels, s.bitDepth, s.codecHeader)
            if (inputRoom > 0) format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, inputRoom)

            // Create and configure decoder
            mediaCodec = MediaCodec.createDecoderByType(mimeType)
            mediaCodec?.configure(format, null, null, 0)
            // What it puts out until a format change says otherwise. Only
            // read for the encoding, so a codec that will not say yet is
            // taken at the 16-bit default rather than failing the stream.
            outputFormat = runCatching { mediaCodec?.outputFormat }.getOrNull()
            mediaCodec?.start()

            _isConfigured = true
            Log.d(TAG, "Decoder configured: $mimeType, ${s.sampleRate}Hz, ${s.channels}ch, ${s.bitDepth}bit " +
                "(puts out encoding ${pcmEncodingOf(outputFormat)})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure decoder", e)
            release()
            throw e
        }
    }

    /**
     * Template method for codec-specific format configuration.
     *
     * Subclasses override this to set codec-specific parameters like
     * CSD (Codec Specific Data) buffers.
     */
    protected abstract fun configureFormat(
        format: MediaFormat,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        codecHeader: ByteArray?
    )

    override fun decode(compressedData: ByteArray, timestampUs: Long): List<DecodedAudio> {
        var codec = mediaCodec
            ?: throw IllegalStateException("Decoder not configured")

        val output = ArrayList<DecodedAudio>(1)

        // Submit input with retry.
        // When all input buffers are occupied (codec backpressure), we drain
        // output to free slots, then retry. This prevents silent frame drops
        // that would corrupt stateful codecs like Opus.
        var submitted = false
        var attempt = 0
        while (attempt <= MAX_INPUT_RETRIES) {
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                if (inputBuffer != null && inputBuffer.capacity() >= compressedData.size) {
                    inputBuffer.clear()
                    inputBuffer.put(compressedData)
                    // The codec hands this timestamp back on the output it
                    // produces from this input, which is how late output is
                    // still placed correctly.
                    codec.queueInputBuffer(inputIndex, 0, compressedData.size, timestampUs, 0)
                    submitted = true
                    break
                }
                if (inputBuffer != null && compressedData.size > inputRoom && stream != null) {
                    codec = setUpWithRoomFor(compressedData.size, codec, inputIndex, inputBuffer.capacity(), output)
                    continue
                }
                // Given back empty: there is no buffer to fill, or the codec
                // keeps its buffers smaller than it was asked to
                codec.queueInputBuffer(inputIndex, 0, 0, timestampUs, 0)
                if (inputBuffer == null) break
                throw IllegalStateException(
                    "A ${compressedData.size}-byte frame does not fit the decoder's " +
                        "${inputBuffer.capacity()}-byte input buffers"
                )
            }

            // No input buffer available -- drain output to free a slot, then retry.
            if (attempt < MAX_INPUT_RETRIES) {
                drainOutput(codec, output)
            }
            attempt++
        }

        if (submitted) {
            refusedFrames = 0
        } else {
            Log.e(TAG, "Failed to submit input after ${MAX_INPUT_RETRIES + 1} attempts, " +
                    "frame dropped (${compressedData.size} bytes)")
            if (++refusedFrames >= MAX_REFUSED_FRAMES) {
                refusedFrames = 0
                throw IllegalStateException("The decoder took no input for $MAX_REFUSED_FRAMES frames in a row")
            }
        }

        drainOutput(codec, output)

        return output
    }

    /**
     * The codec made again with input buffers that take a frame of [size]
     * bytes, in place of [old], whose buffer at [inputIndex] holds only
     * [capacity]. That buffer goes back as the end of the stream, so the
     * audio [old] still holds is collected into [output] before its release.
     * Half as much room again as the frame takes, so one a little larger
     * fits too.
     */
    private fun setUpWithRoomFor(
        size: Int,
        old: MediaCodec,
        inputIndex: Int,
        capacity: Int,
        output: MutableList<DecodedAudio>
    ): MediaCodec {
        try {
            old.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            collectToEndOfStream(old, output)
        } catch (e: Exception) {
            Log.w(TAG, "Error draining decoder", e)
        }
        inputRoom = size + size / 2
        Log.w(TAG, "A $size-byte frame does not fit the decoder's $capacity-byte input buffers: " +
            "setting it up again with $inputRoom")
        release()
        createCodec()
        return mediaCodec ?: throw IllegalStateException("Decoder not configured")
    }

    /**
     * Signal end of stream and collect everything the codec still holds,
     * up to the end-of-stream output or [DRAIN_TIMEOUT_NS].
     */
    override fun drain(): List<DecodedAudio> {
        val codec = mediaCodec ?: return emptyList()
        val output = ArrayList<DecodedAudio>()
        try {
            // Every input slot can be taken by a codec that is behind, which
            // is when it holds output: collect that, then try again.
            var inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            for (attempt in 1..MAX_INPUT_RETRIES) {
                if (inputIndex >= 0) break
                drainOutput(codec, output)
                inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            }
            if (inputIndex < 0) return output
            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            collectToEndOfStream(codec, output)
        } catch (e: Exception) {
            Log.w(TAG, "Error draining decoder", e)
        }
        return output
    }

    /**
     * Collect [codec]'s output into [output], up to the end of stream it has
     * been given or [DRAIN_TIMEOUT_NS].
     */
    private fun collectToEndOfStream(codec: MediaCodec, output: MutableList<DecodedAudio>) {
        val bufferInfo = MediaCodec.BufferInfo()
        val deadline = System.nanoTime() + DRAIN_TIMEOUT_NS
        while (System.nanoTime() < deadline) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outputFormat = codec.outputFormat
            }
            if (outputIndex < 0) continue
            val outBuffer = codec.getOutputBuffer(outputIndex)
            if (outBuffer != null && bufferInfo.size > 0) {
                val pcmData = ByteArray(bufferInfo.size)
                outBuffer.position(bufferInfo.offset)
                outBuffer.get(pcmData, 0, bufferInfo.size)
                output += DecodedAudio(bufferInfo.presentationTimeUs, toStreamPcm(pcmData))
            }
            codec.releaseOutputBuffer(outputIndex, false)
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
    }

    /**
     * Collect the output the codec has ready, each buffer with its timestamp.
     *
     * Waits up to [TIMEOUT_US] for the first buffer, so in the normal case a
     * chunk's audio comes back from the call that submitted it. Once there is
     * output it stops waiting: anything the codec finishes later is picked up
     * by the next call, still carrying the right timestamp.
     *
     * Handles all dequeueOutputBuffer status codes:
     * - >= 0: Valid output buffer with PCM data to collect
     * - INFO_OUTPUT_FORMAT_CHANGED: Update cached format, continue draining
     * - INFO_OUTPUT_BUFFERS_CHANGED: Deprecated but harmless, continue draining
     * - INFO_TRY_AGAIN_LATER: No more output available, stop draining
     */
    private fun drainOutput(codec: MediaCodec, output: MutableList<DecodedAudio>) {
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(
                bufferInfo,
                if (output.isEmpty()) TIMEOUT_US else 0L,
            )

            when {
                outputIndex >= 0 -> {
                    val outBuffer = codec.getOutputBuffer(outputIndex)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        val pcmData = ByteArray(bufferInfo.size)
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.get(pcmData, 0, bufferInfo.size)
                        output += DecodedAudio(bufferInfo.presentationTimeUs, toStreamPcm(pcmData))
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outputFormat = codec.outputFormat
                    Log.d(TAG, "Output format changed: $outputFormat")
                    // Continue draining -- there may be more output buffers
                }

                @Suppress("DEPRECATION")
                outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                    // Deprecated since API 21, but some devices still return it.
                    // No action needed; continue draining.
                }

                else -> {
                    // INFO_TRY_AGAIN_LATER or any unknown negative value: done
                    break
                }
            }
        }
    }

    /** [pcm], as the codec put it out, at the stream's bit depth. */
    private fun toStreamPcm(pcm: ByteArray): ByteArray =
        PcmConverter.convert(pcm, pcmEncodingOf(outputFormat), streamBitDepth)

    /**
     * Flush the decoder to reset internal state.
     *
     * In synchronous mode (no callback set), flush() moves the codec to the
     * Flushed sub-state within the Executing state. The codec automatically
     * resumes to the Running sub-state on the next dequeueInputBuffer() call.
     * Calling start() here would be an illegal state transition (start() is
     * only valid from the Configured state, or after flush() in async mode).
     */
    override fun flush() {
        try {
            mediaCodec?.flush()
            Log.d(TAG, "Decoder flushed")
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing decoder", e)
        }
    }

    override fun release() {
        // stop() throws for a codec in its error state, after a fatal decode
        // error: release() is valid in any state and must still run
        try {
            mediaCodec?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping decoder", e)
        }
        try {
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing decoder", e)
        } finally {
            mediaCodec = null
            _isConfigured = false
            Log.d(TAG, "Decoder released")
        }
    }
}
