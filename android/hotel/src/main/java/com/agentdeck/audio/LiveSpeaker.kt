package com.agentdeck.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** PCM24k playback. Bounded 40ms frames retain their source without copying PCM. */
class LiveSpeaker(private val failed: () -> Unit = {}) {
    private class Frame(val bytes: ByteArray, val offset: Int, val length: Int)
    private val queue = ArrayBlockingQueue<Frame>(24)
    private val lock = Any()
    @Volatile private var running = false
    private var closed = false
    private var inputFinished = false
    private var submittedFrames = 0L
    private var worker: Thread? = null
    @Volatile private var track: AudioTrack? = null
    private val envelope = PcmEnvelope()

    fun currentEnergy(): Float {
        val player = track ?: return 0f
        if (!running) return 0f
        return try {
            envelope.currentEnergy(player.playbackHeadPosition.toLong() and 0xffffffffL, SystemClock.elapsedRealtimeNanos())
        } catch (_: IllegalStateException) { 0f }
    }

    fun start() = synchronized(lock) {
        if (running || closed) return@synchronized
        submittedFrames = 0
        envelope.reset()
        val minimum = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0)
        val player = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum, 9600)).setTransferMode(AudioTrack.MODE_STREAM).build()
        try {
            // API 26–30 have no threshold API: the effective buffer also caps startup.
            // Five full packets must start playback: a short final sixth packet can exhaust
            // sender credit before EOF. The controlled producer sends full chunks except its tail.
            check(player.setBufferSizeInFrames(960) > 0) { "Unsupported playback buffer" }
            if (Build.VERSION.SDK_INT >= 31) {
                check(player.setStartThresholdInFrames(1) == 1) { "Unsupported playback threshold" }
                check(player.startThresholdInFrames == 1) { "Unsupported playback threshold" }
            } else check(player.bufferSizeInFrames in 1..4800) { "Unsupported playback buffer" }
            player.play()
        } catch (e: Exception) { player.release(); throw e }
        track = player
        running = true
        worker = Thread({
            var failure = false
            try {
                while (running && track === player) {
                    val frame = queue.poll(20, TimeUnit.MILLISECONDS)
                    if (frame != null) {
                        if (!write(player, frame.bytes, frame.offset, frame.length, speech = true)) break
                    } else if (synchronized(lock) { inputFinished && queue.isEmpty() }) {
                        // A finite tail can underrun even after earlier speech played. Filling the
                        // entire effective threshold with zeros starts that tail on legacy devices.
                        // These are local silence, not provider audio, speech samples or credit.
                        var remaining = synchronized(lock) {
                            if (submittedFrames == 0L) 0 else {
                                val buffer = player.bufferSizeInFrames
                                val threshold = if (Build.VERSION.SDK_INT >= 31)
                                    minOf(buffer, player.startThresholdInFrames) else buffer
                                check(threshold in 1..4800) { "Unsupported playback threshold" }
                                threshold
                            }
                        }
                        val silence = ByteArray(minOf(remaining, 960) * 2)
                        while (remaining > 0) {
                            val frames = minOf(remaining, 960)
                            if (!write(player, silence, 0, frames * 2, speech = false)) break
                            remaining -= frames
                        }
                        break
                    }
                }
            } catch (_: InterruptedException) { }
            catch (_: Exception) { failure = running }
            if (failure) failed()
        }, "ManzanillaSpeaker").apply { isDaemon = true; start() }
    }

    private fun write(player: AudioTrack, bytes: ByteArray, start: Int, length: Int, speech: Boolean): Boolean {
        var offset = start
        val end = start + length
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (offset < end) {
            val written = synchronized(lock) {
                if (!running || track !== player) return false
                // Nonblocking writes and flush share the fence: no write may land after flush.
                player.write(bytes, offset, end - offset, AudioTrack.WRITE_NON_BLOCKING).also {
                    check(it >= 0 && it % 2 == 0) { "Playback write failed" }
                    if (speech && it > 0) envelope.written(bytes, offset, it)
                }
            }
            offset += written
            if (written == 0) {
                check(SystemClock.elapsedRealtime() < deadline) { "Playback stalled" }
                Thread.sleep(10)
            }
        }
        return true
    }

    /** Seal once, without blocking capture/control. The worker primes any finite tail with silence. */
    fun finishInput(): Boolean = synchronized(lock) {
        if (!running || inputFinished) return@synchronized false
        inputFinished = true
        true
    }

    fun playedSamples(): Long = synchronized(lock) {
        if (!running) return@synchronized 0L
        try { minOf(submittedFrames, track?.playbackHeadPosition?.toLong()?.and(0xffffffffL) ?: 0L) }
        catch (_: IllegalStateException) { 0L }
    }
    /** Includes queued and native-buffered frames, not just already-rendered samples. */
    fun submittedSamples(): Long = synchronized(lock) { submittedFrames }

    /** Nonblocking managed input: sender credit limits outstanding packets to six. */
    fun offer(pcm: ByteArray, offset: Int, length: Int): Boolean = synchronized(lock) {
        require(offset >= 0 && length in 2..1920 && length % 2 == 0 && offset <= pcm.size - length)
        if (!running || inputFinished || !queue.offer(Frame(pcm, offset, length))) return@synchronized false
        submittedFrames += length / 2
        true
    }

    /** Called on the TTS WebSocket reader, NEVER under ASR/microphone locks.
     * Blocking this reader applies TCP backpressure instead of an unbounded inbound coroutine queue.
     * A stopped output wakes within one polling interval; a stalled renderer fails visibly.
     */
    fun append(pcm: ByteArray, active: () -> Boolean = { true }): Boolean {
        require(pcm.isNotEmpty() && pcm.size % 2 == 0)
        var offset = 0
        while (offset < pcm.size) {
            val length = minOf(1920, pcm.size - offset)
            val deadline = SystemClock.elapsedRealtime() + 10000
            while (true) {
                if (!running || !active() || synchronized(lock) { inputFinished }) return false
                if (offer(pcm, offset, length)) break
                check(SystemClock.elapsedRealtime() < deadline) { "Playback stalled" }
                Thread.sleep(10)
            }
            offset += length
        }
        return true
    }

    /** Immediate output fence/flush, without waiting for a writer or provider shutdown. */
    fun flush() {
        synchronized(lock) {
            running = false
            closed = true
            queue.clear()
            try { track?.pause(); track?.flush() } catch (_: IllegalStateException) { }
        }
        worker?.interrupt()
    }

    /** Release on a teardown worker, outside microphone/recognition locks. */
    @Synchronized fun stop() {
        flush()
        val thread = worker
        if (thread !== Thread.currentThread()) thread?.join()
        val player = synchronized(lock) { track.also { track = null; worker = null } }
        try { player?.stop() } catch (_: IllegalStateException) { }
        try { player?.release() } finally { envelope.reset() }
    }
}
