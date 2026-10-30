package com.sendspindroid.playback

import com.sendspindroid.sendspin.PlaybackState
import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.SyncAudioPlayer
import com.sendspindroid.sendspin.audio.OutputPreparer
import com.sendspindroid.sendspin.audio.OutputSetup
import com.sendspindroid.sendspin.decoder.AudioDecoder
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import com.sendspindroid.sendspin.decoder.DecodedAudio
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The engine asks the host's [OutputPreparer] how each player's format goes
 * out. A bit-perfect output that takes only wider samples gets the stream's
 * samples widened, every bit kept at the top.
 */
class SendSpinEngineBitPerfectTest {

    private val dispatcher = StandardTestDispatcher()
    private val players = mutableListOf<SyncAudioPlayer>()
    private val playerDepths = mutableListOf<Int>()
    private val prepared = mutableListOf<Triple<Int, Int, Int>>()
    private var setup: (Int) -> OutputSetup = { OutputSetup(it) }

    private lateinit var engine: SendSpinEngine

    @Before
    fun setUp() {
        mockkObject(AudioDecoderFactory)
        every { AudioDecoderFactory.create(any<String>()) } returns FakeAudioDecoder()
        engine = SendSpinEngine(
            listener = object : SendSpinEngine.Listener {
                override fun onStreamStarted() {}
                override fun onPlayerChanged(player: SyncAudioPlayer?) {}
                override fun onPlayerStateChanged(state: PlaybackState) {}
            },
            lowMemoryMode = { false },
            playerMuted = { false },
            mainDispatcher = dispatcher,
            decodeDispatcher = dispatcher,
            newPlayer = { _, sampleRate, channels, bitDepth, _ ->
                playerDepths += bitDepth
                mockk<SyncAudioPlayer>(relaxed = true).also { p ->
                    var bitPerfect = false
                    every { p.useBitPerfectOutput() } answers { bitPerfect = true }
                    every { p.isBitPerfect } answers { bitPerfect }
                    every { p.matchesFormat(any(), any(), any()) } answers {
                        firstArg<Int>() == sampleRate && secondArg<Int>() == channels && thirdArg<Int>() == bitDepth
                    }
                    every { p.bitDepth } returns bitDepth
                    every { p.bytesPerFrame } returns channels * bitDepth / 8
                    every { p.hasRoomFor(any()) } returns true
                    players += p
                }
            },
            prepareOutput = OutputPreparer { sampleRate, channels, bitDepth ->
                prepared += Triple(sampleRate, channels, bitDepth)
                setup(bitDepth)
            },
        ).also { it.client = mockk<SendSpin>(relaxed = true) }
    }

    @After
    fun tearDown() {
        engine.release()
        unmockkAll()
    }

    @Test
    fun `each player's format is prepared before the player is made`() {
        engine.onStreamStart("flac", 96_000, 2, 24, null)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(Triple(96_000, 2, 24)), prepared)
        assertEquals(listOf(24), playerDepths)
        verify(exactly = 0) { players.single().useBitPerfectOutput() }
    }

    @Test
    fun `a bit-perfect output plays at the depth it takes, and the player knows it is bit-perfect`() {
        setup = { OutputSetup(32, bitPerfect = true) }

        engine.onStreamStart("flac", 96_000, 2, 24, null)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(32), playerDepths)
        verify { players.single().useBitPerfectOutput() }
        assertTrue(engine.bitPerfect)
    }

    @Test
    fun `24-bit audio goes to a 32-bit output with its bits at the top`() {
        setup = { OutputSetup(32, bitPerfect = true) }
        val decoder = mockk<AudioDecoder>(relaxed = true) {
            // One stereo frame: 0x123456 and -2 in 24-bit packed little-endian
            every { decode(any(), any()) } answers {
                listOf(DecodedAudio(secondArg(), byteArrayOf(0x56, 0x34, 0x12, 0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte())))
            }
        }
        every { AudioDecoderFactory.create("flac") } returns decoder
        engine.onStreamStart("flac", 96_000, 2, 24, null)
        dispatcher.scheduler.advanceUntilIdle()

        engine.onAudioChunk(1_000L, ByteArray(8))
        dispatcher.scheduler.advanceUntilIdle()

        val queued = slot<ByteArray>()
        verify { players.single().queueChunk(1_000L, capture(queued), any()) }
        assertArrayEquals(
            byteArrayOf(0, 0x56, 0x34, 0x12, 0, 0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            queued.captured
        )
    }

    @Test
    fun `a new stream that the output now plays differently gets a new player`() {
        engine.onStreamStart("pcm", 48_000, 2, 16, null)
        dispatcher.scheduler.advanceUntilIdle()
        engine.onStreamEnd()
        dispatcher.scheduler.advanceUntilIdle()

        // Same format, but now bit-perfect: the mixer player cannot be kept
        setup = { OutputSetup(it, bitPerfect = true) }
        engine.onStreamStart("pcm", 48_000, 2, 16, null)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, players.size)
        verify { players[0].release() }
        verify { players[1].useBitPerfectOutput() }
    }
}
