package com.agentdeck.hotel

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import java.util.concurrent.atomic.AtomicBoolean

/** Adapted from MicStreamer: 24 kHz PCM16, no local bridge or automatic gain magnification. */
class HotelMic(context: Context, private val send: (ByteArray) -> Boolean, private val failed: () -> Unit) {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    @Volatile private var recorder: AudioRecord? = null
    @SuppressLint("MissingPermission")
    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({
            val previousMode = manager.mode
            var rec: AudioRecord? = null
            try {
                manager.mode = AudioManager.MODE_IN_COMMUNICATION
                val minimum = AudioRecord.getMinBufferSize(24000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 24000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 4096))
                recorder = rec
                check(rec.state == AudioRecord.STATE_INITIALIZED)
                rec.startRecording()
                val buffer = ByteArray(1920) // 40ms
                while (running.get()) {
                    val n = rec.read(buffer, 0, buffer.size)
                    if (n < 0) error("Microphone read failed")
                    if (n > 0 && !send(buffer.copyOf(n))) error("Audio connection stalled")
                }
            } catch (_: Exception) { if (running.get()) failed() }
            finally {
                running.set(false)
                try { rec?.stop() } catch (_: Exception) {}
                rec?.release(); recorder = null
                manager.mode = previousMode
            }
        }, "OrangeHotelMic").apply { isDaemon = true; start() }
    }
    fun stop() {
        running.set(false)
        try { recorder?.stop() } catch (_: Exception) {}
        try { worker?.join(500) } catch (_: InterruptedException) {}
        worker = null
    }
}
