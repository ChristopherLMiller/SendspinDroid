package com.sendspindroid.playback

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * The protocol's volume (0-100) is perceived loudness: roles/player/v1.md asks
 * for `amplitude = (volume / 100)^1.5`, which is `30 * log10(volume / 100)` dB.
 *
 * The app keeps volume on the device's STREAM_MUSIC index, so the hardware
 * keys work. A device applies its own curve to that index, so the index is
 * chosen from that curve ([dbAt], from AudioManager.getStreamVolumeDb), and
 * the rest of the way down to the target is gain on the AudioTrack.
 */
internal object VolumeLaw {

    /** The gain in dB the spec asks for at [volume]; minus infinity at 0. */
    fun targetDb(volume: Int): Double =
        if (volume <= 0) Double.NEGATIVE_INFINITY else 30.0 * log10(volume / 100.0)

    /** The spec's linear amplitude for [volume]. */
    fun amplitude(volume: Int): Float = (volume.coerceIn(0, 100) / 100.0).pow(1.5).toFloat()

    /**
     * The index for [volume]: the lowest one whose device gain reaches the
     * target, so the gain left for the track is at most 0 dB. A volume above
     * 0 never maps to index 0, which is silent.
     */
    fun indexFor(volume: Int, maxIndex: Int, dbAt: (Int) -> Double): Int {
        if (volume <= 0) return 0
        val target = targetDb(volume)
        return (1..maxIndex).firstOrNull { dbAt(it) >= target - TOLERANCE_DB } ?: maxIndex
    }

    /** Gain for the track that brings [index]'s device gain down to [volume]'s target. */
    fun trackGainFor(volume: Int, index: Int, dbAt: (Int) -> Double): Float {
        if (volume <= 0 || index <= 0) return 1f
        val residualDb = targetDb(volume) - dbAt(index)
        return 10.0.pow(residualDb / 20.0).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * The volume that [index] stands for on its own, with no track gain: what
     * device controls report. Rounded down, so that the server sending it
     * back lands on the same index.
     */
    fun volumeFor(index: Int, dbAt: (Int) -> Double): Int {
        if (index <= 0) return 0
        val db = dbAt(index)
        if (db == Double.NEGATIVE_INFINITY) return 0
        return floor(100 * 10.0.pow(db / 30.0) + 1e-9).toInt().coerceIn(1, 100)
    }

    // Rounding in a device's dB table must not push a volume to the next index
    private const val TOLERANCE_DB = 0.01
}
