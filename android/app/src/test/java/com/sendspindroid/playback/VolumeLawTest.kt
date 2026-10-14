package com.sendspindroid.playback

import kotlin.math.abs
import kotlin.math.log10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeLawTest {

    // AOSP's default media curve (default_volume_tables.xml): -58 dB at 1%,
    // -40 dB at 20%, -17 dB at 60%, 0 dB at 100%, interpolated over a 15-step
    // index the way AudioPolicy does it.
    private val maxIndex = 15
    private val points = listOf(1 to -58.0, 20 to -40.0, 60 to -17.0, 100 to 0.0)
    private val dbAt: (Int) -> Double = { index ->
        if (index == 0) Double.NEGATIVE_INFINITY else {
            val percent = (index * 100.0) / maxIndex
            val (lo, hi) = points.zipWithNext().first { (_, b) -> percent <= b.first }
            lo.second + (hi.second - lo.second) * (percent - lo.first) / (hi.first - lo.first)
        }
    }

    /** What the listener gets: the device gain at the index plus the track gain. */
    private fun outputDb(volume: Int): Double {
        val index = VolumeLaw.indexFor(volume, maxIndex, dbAt)
        return dbAt(index) + 20 * log10(VolumeLaw.trackGainFor(volume, index, dbAt).toDouble())
    }

    @Test
    fun `the output follows the spec curve at every volume`() {
        for (volume in 1..100) {
            val errorDb = outputDb(volume) - 30 * log10(volume / 100.0)
            assertTrue("volume $volume off by $errorDb dB", abs(errorDb) < 0.01)
        }
    }

    @Test
    fun `the spec's own examples`() {
        assertEquals(0.0, VolumeLaw.targetDb(100), 1e-9)
        assertEquals(-9.03, VolumeLaw.targetDb(50), 0.01)
        assertEquals(0.3536f, VolumeLaw.amplitude(50), 0.0001f)
        assertEquals(0f, VolumeLaw.amplitude(0), 0f)
    }

    @Test
    fun `track gain only ever attenuates`() {
        for (volume in 0..100) {
            val index = VolumeLaw.indexFor(volume, maxIndex, dbAt)
            val gain = VolumeLaw.trackGainFor(volume, index, dbAt)
            assertTrue("volume $volume gain $gain", gain in 0f..1f)
        }
    }

    @Test
    fun `a volume above 0 is never the silent index`() {
        assertEquals(0, VolumeLaw.indexFor(0, maxIndex, dbAt))
        for (volume in 1..100) assertTrue(VolumeLaw.indexFor(volume, maxIndex, dbAt) >= 1)
    }

    @Test
    fun `device steps report the volume their gain stands for`() {
        assertEquals(0, VolumeLaw.volumeFor(0, dbAt))
        assertEquals(100, VolumeLaw.volumeFor(maxIndex, dbAt))
        // Index 8 of 15 sits at -21 dB on the default curve: 20% louder than
        // half volume would be on a linear index, much quieter than 53.
        val v = VolumeLaw.volumeFor(8, dbAt)
        assertEquals(-21.0, 30 * log10(v / 100.0), 0.3)
        // Rising with the index
        val reported = (1..maxIndex).map { VolumeLaw.volumeFor(it, dbAt) }
        assertEquals(reported.sorted(), reported)
    }

    @Test
    fun `a device step maps back to itself`() {
        for (index in 1..maxIndex) {
            val volume = VolumeLaw.volumeFor(index, dbAt)
            assertEquals("index $index -> volume $volume", index, VolumeLaw.indexFor(volume, maxIndex, dbAt))
        }
    }
}
