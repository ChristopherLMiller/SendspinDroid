package com.sendspindroid.playback

import com.sendspindroid.model.PlaybackStateType
import com.sendspindroid.sendspin.PlaybackState
import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.SyncAudioPlayer
import com.sendspindroid.sendspin.SyncAudioPlayer.MuteReason
import com.sendspindroid.sendspin.SyncAudioPlayerCallback
import com.sendspindroid.sendspin.decoder.AudioDecoder
import com.sendspindroid.sendspin.decoder.DecodedAudio
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** What the engine does with its player around the decode pipeline. */
class SendSpinEngineTest {

    private val dispatcher = StandardTestDispatcher()
    private val events = mutableListOf<String>()
    private val players = mutableListOf<SyncAudioPlayer>()
    private var playerMuted = false

    private lateinit var engine: SendSpinEngine

    @Before
    fun setUp() {
        mockkObject(AudioDecoderFactory)
        every { AudioDecoderFactory.create(any<String>()) } returns FakeAudioDecoder()
        engine = SendSpinEngine(
            listener = object : SendSpinEngine.Listener {
                override fun onStreamStarted() { events += "started" }
                override fun onPlayerChanged(player: SyncAudioPlayer?) {
                    events += if (player == null) "player=null" else "player=${players.indexOf(player)}"
                }
                override fun onPlayerStateChanged(state: PlaybackState) {}
            },
            lowMemoryMode = { false },
            playerMuted = { playerMuted },
            mainDispatcher = dispatcher,
            decodeDispatcher = dispatcher,
            newPlayer = { _, sampleRate, channels, bitDepth, _ ->
                mockk<SyncAudioPlayer>(relaxed = true).also { p ->
                    every { p.matchesFormat(any(), any(), any()) } answers {
                        firstArg<Int>() == sampleRate && secondArg<Int>() == channels && thirdArg<Int>() == bitDepth
                    }
                    every { p.bytesPerFrame } returns channels * bitDepth / 8
                    every { p.hasRoomFor(any()) } returns true
                    players += p
                }
            },
        ).also { it.client = mockk<SendSpin>(relaxed = true) }
    }

    @After
    fun tearDown() {
        engine.release()
        unmockkAll()
    }

    private fun startStream(sampleRate: Int = 48_000) {
        engine.onStreamStart("pcm", sampleRate, 2, 16, null)
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `the host hears of a stream before its player`() {
        startStream()

        assertEquals(listOf("started", "player=0"), events)
        assertSame(players[0], engine.player)
    }

    @Test
    fun `a stream start that repeats the active format keeps the player`() {
        startStream()
        startStream()

        assertEquals(1, players.size)
        assertEquals(listOf("started", "player=0"), events)
    }

    @Test
    fun `a new stream in the same format reuses the player and clears it`() {
        startStream()
        engine.onStreamEnd()
        startStream()

        assertEquals(1, players.size)
        verifyOrder {
            players[0].enterIdle()
            players[0].clearBuffer()
        }
    }

    @Test
    fun `voice and interruption mutes set between streams apply to the next player`() {
        engine.setMuted(MuteReason.VOICE, true)
        engine.setMuted(MuteReason.INTERRUPTION, true)
        startStream()

        verify { players[0].setMuted(MuteReason.VOICE, true) }
        verify { players[0].setMuted(MuteReason.INTERRUPTION, true) }

        engine.setMuted(MuteReason.VOICE, false)
        verify { players[0].setMuted(MuteReason.VOICE, false) }
    }

    @Test
    fun `the player mute is read from the host for each new player`() {
        playerMuted = true
        startStream()

        verify { players[0].setMuted(MuteReason.PLAYER, true) }
    }

    @Test
    fun `a sync mute is not carried to the next player`() {
        startStream()
        engine.onSyncMuteChanged(true)
        dispatcher.scheduler.advanceUntilIdle()
        verify { players[0].setMuted(MuteReason.SYNC, true) }

        engine.releasePlayer()
        engine.endStream()
        startStream()

        verify(exactly = 0) { players[1].setMuted(MuteReason.SYNC, true) }
    }

    @Test
    fun `server stop clears the buffer and pauses, pause keeps it`() {
        startStream()

        engine.applyServerPlayback(PlaybackStateType.STOPPED)
        verifyOrder {
            players[0].clearBuffer()
            players[0].pause()
        }

        engine.applyServerPlayback(PlaybackStateType.PAUSED)
        engine.applyServerPlayback(PlaybackStateType.PLAYING)
        verify(exactly = 1) { players[0].clearBuffer() }
        verify { players[0].resume() }
    }

    @Test
    fun `releasing the player tells the host`() {
        startStream()
        engine.releasePlayer()

        verifyOrder {
            players[0].stop()
            players[0].release()
        }
        assertNull(engine.player)
        assertEquals("player=null", events.last())
    }

    // ========== Format change on an active stream ==========

    /** In-place stream/start to 24-bit PCM, then run what is due now. */
    private fun changeFormat() {
        engine.onStreamStart("pcm", 48_000, 2, 24, null)
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `a format change keeps the old output playing out beside the new one`() {
        startStream()
        changeFormat()

        assertEquals(2, players.size)
        assertSame("the new format goes to a new player at once", players[1], engine.player)
        verify(exactly = 0) { players[0].release() }

        // Released only once it has played out what it holds
        dispatcher.scheduler.advanceTimeBy(1_000)
        dispatcher.scheduler.runCurrent()
        verify(exactly = 0) { players[0].release() }
        every { players[0].hasPlayedOut() } returns true
        dispatcher.scheduler.advanceTimeBy(1_000)
        dispatcher.scheduler.runCurrent()
        verify(exactly = 1) { players[0].release() }
    }

    @Test
    fun `a clear while the old output plays out releases it with the rest`() {
        startStream()
        changeFormat()

        engine.onStreamClear()
        dispatcher.scheduler.runCurrent()

        verify { players[0].release() }
        verify { players[1].clearBuffer() }
    }

    @Test
    fun `mutes reach the output still playing out`() {
        startStream()
        changeFormat()

        engine.setMuted(MuteReason.VOICE, true)

        verify { players[0].setMuted(MuteReason.VOICE, true) }
        verify { players[1].setMuted(MuteReason.VOICE, true) }
    }

    @Test
    fun `audio the old decoder still holds goes to the old output`() {
        every { AudioDecoderFactory.create(any<String>()) } returns decoderHolding(5_000L, 16)
        startStream()
        changeFormat()

        verify { players[0].queueChunk(5_000L, any(), any()) }
        verify(exactly = 0) { players[1].queueChunk(5_000L, any(), any()) }
    }

    @Test
    fun `the host keeps the output being heard until the new one starts`() {
        startStream()
        changeFormat()
        assertTrue("the new output is not the host's while it waits: $events", "player=1" !in events)

        val callback = slot<SyncAudioPlayerCallback>()
        verify { players[1].setStateCallback(capture(callback)) }
        callback.captured.onPlaybackStateChanged(PlaybackState.PLAYING)
        dispatcher.scheduler.runCurrent()
        assertEquals("player=1", events.last())
    }

    @Test
    fun `a format change still pending when a new stream starts does not replace its player`() {
        startStream()
        engine.onStreamStart("pcm", 48_000, 2, 24, null)  // in place, pending on the worker
        engine.onStreamEnd()
        engine.onStreamStart("pcm", 48_000, 2, 32, null)  // a new stream
        dispatcher.scheduler.runCurrent()

        assertEquals(2, players.size)
        assertTrue(engine.player!!.matchesFormat(48_000, 2, 32))
    }

    @Test
    fun `a format change still pending when the connection goes creates no player`() {
        startStream()
        engine.onStreamStart("pcm", 48_000, 2, 24, null)
        engine.releasePlayer()
        engine.endStream()
        dispatcher.scheduler.runCurrent()

        assertEquals(1, players.size)
        assertNull(engine.player)
    }

    @Test
    fun `a format change followed by a clear still switches the output`() {
        startStream()
        engine.onStreamStart("pcm", 48_000, 2, 24, null)
        engine.onStreamClear()
        dispatcher.scheduler.runCurrent()

        assertEquals(2, players.size)
        assertSame(players[1], engine.player)
    }

    private fun decoderHolding(timestampUs: Long, bytes: Int): AudioDecoder =
        mockk<AudioDecoder>(relaxed = true) {
            every { drain() } returns listOf(DecodedAudio(timestampUs, ByteArray(bytes)))
        }

    @Test
    fun `audio the old decoder holds is not kept past a clear that follows the format change`() {
        every { AudioDecoderFactory.create(any<String>()) } returns decoderHolding(5_000L, 16)
        startStream()
        val stillCurrent = mutableListOf<() -> Boolean>()
        every { players[0].queueChunk(5_000L, any(), any()) } answers { stillCurrent += thirdArg<() -> Boolean>() }

        engine.onStreamStart("pcm", 48_000, 2, 24, null)
        engine.onStreamClear()
        dispatcher.scheduler.runCurrent()

        assertTrue(stillCurrent.none { it() })
    }

    @Test
    fun `audio the old decoder holds waits for room in the old output`() {
        every { AudioDecoderFactory.create(any<String>()) } returns decoderHolding(5_000L, 4096 * 4)
        startStream()
        every { players[0].hasRoomFor(any()) } returns false

        changeFormat()
        verify(exactly = 0) { players[0].queueChunk(5_000L, any(), any()) }

        every { players[0].hasRoomFor(any()) } returns true
        dispatcher.scheduler.advanceTimeBy(100)
        dispatcher.scheduler.runCurrent()
        verify(exactly = 1) { players[0].queueChunk(5_000L, any(), any()) }
    }
}
