package com.sendspindroid.sendspin

/**
 * How far ahead of playback the server has sent audio, and how full that is
 * against a healthy link.
 *
 * The server keeps [bufferCapacityBytes] of wire bytes outstanding. Each chunk
 * counts, header included, until it has played (roles/player/v1.md, "Player
 * Buffer Accounting"). A link that keeps up holds that budget full, so the lead
 * of a full buffer is the budget divided by the bytes per second of audio being
 * sent. [Snapshot.fill] compares the lead with that reference: 1.0 is full, and
 * a fill that keeps falling means the link is not keeping up.
 *
 * Diagnostics only. Nothing acts on these readings yet.
 *
 * Thread-safe: chunks arrive on the socket thread, and the stats path reads
 * [snapshot] and samples the lead too.
 */
class StreamLeadEstimator {

    /** A reading. Each field is null until there is enough data to measure it. */
    data class Snapshot(
        /** Newest chunk's server timestamp minus the playback cursor, in seconds. */
        val leadSec: Double?,
        /** Budget divided by [bytesPerAudioSec], in seconds: the lead of a full buffer. */
        val referenceSec: Double?,
        /** [leadSec] over [referenceSec]. 1.0 is full. */
        val fill: Double?,
        /** Least-squares slope of the lead over the last samples, in seconds per second. */
        val trendSecPerSec: Double?,
        /** Wire bytes per second of audio, from the most recent chunks. */
        val bytesPerAudioSec: Double?,
    )

    private var bufferCapacityBytes = 0L

    // A chunk's audio lasts until the next chunk starts, so its duration is
    // known only once the next chunk arrives.
    private var newestChunkUs = 0L
    private var hasNewestChunk = false
    private var previousChunkUs = 0L
    private var previousChunkBytes = 0L
    private var hasPreviousChunk = false

    // Recent chunks, for bytes per second of audio
    private val windowDurationUs = LongArray(CHUNK_WINDOW)
    private val windowBytes = LongArray(CHUNK_WINDOW)
    private var windowCount = 0
    private var windowNext = 0
    private var windowDurationSumUs = 0L
    private var windowBytesSum = 0L

    // Recent lead samples, for the trend
    private val sampleTimeSec = DoubleArray(TREND_SAMPLES)
    private val sampleLeadSec = DoubleArray(TREND_SAMPLES)
    private var sampleCount = 0
    private var sampleNext = 0
    private var firstSampleNs = 0L
    private var lastSampleNs = 0L
    private var leadSec: Double? = null

    /**
     * Start over, for a new stream, a seek or the end of a stream.
     *
     * @param capacityBytes The buffer_capacity advertised in client/hello.
     */
    @Synchronized
    fun reset(capacityBytes: Long) {
        bufferCapacityBytes = capacityBytes
        hasNewestChunk = false
        hasPreviousChunk = false
        windowCount = 0
        windowNext = 0
        windowDurationSumUs = 0L
        windowBytesSum = 0L
        sampleCount = 0
        sampleNext = 0
        leadSec = null
    }

    /**
     * One audio chunk arrived.
     *
     * @param timestampUs Its server timestamp: when its first sample plays.
     * @param wireBytes Its size on the wire, the 13-byte header included.
     */
    @Synchronized
    fun onChunk(timestampUs: Long, wireBytes: Long) {
        newestChunkUs = timestampUs
        hasNewestChunk = true
        if (hasPreviousChunk) {
            val durationUs = timestampUs - previousChunkUs
            // Chunks are at most 150 ms (roles/player/v1.md), so a longer gap is
            // a seek or a stall, not audio.
            if (durationUs in 1L..MAX_CHUNK_US) addToWindow(durationUs, previousChunkBytes)
        }
        previousChunkUs = timestampUs
        previousChunkBytes = wireBytes
        hasPreviousChunk = true
    }

    /**
     * Read the lead. Every call updates [snapshot]; the trend takes at most one
     * sample a second.
     *
     * @param nowNs Monotonic time, as System.nanoTime().
     * @param cursorUs The playback cursor on the server clock, or 0 when not playing.
     */
    @Synchronized
    fun onSample(nowNs: Long, cursorUs: Long) {
        if (cursorUs <= 0L || !hasNewestChunk) {
            // Not playing: the lead and its trend start again
            leadSec = null
            sampleCount = 0
            sampleNext = 0
            return
        }
        val lead = (newestChunkUs - cursorUs) / 1_000_000.0
        leadSec = lead
        if (sampleCount > 0 && nowNs - lastSampleNs < SAMPLE_INTERVAL_NS) return
        if (sampleCount == 0) firstSampleNs = nowNs
        lastSampleNs = nowNs
        sampleTimeSec[sampleNext] = (nowNs - firstSampleNs) / 1_000_000_000.0
        sampleLeadSec[sampleNext] = lead
        sampleNext = (sampleNext + 1) % TREND_SAMPLES
        if (sampleCount < TREND_SAMPLES) sampleCount++
    }

    /** The current reading. */
    @Synchronized
    fun snapshot(): Snapshot {
        val bytesPerSec = bytesPerAudioSec()
        val reference = if (bytesPerSec != null && bufferCapacityBytes > 0L) {
            bufferCapacityBytes / bytesPerSec
        } else {
            null
        }
        val lead = leadSec
        return Snapshot(
            leadSec = lead,
            referenceSec = reference,
            fill = if (lead != null && reference != null) lead / reference else null,
            trendSecPerSec = trendSecPerSec(),
            bytesPerAudioSec = bytesPerSec,
        )
    }

    private fun addToWindow(durationUs: Long, bytes: Long) {
        if (windowCount == CHUNK_WINDOW) {
            windowDurationSumUs -= windowDurationUs[windowNext]
            windowBytesSum -= windowBytes[windowNext]
        } else {
            windowCount++
        }
        windowDurationUs[windowNext] = durationUs
        windowBytes[windowNext] = bytes
        windowDurationSumUs += durationUs
        windowBytesSum += bytes
        windowNext = (windowNext + 1) % CHUNK_WINDOW
    }

    private fun bytesPerAudioSec(): Double? {
        if (windowCount < MIN_WINDOW_CHUNKS || windowDurationSumUs <= 0L || windowBytesSum <= 0L) {
            return null
        }
        return windowBytesSum * 1_000_000.0 / windowDurationSumUs
    }

    /** Least squares over the samples: the slope of the lead against time. */
    private fun trendSecPerSec(): Double? {
        if (sampleCount < MIN_TREND_SAMPLES) return null
        val oldest = (sampleNext - sampleCount + TREND_SAMPLES) % TREND_SAMPLES
        var meanTime = 0.0
        var meanLead = 0.0
        for (i in 0 until sampleCount) {
            val slot = (oldest + i) % TREND_SAMPLES
            meanTime += sampleTimeSec[slot]
            meanLead += sampleLeadSec[slot]
        }
        meanTime /= sampleCount
        meanLead /= sampleCount
        var covariance = 0.0
        var variance = 0.0
        for (i in 0 until sampleCount) {
            val slot = (oldest + i) % TREND_SAMPLES
            val dt = sampleTimeSec[slot] - meanTime
            covariance += dt * (sampleLeadSec[slot] - meanLead)
            variance += dt * dt
        }
        return if (variance > 0.0) covariance / variance else null
    }

    private companion object {
        /** About ten seconds of audio: 64 chunks of 150 ms. */
        private const val CHUNK_WINDOW = 64

        /** Chunks needed before the bytes per second is trusted. */
        private const val MIN_WINDOW_CHUNKS = 8

        /** Lead samples kept for the trend: twenty seconds at one a second. */
        private const val TREND_SAMPLES = 20

        /** Samples needed before a trend is reported. */
        private const val MIN_TREND_SAMPLES = 10

        private const val SAMPLE_INTERVAL_NS = 1_000_000_000L

        /** Longest gap between chunk timestamps that still counts as audio. */
        private const val MAX_CHUNK_US = 1_000_000L
    }
}
