package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.protocol.QualityStep.FLAC_16
import com.sendspindroid.sendspin.protocol.QualityStep.FLAC_24
import com.sendspindroid.sendspin.protocol.QualityStep.OPUS
import com.sendspindroid.sendspin.protocol.QualityStep.PCM_16
import com.sendspindroid.sendspin.protocol.QualityStep.PCM_24
import com.sendspindroid.sendspin.protocol.QualityStep.PCM_32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The quality ladder, lowest first: Opus, FLAC 16, FLAC 24, PCM 16, 24, 32.
 * A quality names a codec, and goes as far up that codec's bit depths as the
 * device plays.
 */
class QualityLadderTest {

    private val everything = QualityStep.entries.toSet()

    @Test
    fun `each quality takes its codec's highest step on a device that plays everything`() {
        assertEquals(OPUS, QualityLadder.resolve(AudioQuality.LOW, everything))
        assertEquals(FLAC_24, QualityLadder.resolve(AudioQuality.NORMAL, everything))
        assertEquals(PCM_32, QualityLadder.resolve(AudioQuality.HIGH, everything))
    }

    @Test
    fun `a device without high-resolution output stays at 16-bit`() {
        val sixteenBit = setOf(OPUS, FLAC_16, PCM_16)
        assertEquals(FLAC_16, QualityLadder.resolve(AudioQuality.NORMAL, sixteenBit))
        assertEquals(PCM_16, QualityLadder.resolve(AudioQuality.HIGH, sixteenBit))
    }

    @Test
    fun `high on a device with 24-bit but not 32-bit output takes PCM 24`() {
        assertEquals(PCM_24, QualityLadder.resolve(AudioQuality.HIGH, everything - PCM_32))
    }

    @Test
    fun `a quality whose codec the device lacks steps down before it steps up`() {
        // No FLAC decoder: Normal comes down to Opus rather than up to PCM
        assertEquals(OPUS, QualityLadder.resolve(AudioQuality.NORMAL, setOf(OPUS, PCM_16)))
        // No Opus decoder: nothing is below Low, so it goes up the least it can
        assertEquals(FLAC_16, QualityLadder.resolve(AudioQuality.LOW, setOf(FLAC_16, PCM_16)))
        assertEquals(PCM_16, QualityLadder.resolve(AudioQuality.LOW, setOf(PCM_16)))
    }

    @Test
    fun `nothing supported resolves to nothing`() {
        assertNull(QualityLadder.resolve(AudioQuality.NORMAL, emptySet()))
    }

    @Test
    fun `the offer order comes down the ladder from the preferred step, then goes up`() {
        assertEquals(
            listOf(FLAC_24, FLAC_16, OPUS, PCM_16, PCM_24, PCM_32),
            QualityLadder.offerOrder(FLAC_24, everything)
        )
        assertEquals(
            listOf(OPUS, FLAC_16, FLAC_24, PCM_16, PCM_24, PCM_32),
            QualityLadder.offerOrder(OPUS, everything)
        )
        assertEquals(
            listOf(PCM_32, PCM_24, PCM_16, FLAC_24, FLAC_16, OPUS),
            QualityLadder.offerOrder(PCM_32, everything)
        )
    }

    @Test
    fun `the offer order holds only what the device plays`() {
        assertEquals(listOf(PCM_16, FLAC_16, OPUS), QualityLadder.offerOrder(PCM_16, setOf(OPUS, FLAC_16, PCM_16)))
    }

    @Test
    fun `a stream's step comes from its codec and bit depth`() {
        assertEquals(OPUS, QualityLadder.stepOf("opus", 16))
        assertEquals(FLAC_24, QualityLadder.stepOf("FLAC", 24))
        assertEquals(PCM_24, QualityLadder.stepOf("pcm", 24))
        // A depth between steps takes the one under it
        assertEquals(FLAC_24, QualityLadder.stepOf("flac", 32))
        assertNull(QualityLadder.stepOf("mp3", 16))
    }

    @Test
    fun `an upgrade and a downgrade are told apart by step, then rate`() {
        val flac16 = StreamFormat("flac", 48_000, 2, 16)
        val opus = StreamFormat("opus", 48_000, 2, 16)
        val pcm24 = StreamFormat("pcm", 48_000, 2, 24)
        assertTrue(QualityLadder.compare(flac16, opus) < 0)
        assertTrue(QualityLadder.compare(opus, pcm24) > 0)
        // FLAC 24 is below PCM 16: the ladder goes by codec before depth
        assertTrue(QualityLadder.compare(StreamFormat("pcm", 48_000, 2, 16), StreamFormat("flac", 48_000, 2, 24)) < 0)
        // Same step, higher rate
        assertTrue(QualityLadder.compare(flac16, flac16.copy(sampleRate = 96_000)) > 0)
        // Channels alone are neither
        assertEquals(0, QualityLadder.compare(flac16, flac16.copy(channels = 1)))
    }

    @Test
    fun `a stream format ignores the codec header`() {
        val a = StreamConfig("flac", 48_000, 2, 24, byteArrayOf(1, 2, 3))
        val b = StreamConfig("flac", 48_000, 2, 24, byteArrayOf(4, 5, 6))
        assertEquals(a.format, b.format)
    }
}
