package com.sendspindroid.diagnostics

import com.sendspindroid.sendspin.protocol.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecChangeRecorderTest {

    private var now = 1_000L
    private val recorder = CodecChangeRecorder(capacity = 3, clock = { now })

    private val opus = StreamFormat("opus", 48_000, 2, 16)
    private val flac16 = StreamFormat("flac", 48_000, 2, 16)
    private val flac24 = StreamFormat("flac", 48_000, 2, 24)
    private val pcm32 = StreamFormat("pcm", 48_000, 2, 32)

    private fun start(format: StreamFormat, midStream: Boolean = false, network: String = "WIFI") =
        recorder.onStreamStart(format, midStream, network, asked = "FLAC_24 (NORMAL on Wi-Fi)")

    @Test
    fun `the first stream is no change`() {
        start(flac24)

        assertTrue(recorder.recent().isEmpty())
        assertEquals("--- Codec changes ---\n(no codec changes recorded)", recorder.summary())
    }

    @Test
    fun `a stream in the format of the one before is no change`() {
        start(flac24)
        start(flac24, midStream = true)
        start(flac24)

        assertTrue(recorder.recent().isEmpty())
    }

    @Test
    fun `going down the ladder is a downgrade, and up it an upgrade`() {
        start(flac24)
        now = 2_000L
        start(opus, network = "CELLULAR")
        now = 3_000L
        start(pcm32, midStream = true)

        val (down, up) = recorder.recent()
        assertEquals(CodecChange.Direction.DOWNGRADE, down.direction)
        assertEquals(flac24, down.from)
        assertEquals(opus, down.to)
        assertEquals("CELLULAR", down.network)
        assertFalse(down.midStream)
        assertEquals(2_000L, down.ts)
        assertEquals(CodecChange.Direction.UPGRADE, up.direction)
        assertTrue(up.midStream)
        assertEquals(1, recorder.upgrades)
        assertEquals(1, recorder.downgrades)
    }

    @Test
    fun `changes are compared across streams and connections`() {
        // A stream ended, the connection came back over mobile data
        start(flac16)
        start(opus, network = "CELLULAR")

        assertEquals(opus, recorder.recent().single().to)
        assertEquals(flac16, recorder.recent().single().from)
    }

    @Test
    fun `a change of channels alone is neither up nor down`() {
        start(flac16)
        start(flac16.copy(channels = 1))

        assertEquals(CodecChange.Direction.CHANGE, recorder.recent().single().direction)
        assertEquals(0, recorder.upgrades)
        assertEquals(0, recorder.downgrades)
    }

    @Test
    fun `only the most recent changes are kept, but the counts go on`() {
        start(opus)
        repeat(4) {
            start(flac16)
            start(opus)
        }

        assertEquals(3, recorder.recent().size)
        assertEquals(4, recorder.upgrades)
        assertEquals(4, recorder.downgrades)
    }

    @Test
    fun `the summary lists the newest first, each line what changed and why`() {
        start(flac24)
        start(opus, network = "CELLULAR")
        start(flac24, midStream = true)

        val lines = recorder.summary().lines()
        assertEquals("--- Codec changes (2 recent, 1 up, 1 down) ---", lines[0])
        assertTrue(lines[1], lines[1].contains(" UPGRADE OPUS 48 kHz 16-bit -> FLAC 48 kHz 24-bit mid-stream on WIFI"))
        assertTrue(lines[2], lines[2].contains(" DOWNGRADE FLAC 48 kHz 24-bit -> OPUS 48 kHz 16-bit new stream on CELLULAR"))
        assertTrue(lines[2], lines[2].endsWith("asked=FLAC_24 (NORMAL on Wi-Fi)"))
    }
}
