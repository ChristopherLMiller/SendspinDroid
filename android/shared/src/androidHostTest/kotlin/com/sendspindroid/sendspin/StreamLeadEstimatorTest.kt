package com.sendspindroid.sendspin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamLeadEstimatorTest {

    /** Feeds [count] chunks, one every 150 ms, and returns the timestamp after the last. */
    private fun feedSteadyChunks(
        estimator: StreamLeadEstimator,
        fromUs: Long,
        count: Int,
        bytes: Long = 1_000L,
    ): Long {
        var t = fromUs
        repeat(count) {
            estimator.onChunk(t, bytes)
            t += 150_000L
        }
        return t
    }

    /** Twenty chunks of 1,000 bytes every 150 ms: the newest starts at 2.85 s. */
    private fun estimatorWithTwentyChunks(): StreamLeadEstimator {
        val estimator = StreamLeadEstimator()
        estimator.reset(1_000_000L)
        feedSteadyChunks(estimator, 0L, 20)
        return estimator
    }

    @Test
    fun snapshotIsEmptyBeforeAnyChunk() {
        val snapshot = StreamLeadEstimator().snapshot()
        assertNull(snapshot.leadSec)
        assertNull(snapshot.bytesPerAudioSec)
        assertNull(snapshot.fill)
    }

    @Test
    fun bytesPerAudioSecondComeFromChunkGaps() {
        val estimator = estimatorWithTwentyChunks()
        assertEquals(1_000.0 / 0.15, estimator.snapshot().bytesPerAudioSec!!, 0.01)
    }

    @Test
    fun referenceIsBudgetOverBytesPerSecond() {
        val estimator = estimatorWithTwentyChunks()
        // 1,000,000 bytes at 6,666.7 bytes per second is 150 s
        assertEquals(150.0, estimator.snapshot().referenceSec!!, 0.01)
    }

    @Test
    fun leadIsNewestChunkMinusTheCursor() {
        val estimator = estimatorWithTwentyChunks()
        estimator.onSample(0L, 1_000_000L) // playback is at 1.0 s
        assertEquals(1.85, estimator.snapshot().leadSec!!, 1e-9)
    }

    @Test
    fun fillIsLeadOverReference() {
        val estimator = estimatorWithTwentyChunks()
        estimator.onSample(0L, 1_000_000L)
        val snapshot = estimator.snapshot()
        assertEquals(1.85 / snapshot.referenceSec!!, snapshot.fill!!, 1e-9)
    }

    @Test
    fun trendIsTheSlopeOfTheLead() {
        val estimator = estimatorWithTwentyChunks()
        // The lead falls by half a second every second
        for (second in 0..9) {
            val lead = 2.0 - 0.5 * second
            val cursorUs = 2_850_000L - (lead * 1_000_000).toLong()
            estimator.onSample(second * 1_000_000_000L, cursorUs)
        }
        assertEquals(-0.5, estimator.snapshot().trendSecPerSec!!, 1e-9)
    }

    @Test
    fun gapLongerThanAChunkIsNotCountedAsAudio() {
        val estimator = StreamLeadEstimator()
        estimator.reset(1_000_000L)
        var t = feedSteadyChunks(estimator, 0L, 10)
        estimator.onChunk(t, 1_000_000L) // a large chunk...
        t += 5_000_000L // ...followed by a five second gap
        feedSteadyChunks(estimator, t, 10)
        assertEquals(1_000.0 / 0.15, estimator.snapshot().bytesPerAudioSec!!, 0.01)
    }

    @Test
    fun oldChunksLeaveTheWindow() {
        val estimator = StreamLeadEstimator()
        estimator.reset(1_000_000L)
        val t = feedSteadyChunks(estimator, 0L, 70, bytes = 1_000L)
        feedSteadyChunks(estimator, t, 70, bytes = 2_000L)
        assertEquals(2_000.0 / 0.15, estimator.snapshot().bytesPerAudioSec!!, 0.01)
    }

    @Test
    fun leadIsUnknownWhenNothingIsPlaying() {
        val estimator = estimatorWithTwentyChunks()
        estimator.onSample(0L, 0L) // cursor 0: nothing has played yet
        val snapshot = estimator.snapshot()
        assertNull(snapshot.leadSec)
        assertNull(snapshot.trendSecPerSec)
    }

    @Test
    fun resetForgetsTheLeadAndTheChunks() {
        val estimator = estimatorWithTwentyChunks()
        estimator.onSample(0L, 1_000_000L)
        estimator.reset(1_000_000L)
        val snapshot = estimator.snapshot()
        assertNull(snapshot.leadSec)
        assertNull(snapshot.bytesPerAudioSec)
    }
}
