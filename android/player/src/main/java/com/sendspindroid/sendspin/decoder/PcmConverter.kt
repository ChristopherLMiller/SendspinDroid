package com.sendspindroid.sendspin.decoder

import android.media.AudioFormat
import kotlin.math.roundToLong

/**
 * Decoded PCM, from the encoding a decoder puts out to the bit depth its
 * stream plays at: signed little-endian 16, 24 (packed) or 32-bit.
 *
 * A FLAC decoder asked for high-resolution output hands back float, which
 * holds a 24-bit sample exactly: scaled back to 24 bits it is the sample the
 * server encoded. Integer samples are shifted; going down a depth drops the
 * low bits.
 */
object PcmConverter {

    /** Bytes one sample takes in [encoding], or null for an encoding this cannot read. */
    fun bytesPerSample(encoding: Int): Int? = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> 2
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
        else -> null
    }

    /** The integer encoding of [bitDepth]-bit PCM, or null for a depth this cannot write. */
    fun integerEncodingOf(bitDepth: Int): Int? = when (bitDepth) {
        16 -> AudioFormat.ENCODING_PCM_16BIT
        24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
        32 -> AudioFormat.ENCODING_PCM_32BIT
        else -> null
    }

    /**
     * [pcm] in [fromEncoding] as samples of [toBitDepth] bits. Returned as is
     * when it is in that depth already, or when either side is one this
     * cannot convert.
     */
    fun convert(pcm: ByteArray, fromEncoding: Int, toBitDepth: Int): ByteArray {
        val target = integerEncodingOf(toBitDepth) ?: return pcm
        if (fromEncoding == target) return pcm
        val inBytes = bytesPerSample(fromEncoding) ?: return pcm
        val outBytes = toBitDepth / 8
        val samples = pcm.size / inBytes
        val out = ByteArray(samples * outBytes)
        val fullScale = 1L shl (toBitDepth - 1)
        for (i in 0 until samples) {
            val raw = readLe(pcm, i * inBytes, inBytes)
            val value = if (fromEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                val f = Float.fromBits(raw)
                if (f.isNaN()) 0L else (f.toDouble() * fullScale).roundToLong().coerceIn(-fullScale, fullScale - 1)
            } else {
                // To the top of an Int, which signs it, then down to the target depth
                ((raw shl (32 - inBytes * 8)) shr (32 - toBitDepth)).toLong()
            }
            writeLe(out, i * outBytes, value, outBytes)
        }
        return out
    }

    /**
     * [pcm] of [bitDepth]-bit samples, scaled by [gain] (0.0 to 1.0). The same
     * array at a gain of 1, so nothing is touched at full volume; zeros at 0.
     * For an output Android applies no volume to.
     */
    fun applyGain(pcm: ByteArray, bitDepth: Int, gain: Float): ByteArray {
        if (gain >= 1f || integerEncodingOf(bitDepth) == null) return pcm
        if (gain <= 0f) return ByteArray(pcm.size)
        val bytes = bitDepth / 8
        val out = ByteArray(pcm.size)
        for (at in 0..pcm.size - bytes step bytes) {
            val sample = (readLe(pcm, at, bytes) shl (32 - bitDepth)) shr (32 - bitDepth)
            writeLe(out, at, (sample * gain.toDouble()).roundToLong(), bytes)
        }
        return out
    }

    private fun readLe(bytes: ByteArray, at: Int, count: Int): Int {
        var value = 0
        for (k in 0 until count) value = value or ((bytes[at + k].toInt() and 0xFF) shl (8 * k))
        return value
    }

    private fun writeLe(bytes: ByteArray, at: Int, value: Long, count: Int) {
        for (k in 0 until count) bytes[at + k] = (value shr (8 * k)).toByte()
    }
}
