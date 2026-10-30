package com.sendspindroid.diagnostics

import com.sendspindroid.model.technicalLabel
import com.sendspindroid.sendspin.protocol.QualityLadder
import com.sendspindroid.sendspin.protocol.StreamFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One change of stream format: from the format the last stream played in to
 * the one a `stream/start` brought, on a running stream or with a new one,
 * across connections too. Up or down the quality ladder, or neither. Like a
 * [HandoffEpisode], categorical and timing only, so it is safe in a report.
 */
data class CodecChange(
    val ts: Long,
    val from: StreamFormat,
    val to: StreamFormat,
    /** A format change on a running stream, rather than a new stream. */
    val midStream: Boolean,
    /** TransportType of the network in use. */
    val network: String,
    /** What the connection asked for, as the step and quality; null when not known. */
    val asked: String?,
) {
    enum class Direction { UPGRADE, DOWNGRADE, CHANGE }

    val direction: Direction
        get() {
            val order = QualityLadder.compare(from, to)
            return when {
                order > 0 -> Direction.UPGRADE
                order < 0 -> Direction.DOWNGRADE
                else -> Direction.CHANGE
            }
        }

    /** One line for the stats sheet and the diagnostic snapshot. */
    fun summarize(): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ts))
        val how = if (midStream) "mid-stream" else "new stream"
        return "$time ${direction.name} ${from.technicalLabel()} -> ${to.technicalLabel()} " +
            "$how on $network asked=${asked ?: "?"}"
    }
}

/**
 * Keeps the recent [CodecChange]s, and counts upgrades and downgrades since
 * the service started. Fed every player `stream/start`; one that keeps the
 * format of the stream before it, or is the first, is no change.
 *
 * Takes primitives, like [HandoffEpisodeRecorder], so it is pure and
 * unit-testable. Called on the client's thread and read on the main one.
 */
class CodecChangeRecorder(
    private val capacity: Int = MAX_CHANGES,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val ring = ArrayDeque<CodecChange>()
    private var last: StreamFormat? = null

    var upgrades = 0
        @Synchronized get
        private set

    var downgrades = 0
        @Synchronized get
        private set

    @Synchronized
    fun onStreamStart(format: StreamFormat, midStream: Boolean, network: String, asked: String?) {
        val previous = last
        last = format
        if (previous == null || previous == format) return
        val change = CodecChange(clock(), previous, format, midStream, network, asked)
        ring.addLast(change)
        while (ring.size > capacity) ring.removeFirst()
        when (change.direction) {
            CodecChange.Direction.UPGRADE -> upgrades++
            CodecChange.Direction.DOWNGRADE -> downgrades++
            CodecChange.Direction.CHANGE -> {}
        }
    }

    @Synchronized
    fun recent(): List<CodecChange> = ring.toList()

    /** Newest-first summary block, in the shape of [HandoffEpisodeRecorder.summary]. */
    @Synchronized
    fun summary(): String {
        if (ring.isEmpty()) return "--- Codec changes ---\n(no codec changes recorded)"
        return buildString {
            appendLine("--- Codec changes (${ring.size} recent, $upgrades up, $downgrades down) ---")
            ring.reversed().forEach { appendLine(it.summarize()) }
        }.trimEnd()
    }

    companion object {
        const val MAX_CHANGES = 50
    }
}
