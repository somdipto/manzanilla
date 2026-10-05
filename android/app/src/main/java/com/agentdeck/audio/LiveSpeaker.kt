package com.agentdeck.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Ordered, bounded PCM playback. No API credential or internet connection lives here. */
class LiveSpeaker {
    private val queue = ArrayBlockingQueue<ByteArray>(16)
    @Volatile private var running = false
    private var worker: Thread? = null
    @Volatile private var track: AudioTrack? = null
    private val envelope = PcmEnvelope()

    /** Actual playback energy, safe for a 20 Hz UI update without a new audio thread. */
    @Synchronized fun currentEnergy(): Float {
        val player = track ?: return 0f
        if (!running) return 0f
        return try {
            envelope.currentEnergy(player.playbackHeadPosition.toLong() and 0xffffffffL, SystemClock.elapsedRealtimeNanos())
        } catch (_: IllegalStateException) {
            envelope.reset()
            0f
        }
    }

    @Synchronized fun start() {
        if (running) return
        queue.clear()
        envelope.reset()
        val minimum = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val player = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum, 9600)).setTransferMode(AudioTrack.MODE_STREAM).build()
        track = player
        running = true
        player.play()
        worker = Thread({
            try {
                while (running && track === player) {
                    val data = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    var offset = 0
                    while (running && track === player && offset < data.size) {
                        // 24 kHz mono PCM16: at most 60 ms per meter sample/write.
                        val written = player.write(data, offset, minOf(data.size - offset, 2880), AudioTrack.WRITE_BLOCKING)
                        if (written <= 0) break
                        if (running && track === player) envelope.written(data, offset, written)
                        offset += written
                    }
                }
            } catch (_: InterruptedException) { }
            catch (_: IllegalStateException) { }
        }, "OrangeLiveSpeaker").apply { isDaemon = true; start() }
    }

    fun append(pcm: ByteArray) {
        if (!running || pcm.size % 2 != 0 || pcm.size > 192000) return
        if (!queue.offer(pcm)) {
            queue.clear() // Avoid seconds of stale speech after interruption/network stalls.
            queue.offer(pcm)
        }
    }

    @Synchronized fun stop() {
        running = false
        val player = track
        track = null
        envelope.reset()
        queue.clear()
        worker?.interrupt()
        try { player?.pause(); player?.flush() } catch (_: IllegalStateException) { }
        try { worker?.join(250) } catch (_: InterruptedException) { }
        player?.release()
        worker = null
    }
}
