package com.agentdeck.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** PCM24k playback: at most one second queued, no silently discarded/reordered speech. */
class LiveSpeaker(private val failed: () -> Unit = {}) {
    private val queue = ArrayBlockingQueue<ByteArray>(16)
    private val lock = Any()
    @Volatile private var running = false
    private var closed = false
    private var queuedBytes = 0
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
        queue.clear()
        queuedBytes = 0
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
        try { player.play() } catch (e: Exception) { player.release(); throw e }
        track = player
        running = true
        worker = Thread({
            var failure = false
            try {
                while (running && track === player) {
                    val data = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    synchronized(lock) { queuedBytes -= data.size }
                    var offset = 0
                    while (running && track === player && offset < data.size) {
                        val written = player.write(data, offset, minOf(data.size - offset, 2880), AudioTrack.WRITE_BLOCKING)
                        if (written <= 0) { if (running) failure = true; break }
                        if (running && track === player) envelope.written(data, offset, written)
                        offset += written
                    }
                    if (failure) break
                }
            } catch (_: InterruptedException) { }
            catch (_: IllegalStateException) { failure = running }
            if (failure) failed()
        }, "ManzanillaSpeaker").apply { isDaemon = true; start() }
    }

    fun playedSamples(): Long = try { track?.playbackHeadPosition?.toLong()?.and(0xffffffffL) ?: 0L } catch (_: Exception) { 0L }

    /** Includes queued and native-buffered frames; unlike playback head, this is not heard audio. */
    fun submittedSamples(): Long = synchronized(lock) { submittedFrames }

    fun append(pcm: ByteArray): Boolean = synchronized(lock) {
        if (!running || pcm.isEmpty() || pcm.size % 2 != 0 || pcm.size > 48000 || queuedBytes + pcm.size > 48000) return@synchronized false
        if (!queue.offer(pcm)) return@synchronized false
        queuedBytes += pcm.size
        submittedFrames += pcm.size / 2
        true
    }

    /** May block until an in-flight native write ends. Call from the engine's teardown worker. */
    fun stop() {
        val player: AudioTrack?
        val thread: Thread?
        synchronized(lock) {
            running = false
            closed = true
            player = track
            track = null
            thread = worker
            queue.clear()
            queuedBytes = 0
        }
        thread?.interrupt()
        try { player?.pause(); player?.flush(); player?.stop() } catch (_: IllegalStateException) { }
        if (thread !== Thread.currentThread()) thread?.join()
        try { player?.release() } finally { envelope.reset() }
        synchronized(lock) { if (worker === thread) worker = null }
    }
}
