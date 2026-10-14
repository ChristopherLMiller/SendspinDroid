package com.sendspindroid.playback

import android.util.Log
import com.sendspindroid.model.PlaybackStateType
import com.sendspindroid.sendspin.PlaybackState as SyncPlaybackState
import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.SyncAudioPlayer
import com.sendspindroid.sendspin.SyncAudioPlayerCallback
import com.sendspindroid.sendspin.decoder.AudioDecoder
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import com.sendspindroid.sendspin.protocol.StreamConfig
import com.sendspindroid.sendspin.SendspinTimeFilter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The audio pipeline between a [SendSpin] client and the output: one decode
 * worker, the [SyncAudioPlayer] and its format changes, and the local mutes.
 *
 * The host passes on the client's stream callbacks and its connection
 * events, and keeps the rest (media session, notification, audio focus,
 * device volume, connections). The `on*` stream methods are called on the
 * client's thread; every other method, and every [Listener] call, is on the
 * main thread.
 */
class SendSpinEngine(
    private val listener: Listener,
    /** Cap the decoded queue at about ten seconds. Read when a player is created. */
    private val lowMemoryMode: () -> Boolean,
    /** The saved `muted` state. Read when a player is created. */
    private val playerMuted: () -> Boolean,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    // Decode worker: single-thread-equivalent coroutine that owns the
    // decoder for its lifetime. See
    // docs/superpowers/specs/2026-04-23-codec-safe-decoder-redesign-design.md
    // for the H-4 + M-8 rationale.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val newPlayer: (SendspinTimeFilter, sampleRate: Int, channels: Int, bitDepth: Int, maxQueueSamples: Long) -> SyncAudioPlayer =
        { timeFilter, sampleRate, channels, bitDepth, maxQueueSamples ->
            SyncAudioPlayer(
                timeFilter = timeFilter,
                sampleRate = sampleRate,
                channels = channels,
                bitDepth = bitDepth,
                maxQueueSamples = maxQueueSamples,
            )
        },
) {

    interface Listener {
        /**
         * A stream started and the client's clock is available. Called
         * before its player is set up, for the host to take what playback
         * needs (locks, foreground).
         */
        fun onStreamStarted()

        /** The player was created or replaced, or released ([player] null). */
        fun onPlayerChanged(player: SyncAudioPlayer?)

        /** The player's own playback state changed. */
        fun onPlayerStateChanged(state: SyncPlaybackState)
    }

    /** The client being played from, whose clock the player follows. */
    @Volatile
    var client: SendSpin? = null

    /** The output, null between streams of different formats and after a disconnect. */
    @Volatile
    var player: SyncAudioPlayer? = null
        private set

    /** Codec of the last stream started, for stats. */
    var codec: String = "pcm"
        private set

    // Configuration of the active player stream, null when none is active.
    // Tells a stream/start that begins a stream from one that reconfigures
    // the stream already playing, which must keep its buffered audio.
    @Volatile
    var activeStream: StreamConfig? = null
        private set

    // Mutes kept across players. PLAYER is read from the host instead, and
    // SYNC belongs to the player whose sync it reports.
    private val heldMutes = mutableSetOf<SyncAudioPlayer.MuteReason>()

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

    /**
     * Tasks sent through [decodeChannel] to the single-owner decode worker.
     *
     * All decoder lifecycle transitions (StartStream, Flush, Release) travel on
     * the same channel as Chunk so they respect FIFO ordering with pending
     * decodes. See
     * docs/superpowers/specs/2026-04-23-codec-safe-decoder-redesign-design.md
     * for the H-4 + M-8 rationale.
     */
    private sealed class DecodeTask {
        class Chunk(
            val serverTimeMicros: Long,
            val audioData: ByteArray,
            val generation: Int,
        ) : DecodeTask()
        class StartStream(
            val codec: String,
            val sampleRate: Int,
            val channels: Int,
            val bitDepth: Int,
            val codecHeader: ByteArray?,
            // True when this reconfigures an active stream: audio queued
            // before it is in the previous format and must still play.
            val keepBuffered: Boolean,
        ) : DecodeTask()
        object Flush : DecodeTask()
        object Release : DecodeTask()
    }

    // Capacity = 1000 ~= 20 seconds at the expected 50 chunks/sec. Sized to
    // absorb cold-start + pre-buffered bursts (~100 entries) with 10x headroom.
    private val decodeChannel = Channel<DecodeTask>(capacity = 1000)

    private val decodeJob: Job

    // Owned exclusively by the decode worker coroutine (serialized on
    // decodeDispatcher). Single-writer invariant: all mutations happen
    // inside handleDecodeStartStream / handleDecodeRelease, both of which
    // run on decodeDispatcher. No volatile / lock needed because no other
    // thread reads or writes this field.
    private var audioDecoder: AudioDecoder? = null

    // Guards against race condition: onAudioChunk runs on WebSocket thread but
    // decoder creation is posted to the decode worker. Chunks arriving before
    // the new decoder is ready would hit the old (released) decoder and throw.
    @Volatile
    private var decoderReady = false

    // Identifies the stream a chunk belongs to. onAudioChunk snapshots
    // it on WS-IO at enqueue time and the decode worker re-checks it before
    // decoding, so chunks from a superseded stream are discarded no matter how
    // long they sat on the main dispatch queue or in decodeChannel. Ordering
    // and drains can both be defeated by in-flight work; a tag cannot.
    // Single-writer: only the WS-IO stream callbacks mutate it.
    @Volatile
    private var decodeGeneration = 0

    init {
        decodeJob = scope.launch(decodeDispatcher) {
            try {
                for (task in decodeChannel) {
                    try {
                        when (task) {
                            is DecodeTask.Chunk -> handleDecodeChunk(task)
                            is DecodeTask.StartStream -> handleDecodeStartStream(task)
                            DecodeTask.Flush -> handleDecodeFlush()
                            DecodeTask.Release -> handleDecodeRelease()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Decode worker error on task ${task::class.simpleName}", e)
                    }
                }
            } finally {
                // release()'s Release is lost when the channel is full, and a
                // cancelled worker never reaches it. Release on the way out.
                handleDecodeRelease()
            }
        }
    }

    // ========== Stream callbacks, on the client's thread ==========

    fun onStreamStart(codec: String, sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: ByteArray?) {
        val config = StreamConfig(codec, sampleRate, channels, bitDepth, codecHeader)
        val action = StreamStartAction.of(activeStream, config)
        activeStream = config
        if (action == StreamStartAction.UNCHANGED) {
            Log.d(TAG, "stream/start repeats the active format - nothing to do")
            return
        }
        // A stream/start on an active stream reconfigures it: "Clients
        // MUST keep buffered chunks and decode each chunk in the format
        // that was in effect when it was received." FIFO ordering on
        // decodeChannel gives the second half; not discarding anything
        // gives the first.
        val keepBuffered = action == StreamStartAction.FORMAT_CHANGE

        // A new stream invalidates every chunk left from the last one.
        // Bumping here -- on WS-IO, before anything new is queued -- means
        // chunks already in flight toward decodeChannel carry the old tag
        // and are dropped by the worker (#114).
        if (!keepBuffered) decodeGeneration++

        // Post decoder lifecycle to the single-owner decode worker via the
        // channel. FIFO ordering between StartStream and any subsequent
        // Chunk tasks ensures the new decoder is in place before its
        // chunks are decoded. decoderReady is set optimistically so any
        // chunk arriving during reconfiguration is still enqueued; it
        // will decode with the new decoder once the worker drains the
        // StartStream task ahead of it.
        decoderReady = true
        scope.launch {
            decodeChannel.send(
                DecodeTask.StartStream(codec, sampleRate, channels, bitDepth, codecHeader, keepBuffered)
            )
        }

        scope.launch {
            this@SendSpinEngine.codec = codec

            if (client?.getTimeFilter() == null) {
                Log.e(TAG, "Cannot start audio: time filter not available")
                return@launch
            }

            listener.onStreamStarted()

            // The decode worker switches the output of an active stream,
            // once the audio buffered in the old format has played.
            if (keepBuffered) return@launch

            // Reuse existing player if format matches (DAC timestamps stay warm)
            val existingPlayer = player
            if (existingPlayer != null && existingPlayer.matchesFormat(sampleRate, channels, bitDepth)) {
                Log.i(TAG, "Reusing existing SyncAudioPlayer - DAC already warm")
                existingPlayer.clearBuffer()
            } else {
                // Format changed or no existing player - create new one
                existingPlayer?.release()
                createPlayer(sampleRate, channels, bitDepth)
            }
        }
    }

    fun onAudioChunk(serverTimeMicros: Long, audioData: ByteArray) {
        // Fast-path gate: once onStreamStart has been observed but before
        // any audio has been accepted, decoderReady stays false so we
        // don't pile chunks into the channel for a decoder that will
        // never exist. Normal steady state sees decoderReady = true.
        if (!decoderReady) return

        // Hand the chunk to the single-owner decode worker via the
        // channel. Wrapping send() in launch lets this callback return
        // immediately on WS-IO (fixing H-4); the launched coroutine
        // suspends on the channel if it is full. We deliberately do
        // NOT use trySend + drop: dropping compressed chunks mid-frame
        // corrupts codec state (this is exactly the regression PR #142
        // introduced via drop-oldest). Suspend-on-full is the
        // correctness property; see design doc H-4 / M-8 rationale.

        // Snapshot on WS-IO so the tag reflects the stream this chunk
        // actually arrived on, not whatever is current by the time the
        // launched coroutine runs on the main dispatcher.
        val generation = decodeGeneration
        scope.launch {
            decodeChannel.send(DecodeTask.Chunk(serverTimeMicros, audioData, generation))
        }
    }

    fun onStreamClear() {
        Log.i(TAG, "[cmd-trace] T2 onStreamClear ts=${System.nanoTime() / 1_000_000} thread=${Thread.currentThread().name}")
        // Decoder flush goes through the channel so it is ordered with
        // any in-flight Chunk tasks: every chunk enqueued before the
        // stream/clear message decodes with the pre-flush decoder
        // state; every chunk enqueued after decodes with the flushed
        // decoder. Preserves the FIFO guarantee from the design.
        //
        // "clients MUST clear all buffered audio chunks and continue with
        // chunks received after this message": the bump discards chunks
        // still waiting to be decoded, which can be seconds of audio while
        // the worker is holding a format switch back.
        decodeGeneration++
        scope.launch { decodeChannel.send(DecodeTask.Flush) }

        scope.launch {
            Log.i(TAG, "[cmd-trace] T3 onStreamClear.post ts=${System.nanoTime() / 1_000_000} thread=${Thread.currentThread().name}")
            Log.d(TAG, "Stream clear - flushing audio buffer")
            player?.clearBuffer()
        }
    }

    fun onStreamEnd() {
        Log.i(TAG, "[cmd-trace] T2 onStreamEnd ts=${System.nanoTime() / 1_000_000} thread=${Thread.currentThread().name}")

        // Invalidate the ending stream's pre-buffered chunks (issue #114).
        decodeGeneration++
        activeStream = null

        scope.launch {
            Log.i(TAG, "[cmd-trace] T3 onStreamEnd.post ts=${System.nanoTime() / 1_000_000} thread=${Thread.currentThread().name}")
            Log.i(TAG, "Stream end - server terminated playback")
            // Enter idle mode: keep AudioTrack alive and writing silence
            // so DAC timestamps stay warm for the next stream start
            player?.enterIdle()
        }
    }

    /** The client reports `state="error"` while out of sync, and mutes until it is back. */
    fun onSyncMuteChanged(muted: Boolean) {
        scope.launch {
            player?.setMuted(SyncAudioPlayer.MuteReason.SYNC, muted)
        }
    }

    /** The network changed under the client: re-anchor the output. */
    fun onNetworkChanged() {
        scope.launch {
            Log.i(TAG, "Network changed - triggering audio player reanchor")
            player?.clearBuffer()
        }
    }

    // ========== Host calls, on the main thread ==========

    /** The client left Ready. No stream survives the connection it was started on. */
    fun endStream() {
        activeStream = null
    }

    /** The connection is gone: stop and release the output. */
    fun releasePlayer() {
        player?.stop()
        player?.release()
        player = null
        listener.onPlayerChanged(null)
    }

    /**
     * The server's playback state, per the spec: stopped resets the position,
     * so the buffer goes; paused keeps it for later; playing resumes.
     */
    fun applyServerPlayback(state: PlaybackStateType) {
        val p = player
        when (state) {
            PlaybackStateType.STOPPED -> {
                p?.clearBuffer()
                p?.pause()
            }
            PlaybackStateType.PAUSED -> p?.pause()
            PlaybackStateType.PLAYING -> p?.resume()
            else -> {}
        }
    }

    /**
     * Mute or un-mute the output for one [reason]. INTERRUPTION and VOICE are
     * kept and applied to every later player; PLAYER is the host's to
     * persist, and is read back from [playerMuted] for a new player.
     */
    fun setMuted(reason: SyncAudioPlayer.MuteReason, muted: Boolean) {
        if (reason == SyncAudioPlayer.MuteReason.INTERRUPTION || reason == SyncAudioPlayer.MuteReason.VOICE) {
            if (muted) heldMutes += reason else heldMutes -= reason
        }
        player?.setMuted(reason, muted)
    }

    /**
     * Stop the decode worker and release the output. The engine cannot be
     * used afterwards.
     */
    fun release() {
        // Send a final Release through the channel so it runs after any
        // pending decode tasks, then close the channel and wait up to 500 ms
        // for the worker to drain and exit. trySend (not send) because
        // release is non-suspending -- if the channel is somehow full we
        // don't want to block teardown waiting for the worker to make
        // progress; handleDecodeRelease still runs if trySend succeeds, or
        // the worker will release the decoder as it exits when the channel
        // is closed. Bumping the generation first ends a wait for queue room
        // and drops the backlog undecoded, so the worker can exit in time.
        decodeGeneration++
        decodeChannel.trySend(DecodeTask.Release)
        decodeChannel.close()
        runBlocking {
            withTimeoutOrNull(500) { decodeJob.join() }
        }
        scope.cancel()

        // audioDecoder is released by handleDecodeRelease on the decode
        // worker (invariant: only that coroutine mutates the field).
        player?.release()
        player = null
    }

    // ========== Decode worker ==========

    /**
     * Decode a single audio chunk. Runs on [decodeDispatcher]; [audioDecoder]
     * is read from the single-owner thread so no TOCTOU local-ref capture is
     * needed.
     */
    private suspend fun handleDecodeChunk(t: DecodeTask.Chunk) {
        // Drop chunks belonging to a stream that has since ended or been
        // replaced. Popping and discarding costs microseconds, so a ~30-second
        // look-ahead backlog clears near-instantly instead of grinding through
        // player.queueChunk back-pressure at real-time rate -- that
        // stall is what delayed decoder reconfiguration in issue #114.
        if (t.generation != decodeGeneration) return

        // No decoder means the stream's codec could not be set up: drop the
        // chunk. PCM has a decoder of its own, so nothing passes through raw.
        val decoder = audioDecoder ?: return
        val decoded = try {
            decoder.decode(t.audioData, t.serverTimeMicros)
        } catch (e: Exception) {
            Log.e(TAG, "Decode error, dropping chunk", e)
            return
        }
        val out = player ?: return
        // Checked again, by the player under the lock its clear takes: a
        // stream/clear or stream/end that landed while this chunk was
        // decoding has cleared the player or is about to, and a chunk queued
        // after that would sit at the head of the queue with the old stream's
        // timestamp. The new stream's audio is then discarded as overlap
        // until it catches up - seconds of silence after a skip. The
        // generation is bumped before the clear is posted, so a chunk that
        // passes here is queued before the clear and removed by it.
        //
        // Stamped by the decoder, not with this chunk's time: what comes out
        // can be the previous chunk's audio.
        for (audio in decoded) {
            // The server may run minutes ahead (buffer_capacity counts encoded
            // bytes), more than the capped decoded queue holds. Wait for room
            // so the backlog stays encoded in decodeChannel; a stream/clear or
            // stream/end bumps decodeGeneration and ends the wait.
            val samples = (audio.pcm.size / out.bytesPerFrame).toLong()
            while (t.generation == decodeGeneration && !out.hasRoomFor(samples)) {
                delay(FORMAT_SWITCH_POLL_MS)
            }
            out.queueChunk(audio.timestampUs, audio.pcm) { t.generation == decodeGeneration }
        }
    }

    /**
     * Release any prior decoder and create+configure a new one for the new
     * stream. Runs on [decodeDispatcher] so we're the single owner of
     * [audioDecoder]. If the decoder cannot be created or configured there is
     * no decoder and the stream's chunks are dropped: silence, never the
     * compressed bytes played as PCM.
     */
    private suspend fun handleDecodeStartStream(t: DecodeTask.StartStream) {
        Log.d(
            TAG,
            "Stream started: codec=${t.codec}, rate=${t.sampleRate}, " +
                "channels=${t.channels}, bits=${t.bitDepth}, " +
                "header=${t.codecHeader?.size ?: 0} bytes"
        )

        // Release existing decoder and create new one for this stream.
        audioDecoder?.release()
        audioDecoder = null
        try {
            val decoder = AudioDecoderFactory.create(t.codec)
            decoder.configure(t.sampleRate, t.channels, t.bitDepth, t.codecHeader)
            audioDecoder = decoder
            Log.i(TAG, "Audio decoder created: ${t.codec}")
            decoderReady = true
        } catch (e: Exception) {
            Log.e(TAG, "No decoder for ${t.codec}; dropping this stream's audio", e)
            // Subsequent chunks are rejected at the WS-IO fast-path gate.
            decoderReady = false
        }

        if (t.keepBuffered) switchOutputFormat(t)
    }

    /**
     * Format change on an active stream. Everything already queued in
     * [player] was decoded in the previous format and must still be
     * played, so when the PCM format differs the player is only replaced once
     * its queue has drained. Suspending here holds back the chunks behind this
     * task, which are in the new format. A stream/clear or stream/end bumps
     * [decodeGeneration] and ends the wait early.
     */
    private suspend fun switchOutputFormat(t: DecodeTask.StartStream) {
        val old = player
        if (old != null && old.matchesFormat(t.sampleRate, t.channels, t.bitDepth)) return

        Log.i(TAG, "Output format change on active stream - playing out ${old?.getBufferedDurationMs() ?: 0}ms of buffered audio first")
        val generation = decodeGeneration
        while (old != null && old === player && generation == decodeGeneration &&
            old.getBufferedDurationMs() > 0
        ) {
            delay(FORMAT_SWITCH_POLL_MS)
        }

        withContext(mainDispatcher) {
            // Replaced or torn down while we waited (disconnect, new stream).
            if (player !== old) return@withContext
            old?.release()
            createPlayer(t.sampleRate, t.channels, t.bitDepth)
        }
    }

    private fun handleDecodeFlush() {
        audioDecoder?.flush()
    }

    private fun handleDecodeRelease() {
        audioDecoder?.release()
        audioDecoder = null
        decoderReady = false
    }

    /** Creates and starts a [SyncAudioPlayer] for the given PCM format. Main thread. */
    private fun createPlayer(sampleRate: Int, channels: Int, bitDepth: Int) {
        val timeFilter = client?.getTimeFilter()
        if (timeFilter == null) {
            Log.e(TAG, "Cannot start audio: time filter not available")
            return
        }
        // In low memory mode, cap the chunk queue to ~10 seconds of audio
        val maxSamples = if (lowMemoryMode()) {
            sampleRate.toLong() * SendSpinProtocol.Buffer.DURATION_LOW_MEM_SEC
        } else {
            0L  // Unlimited
        }
        val created = newPlayer(timeFilter, sampleRate, channels, bitDepth, maxSamples).apply {
            setStateCallback(object : SyncAudioPlayerCallback {
                override fun onPlaybackStateChanged(state: SyncPlaybackState) {
                    scope.launch {
                        Log.d(TAG, "SyncAudioPlayer state changed: $state")
                        listener.onPlayerStateChanged(state)
                    }
                }
            })
            // From the host, not the session state: that is reset on disconnect.
            setMuted(SyncAudioPlayer.MuteReason.PLAYER, playerMuted())
            setMuted(SyncAudioPlayer.MuteReason.INTERRUPTION, SyncAudioPlayer.MuteReason.INTERRUPTION in heldMutes)
            setMuted(SyncAudioPlayer.MuteReason.VOICE, SyncAudioPlayer.MuteReason.VOICE in heldMutes)
            initialize()
            start()
        }
        player = created
        listener.onPlayerChanged(created)
        Log.i(TAG, "SyncAudioPlayer created: ${sampleRate}Hz, ${channels}ch, ${bitDepth}bit")
    }

    private companion object {
        const val TAG = "SendSpinEngine"

        // Poll interval while an output format switch waits for the audio
        // buffered in the previous format to play out.
        const val FORMAT_SWITCH_POLL_MS = 20L
    }
}
