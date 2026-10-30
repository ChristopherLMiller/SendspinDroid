package com.sendspindroid.sendspin.protocol

/**
 * The audio quality the user picks, once for Wi-Fi and once for mobile data.
 * Each names a codec; how far up that codec's bit depths a stream goes is
 * down to what the device plays (see [QualityLadder.resolve]).
 */
enum class AudioQuality {
    /** Opus: lossy, the least data. */
    LOW,

    /** FLAC: lossless, compressed. */
    NORMAL,

    /** PCM: uncompressed, the most data. */
    HIGH,
}

/**
 * One step of the quality ladder, lowest first: Opus, FLAC 16, FLAC 24, then
 * PCM 16, 24 and 32. Every step is stereo at
 * [SendSpinProtocol.AudioFormat.SAMPLE_RATE].
 */
enum class QualityStep(val codec: String, val bitDepth: Int, val quality: AudioQuality) {
    OPUS("opus", 16, AudioQuality.LOW),
    FLAC_16("flac", 16, AudioQuality.NORMAL),
    FLAC_24("flac", 24, AudioQuality.NORMAL),
    PCM_16("pcm", 16, AudioQuality.HIGH),
    PCM_24("pcm", 24, AudioQuality.HIGH),
    PCM_32("pcm", 32, AudioQuality.HIGH),
}

/**
 * What a stream carries, without its codec header: for showing the stream,
 * and for comparing one stream with the next. A new codec header alone does
 * not change it.
 */
data class StreamFormat(
    val codec: String,
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int,
)

val StreamConfig.format: StreamFormat
    get() = StreamFormat(codec, sampleRate, channels, bitDepth)

object QualityLadder {

    /**
     * The step to stream at for [quality] on a device that plays [supported]:
     * the best of them at or under the top step of [quality]'s codec or, when
     * there is none, the lowest above it. Null only when [supported] is empty.
     */
    fun resolve(quality: AudioQuality, supported: Set<QualityStep>): QualityStep? {
        val top = QualityStep.entries.last { it.quality == quality }
        return supported.filter { it <= top }.maxOrNull()
            ?: supported.filter { it > top }.minOrNull()
    }

    /**
     * [supported] in the order to offer them, [preferred] first: then the
     * steps under it, best first, then those above it, lowest first. A server
     * that cannot stream the preferred step comes down the ladder before it
     * goes up.
     */
    fun offerOrder(preferred: QualityStep, supported: Set<QualityStep>): List<QualityStep> =
        listOf(preferred) +
            supported.filter { it < preferred }.sortedDescending() +
            supported.filter { it > preferred }.sorted()

    /**
     * The step a stream in [codec] at [bitDepth] is on: the codec's step for
     * that depth, else the highest under it, else its lowest. Null for a
     * codec that is not on the ladder.
     */
    fun stepOf(codec: String, bitDepth: Int): QualityStep? {
        val steps = QualityStep.entries.filter { it.codec.equals(codec, ignoreCase = true) }
        return steps.lastOrNull { it.bitDepth <= bitDepth } ?: steps.firstOrNull()
    }

    /**
     * Above zero when [to] is an upgrade on [from], below zero when it is a
     * downgrade, zero when it is neither (a change of channels only, say):
     * by ladder step, then bit depth, then sample rate. A codec off the
     * ladder ranks below every step.
     */
    fun compare(from: StreamFormat, to: StreamFormat): Int {
        val fromStep = stepOf(from.codec, from.bitDepth)?.ordinal ?: -1
        val toStep = stepOf(to.codec, to.bitDepth)?.ordinal ?: -1
        return when {
            toStep != fromStep -> toStep.compareTo(fromStep)
            to.bitDepth != from.bitDepth -> to.bitDepth.compareTo(from.bitDepth)
            else -> to.sampleRate.compareTo(from.sampleRate)
        }
    }
}
