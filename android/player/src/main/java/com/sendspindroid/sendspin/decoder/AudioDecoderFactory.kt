package com.sendspindroid.sendspin.decoder

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import com.sendspindroid.sendspin.protocol.QualityStep
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import java.util.concurrent.ConcurrentHashMap

/**
 * Factory for creating audio decoders based on codec type.
 *
 * Provides methods to create decoders and check codec support on the device.
 */
object AudioDecoderFactory {

    private const val TAG = "AudioDecoderFactory"

    /**
     * Create a decoder for the specified codec.
     *
     * @param codec The codec identifier ("pcm", "flac", "opus")
     * @return An appropriate AudioDecoder implementation
     * @throws IllegalArgumentException if the codec is unknown or this device
     *   cannot decode it. There is no PCM fallback: compressed bytes played as
     *   PCM are full-scale noise, so the caller must drop the stream's audio.
     */
    fun create(codec: String): AudioDecoder {
        val name = codec.lowercase()
        return when {
            name == "pcm" -> PcmDecoder()
            name == "flac" && isCodecSupported(name) -> FlacDecoder()
            name == "opus" && isCodecSupported(name) -> OpusDecoder()
            else -> throw IllegalArgumentException("No decoder for codec '$codec' on this device")
        }
    }

    /**
     * Check if a codec is supported on this device.
     *
     * @param codec The codec identifier ("pcm", "flac", "opus")
     * @return true if the codec is supported
     */
    fun isCodecSupported(codec: String): Boolean {
        return when (codec.lowercase()) {
            "pcm" -> true  // Always supported
            "flac" -> isMediaCodecSupported(MediaFormat.MIMETYPE_AUDIO_FLAC)
            "opus" -> isMediaCodecSupported(MediaFormat.MIMETYPE_AUDIO_OPUS)
            else -> false
        }
    }

    /**
     * Get list of all supported codecs on this device.
     *
     * @return List of supported codec identifiers
     */
    fun getSupportedCodecs(): List<String> {
        return listOf("pcm", "flac", "opus").filter { isCodecSupported(it) }
    }

    /**
     * Get list of PCM bit depths supported by the device's AudioTrack hardware.
     *
     * Probes AudioTrack.getMinBufferSize for each encoding to detect support.
     * 16-bit is always included. 24-bit packed (ENCODING_PCM_24BIT_PACKED) and
     * 32-bit integer (ENCODING_PCM_32BIT) require API 31+.
     *
     * @return Sorted list of supported bit depths (e.g., [16, 24, 32])
     */
    fun getSupportedPcmBitDepths(): List<Int> {
        val depths = mutableListOf(16)

        // 32-bit integer PCM (ENCODING_PCM_32BIT) - API 31+
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    SendSpinProtocol.AudioFormat.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_32BIT
                )
                if (minBuf > 0) depths.add(32)
            } catch (e: Exception) {
                Log.d(TAG, "32-bit integer PCM not supported: ${e.message}")
            }
        }

        // 24-bit packed (ENCODING_PCM_24BIT_PACKED) - API 31+
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    SendSpinProtocol.AudioFormat.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_24BIT_PACKED
                )
                if (minBuf > 0) depths.add(24)
            } catch (e: Exception) {
                Log.d(TAG, "24-bit packed PCM not supported: ${e.message}")
            }
        }

        return depths.sorted()
    }

    /**
     * The steps of the quality ladder this device plays at the default rate.
     * PCM 16 is always among them. FLAC 24 needs both a 24-bit output and a
     * FLAC decoder that hands back more than 16 bits
     * ([isHighResFlacDecodeSupported]). Low memory mode keeps every codec at
     * 16-bit, as it does PCM.
     */
    fun supportedQualitySteps(lowMemoryMode: Boolean): Set<QualityStep> {
        val outputDepths = if (lowMemoryMode) listOf(16) else getSupportedPcmBitDepths()
        return QualityStep.entries.filterTo(mutableSetOf()) { step ->
            when (step.codec) {
                "pcm" -> step.bitDepth in outputDepths
                "flac" -> isCodecSupported("flac") && (step.bitDepth == 16 ||
                    (step.bitDepth in outputDepths && isHighResFlacDecodeSupported()))
                else -> isCodecSupported(step.codec)
            }
        }
    }

    // Asked once: the probe creates a decoder.
    @Volatile
    private var highResFlacProbe: Boolean? = null

    /**
     * Whether this device's FLAC decoder hands back more than 16 bits a
     * sample. The platform decoders put out 16-bit PCM unless asked for
     * float, and float keeps every bit of a 24-bit stream. Found by
     * configuring the decoder [FlacDecoder] uses for float output and reading
     * back the encoding it will put out.
     */
    fun isHighResFlacDecodeSupported(): Boolean =
        highResFlacProbe ?: probeHighResFlac().also { highResFlacProbe = it }

    private fun probeHighResFlac(): Boolean {
        if (!isCodecSupported("flac")) return false
        var codec: MediaCodec? = null
        return try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_FLAC, SendSpinProtocol.AudioFormat.SAMPLE_RATE, 2
            )
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
            codec.configure(format, null, null, 0)
            val encoding = MediaCodecDecoder.pcmEncodingOf(codec.outputFormat)
            val highRes = (PcmConverter.bytesPerSample(encoding) ?: 0) > 2
            Log.i(TAG, "FLAC decoder ${codec.name} asked for float puts out encoding $encoding (high-res: $highRes)")
            highRes
        } catch (e: Exception) {
            Log.i(TAG, "FLAC decoder cannot put out float, FLAC stays at 16-bit: ${e.message}")
            false
        } finally {
            codec?.release()
        }
    }

    // A probe opens real audio tracks and decoder lookups, so each answer is
    // kept: the first connection pays for it, later ones read the map.
    private val pcmOutputProbeCache = ConcurrentHashMap<Pair<Int, Int>, Boolean>()
    private val codecRateProbeCache = ConcurrentHashMap<Pair<String, Int>, Boolean>()

    /**
     * Whether this device's audio output accepts stereo PCM at [sampleRate] and
     * [bitDepth], found by opening an [AudioTrack] the way playback does.
     *
     * The platform's documented sample-rate ceiling and what a given device and
     * output route accept do not always agree, so this asks the device rather
     * than a table. A track that opens only proves the platform took the
     * format: it may still resample to the output's native rate. Whether it
     * does not is logged where the platform can say (API 29+).
     *
     * 24-bit and 32-bit PCM need API 31+, as everywhere else in the player.
     */
    fun isPcmOutputSupported(sampleRate: Int, bitDepth: Int): Boolean =
        pcmOutputProbeCache.getOrPut(sampleRate to bitDepth) { probePcmOutput(sampleRate, bitDepth) }

    private fun probePcmOutput(sampleRate: Int, bitDepth: Int): Boolean {
        val encoding = when (bitDepth) {
            16 -> AudioFormat.ENCODING_PCM_16BIT
            24 -> if (Build.VERSION.SDK_INT >= 31) AudioFormat.ENCODING_PCM_24BIT_PACKED else return false
            32 -> if (Build.VERSION.SDK_INT >= 31) AudioFormat.ENCODING_PCM_32BIT else return false
            else -> return false
        }
        return try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_STEREO, encoding
            )
            if (minBuf <= 0) {
                Log.d(TAG, "PCM output ${sampleRate}Hz/${bitDepth}bit rejected: min buffer size $minBuf")
                return false
            }
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(encoding)
                .build()
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val opened = track.state == AudioTrack.STATE_INITIALIZED
            track.release()
            if (opened) {
                val direct = if (Build.VERSION.SDK_INT >= 29) {
                    AudioTrack.isDirectPlaybackSupported(format, attributes).toString()
                } else {
                    "unknown"
                }
                Log.i(TAG, "PCM output ${sampleRate}Hz/${bitDepth}bit accepted (direct playback: $direct)")
            } else {
                Log.d(TAG, "PCM output ${sampleRate}Hz/${bitDepth}bit rejected: track did not initialize")
            }
            opened
        } catch (e: Exception) {
            Log.d(TAG, "PCM output ${sampleRate}Hz/${bitDepth}bit rejected: ${e.message}")
            false
        }
    }

    /**
     * Whether this device has a decoder for [codec] that takes [sampleRate],
     * asked of the platform's codec list. PCM needs no decoder and is always
     * true here; whether its rate can be played is [isPcmOutputSupported].
     */
    fun isCodecSupportedAtSampleRate(codec: String, sampleRate: Int): Boolean {
        val mime = when (codec.lowercase()) {
            "flac" -> MediaFormat.MIMETYPE_AUDIO_FLAC
            "opus" -> MediaFormat.MIMETYPE_AUDIO_OPUS
            else -> return codec.equals("pcm", ignoreCase = true)
        }
        return codecRateProbeCache.getOrPut(mime to sampleRate) {
            try {
                val format = MediaFormat.createAudioFormat(mime, sampleRate, 2)
                val found = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) != null
                if (!found) Log.d(TAG, "No $mime decoder takes ${sampleRate}Hz")
                found
            } catch (e: Exception) {
                Log.d(TAG, "$mime decoder lookup at ${sampleRate}Hz failed: ${e.message}")
                false
            }
        }
    }

    private fun isMediaCodecSupported(mimeType: String): Boolean {
        val format = MediaFormat().apply { setString(MediaFormat.KEY_MIME, mimeType) }
        val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .findDecoderForFormat(format)
        if (codecName == null) {
            Log.d(TAG, "No decoder available for $mimeType")
        }
        return codecName != null
    }
}
