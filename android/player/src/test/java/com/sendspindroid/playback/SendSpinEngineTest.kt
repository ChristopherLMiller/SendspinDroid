package com.sendspindroid.playback

import com.sendspindroid.model.PlaybackStateType
import com.sendspindroid.sendspin.PlaybackState
import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.SyncAudioPlayer
import com.sendspindroid.sendspin.SyncAudioPlayer.MuteReason
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    fun `a voice mute set between streams applies to the next player`() {
        engine.setMuted(MuteReason.VOICE, true)
        startStream()

        verify { players[0].setMuted(MuteReason.VOICE, true) }

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
}
