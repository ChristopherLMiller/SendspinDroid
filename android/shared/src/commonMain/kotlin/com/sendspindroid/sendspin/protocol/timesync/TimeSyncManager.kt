package com.sendspindroid.sendspin.protocol.timesync

import com.sendspindroid.sendspin.SendspinTimeFilter
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import com.sendspindroid.sendspin.protocol.TimeMeasurement
import com.sendspindroid.shared.log.Log
import com.sendspindroid.shared.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives the NTP-style time-sync burst loop and feeds its filter.
 *
 * Threading: [start] launches the burst-send loop on the supplied scope
 * (in production a dedicated single-thread dispatcher). [onServerTime]
 * is called from the WebSocket transport's receive thread:
 *   - During a burst window, replies are queued under
 *     `pendingBurstMeasurements` and processed later on the burst-loop
 *     thread.
 *   - Outside a burst window, the reply is fed straight to the filter
 *     on the receive thread.
 *
 * The two paths can therefore both call `timeFilter.addMeasurement` on
 * different threads. That is safe by design: the filter's internal
 * mutex serialises both paths, and out-of-burst replies are not in
 * competition with any burst's best-of-RTT selection (no burst is
 * active by definition).
 *
 * Deep sleep: the client timestamps are on CLOCK_MONOTONIC, which stops
 * while the device sleeps, and the server's clock does not, so after a
 * sleep the offset has stepped by the time asleep. [suspendedTimeMicros]
 * (the time spent asleep since boot) tells each burst whether there was
 * one; if so the filter starts over, and a burst whose round trips span
 * the sleep is discarded.
 */
class TimeSyncManager(
    private val timeFilter: SendspinTimeFilter,
    private val sendClientTime: () -> Unit,
    private val onMeasurementApplied: () -> Unit = {},
    private val tag: String = "TimeSyncManager",
    private val suspendedTimeMicros: () -> Long = Platform::suspendedTimeMicros,
) {
    companion object {
        private const val MAX_ACCEPTABLE_RTT_US = 10_000_000L
        private const val RTT_HISTORY_SIZE = 15
        private const val BURST_COUNT_HIGH_JITTER = 15
        private const val BURST_COUNT_LOW_JITTER = 5
        private const val INTERVAL_MS_HIGH_JITTER = 200L
        private const val INTERVAL_MS_LOW_JITTER = 500L
        private const val BURST_COUNT_CONVERGED = 3
        private const val INTERVAL_MS_CONVERGED = 3000L
        private const val HIGH_JITTER_THRESHOLD_US = 20_000L
        private const val LOW_JITTER_THRESHOLD_US = 5_000L

        // More time asleep than this since the last burst starts sync over.
        // Any real sleep is far longer; the reading itself is good to 50 us.
        private const val SLEEP_STEP_US = 1_000L

        // After a sleep: bursts without the usual wait, enough for the
        // filter to be ready again (two measurements)
        private const val BURSTS_AFTER_SLEEP = 2
    }

    @Volatile
    private var running = false
    private var syncJob: Job? = null

    private val pendingBurstMeasurements = mutableListOf<TimeMeasurement>()
    @Volatile
    private var burstInProgress = false

    private val rttHistory = LongArray(RTT_HISTORY_SIZE)
    private var rttHistoryIndex = 0
    private var rttHistoryCount = 0

    private var currentBurstCount = SendSpinProtocol.TimeSync.BURST_COUNT
    private var currentIntervalMs = SendSpinProtocol.TimeSync.INTERVAL_MS

    // Time asleep since boot as of the last burst, and as this one began.
    // Guarded by pendingBurstMeasurements.
    private var suspendedAtLastBurstUs = 0L
    private var suspendedAtBurstStartUs = 0L

    // Bursts to send without waiting the interval. Burst loop only.
    private var immediateBursts = 0

    val isRunning: Boolean
        get() = running

    // Visible for testing: burst strategy and RTT history state
    internal val testCurrentBurstCount: Int get() = synchronized(pendingBurstMeasurements) { currentBurstCount }
    internal val testCurrentIntervalMs: Long get() = synchronized(pendingBurstMeasurements) { currentIntervalMs }
    internal val testRttHistoryCount: Int get() = synchronized(pendingBurstMeasurements) { rttHistoryCount }

    fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        synchronized(pendingBurstMeasurements) {
            suspendedAtLastBurstUs = suspendedTimeMicros()
        }
        immediateBursts = 0

        syncJob = scope.launch {
            sendTimeSyncBurst()

            while (running && isActive) {
                if (immediateBursts > 0) immediateBursts-- else delay(currentIntervalMs)
                if (running) {
                    sendTimeSyncBurst()
                }
            }
        }
    }

    fun stop() {
        running = false
        syncJob?.cancel()
        syncJob = null
        synchronized(pendingBurstMeasurements) {
            pendingBurstMeasurements.clear()
            burstInProgress = false
            rttHistoryIndex = 0
            rttHistoryCount = 0
            currentBurstCount = SendSpinProtocol.TimeSync.BURST_COUNT
            currentIntervalMs = SendSpinProtocol.TimeSync.INTERVAL_MS
        }
    }

    /**
     * Feed a `server/time` measurement to the manager.
     *
     * @return `true` if the measurement was buffered for the in-progress
     *   burst's best-of-RTT selection. `false` if it was processed
     *   immediately (out-of-burst path), dropped as stale, or arrived
     *   while the manager is stopped. Callers that just want to forward
     *   the measurement can ignore the return value.
     */
    fun onServerTime(measurement: TimeMeasurement): Boolean {
        if (!running) return false

        synchronized(pendingBurstMeasurements) {
            if (burstInProgress) {
                pendingBurstMeasurements.add(measurement)
                return true
            }
        }

        if (measurement.rtt > MAX_ACCEPTABLE_RTT_US) {
            Log.v(tag, "Ignoring stale time response: RTT=${measurement.rtt / 1_000_000}s")
            return false
        }

        // A sleep since the last burst: its round trip may span it, and the
        // next burst starts sync over
        val sleptUs = suspendedTimeMicros() - synchronized(pendingBurstMeasurements) { suspendedAtLastBurstUs }
        if (sleptUs > SLEEP_STEP_US) return false

        val maxError = computeMaxError(measurement.rtt)
        timeFilter.addMeasurement(measurement.offset, maxError, measurement.clientReceived)

        if (timeFilter.isReady) {
            Log.v(tag, "Time sync: offset=${timeFilter.offsetMicros}μs, error=${timeFilter.errorMicros}μs")
        }

        onMeasurementApplied()
        return false
    }

    private suspend fun sendTimeSyncBurst() {
        synchronized(pendingBurstMeasurements) {
            pendingBurstMeasurements.clear()
            burstInProgress = true
            suspendedAtBurstStartUs = suspendedTimeMicros()
        }

        try {
            repeat(currentBurstCount) {
                if (!running || !currentCoroutineContext().isActive) return
                sendClientTime()
                delay(SendSpinProtocol.TimeSync.BURST_DELAY_MS)
            }

            delay(SendSpinProtocol.TimeSync.BURST_DELAY_MS * 2)

            processBurstResults()
        } finally {
            synchronized(pendingBurstMeasurements) {
                burstInProgress = false
            }
        }
    }

    private fun processBurstResults() {
        synchronized(pendingBurstMeasurements) {
            burstInProgress = false

            val suspendedNowUs = suspendedTimeMicros()
            val sleptUs = suspendedNowUs - suspendedAtLastBurstUs
            suspendedAtLastBurstUs = suspendedNowUs
            if (sleptUs > SLEEP_STEP_US) {
                Log.i(tag, "Device was asleep for ${sleptUs / 1000}ms - starting time sync over")
                timeFilter.reset()
                immediateBursts = BURSTS_AFTER_SLEEP
                if (suspendedNowUs - suspendedAtBurstStartUs > SLEEP_STEP_US) {
                    // This burst's round trips span the sleep
                    pendingBurstMeasurements.clear()
                    return@synchronized
                }
                // All of this burst came after it: it is the first measurement
                immediateBursts = BURSTS_AFTER_SLEEP - 1
            }

            if (pendingBurstMeasurements.isEmpty()) {
                Log.w(tag, "No time sync responses received in burst")
                return
            }

            // A round trip that leaves no positive max_error is one the filter
            // skips, so it must not win the burst
            val validMeasurements = pendingBurstMeasurements.filter {
                it.rtt < MAX_ACCEPTABLE_RTT_US && computeMaxError(it.rtt) > 0
            }
            if (validMeasurements.isEmpty()) {
                Log.w(tag, "All ${pendingBurstMeasurements.size} responses had RTT > ${MAX_ACCEPTABLE_RTT_US / 1_000_000}s - skipping burst")
                pendingBurstMeasurements.clear()
                return
            }

            val best = validMeasurements.minByOrNull { it.rtt }!!

            val maxError = computeMaxError(best.rtt)

            recordRtt(best.rtt)
            updateBurstStrategy()

            val staleCount = pendingBurstMeasurements.size - validMeasurements.size
            Log.v(tag, "Time sync burst: ${validMeasurements.size}/$currentBurstCount responses" +
                    (if (staleCount > 0) " ($staleCount stale rejected)" else "") +
                    ", best RTT=${best.rtt}μs, offset=${best.offset}μs")

            val accepted = timeFilter.addMeasurement(best.offset, maxError, best.clientReceived)

            if (timeFilter.isReady) {
                Log.v(tag, "Time sync: offset=${timeFilter.offsetMicros}μs, error=${timeFilter.errorMicros}μs, " +
                        "drift=${"%.3f".format(timeFilter.driftPpm)}ppm" +
                        if (!accepted) " [rejected]" else "")
            }

            pendingBurstMeasurements.clear()
        }
        onMeasurementApplied()
    }

    private fun computeMaxError(rtt: Long): Long = rtt / 2L

    private fun recordRtt(rtt: Long) {
        rttHistory[rttHistoryIndex] = rtt
        rttHistoryIndex = (rttHistoryIndex + 1) % RTT_HISTORY_SIZE
        if (rttHistoryCount < RTT_HISTORY_SIZE) rttHistoryCount++
    }

    private fun updateBurstStrategy() {
        if (rttHistoryCount < 5) return

        val count = minOf(rttHistoryCount, RTT_HISTORY_SIZE)
        val sorted = LongArray(count)
        for (i in 0 until count) {
            sorted[i] = rttHistory[i]
        }
        sorted.sort()

        val q1 = sorted[count / 4]
        val q3 = sorted[(count * 3) / 4]
        val jitter = q3 - q1

        when {
            jitter > HIGH_JITTER_THRESHOLD_US -> {
                currentBurstCount = BURST_COUNT_HIGH_JITTER
                currentIntervalMs = INTERVAL_MS_HIGH_JITTER
            }
            jitter < LOW_JITTER_THRESHOLD_US -> {
                currentBurstCount = BURST_COUNT_LOW_JITTER
                currentIntervalMs = INTERVAL_MS_LOW_JITTER
            }
            else -> {
                currentBurstCount = SendSpinProtocol.TimeSync.BURST_COUNT
                currentIntervalMs = SendSpinProtocol.TimeSync.INTERVAL_MS
            }
        }

        if (timeFilter.isConverged) {
            currentBurstCount = BURST_COUNT_CONVERGED
            currentIntervalMs = INTERVAL_MS_CONVERGED
        }
    }
}
