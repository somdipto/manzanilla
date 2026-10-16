package com.agentdeck.hotel

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import com.agentdeck.audio.PcmEnvelope

/** A single-use capture owner. stop() returns only after its recorder/AEC and worker are released. */
class HotelMic(
    context: Context,
    private val send: (ByteArray, Int) -> Boolean,
    private val failed: () -> Unit,
    private val started: () -> Unit = {},
    private val level: (Float) -> Unit = {},
    private val sampleRate: Int = 24000,
    private val echoControl: (EchoControl) -> Unit = {}
) {
    enum class EchoControl(val description: String) {
        UNAVAILABLE("The device does not report an echo-cancellation effect."),
        REPORTED_ENABLED("The device reports echo cancellation enabled."),
        NOT_ENABLED("The device did not enable echo cancellation."),
        SETUP_FAILED("Echo-cancellation setup could not be confirmed.")
    }
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lock = Any()
    @Volatile private var running = false
    @Volatile private var releaseFailed = false
    private var closed = false
    private var worker: Thread? = null
    @Volatile private var recorder: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun start() = synchronized(lock) {
        if (closed || running) return@synchronized
        running = true
        worker = Thread({
            val previousMode = manager.mode
            var rec: AudioRecord? = null
            var aec: AcousticEchoCanceler? = null
            var failure = false
            try {
                manager.mode = AudioManager.MODE_IN_COMMUNICATION
                val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, sampleRate / 5))
                recorder = rec
                check(rec.state == AudioRecord.STATE_INITIALIZED)
                // Android's effect flag is a capability report, not acoustic validation.
                // https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler
                val echo = try {
                    if (!AcousticEchoCanceler.isAvailable()) EchoControl.UNAVAILABLE
                    else {
                        aec = AcousticEchoCanceler.create(rec.audioSessionId)
                        aec?.enabled = true
                        if (aec?.enabled == true) EchoControl.REPORTED_ENABLED else EchoControl.NOT_ENABLED
                    }
                } catch (_: Exception) { EchoControl.SETUP_FAILED }
                echoControl(echo)
                // The owner may have been stopped during construction. Never start a cancelled recorder.
                synchronized(lock) {
                    if (running) {
                        rec.startRecording()
                        check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                        started()
                    }
                }
                val buffer = ByteArray(sampleRate / 5) // 100 ms PCM16, reused until the transport consumes it.
                while (running) {
                    val n = rec.read(buffer, 0, buffer.size)
                    if (n < 0 && running) error("Microphone read failed")
                    if (n > 0 && running) {
                        level(PcmEnvelope.rms16le(buffer, 0, n))
                        if (!send(buffer, n) && running) error("Audio connection stalled")
                    }
                }
            } catch (_: Exception) { failure = running }
            finally {
                running = false
                try { rec?.stop() } catch (_: Exception) { }
                try { aec?.release() } catch (_: Exception) { releaseFailed = true }
                try { rec?.release() } catch (_: Exception) { releaseFailed = true }
                recorder = null
                try { manager.mode = previousMode } catch (_: Exception) { releaseFailed = true }
            }
            // Failure handling is outside resource cleanup, and may safely request asynchronous engine stop.
            if (failure) failed()
        }, "ManzanillaMic").apply { isDaemon = true; start() }
    }

    fun stop() {
        val thread = synchronized(lock) {
            closed = true
            running = false
            worker
        }
        try { recorder?.stop() } catch (_: Exception) { }
        if (thread !== Thread.currentThread()) thread?.join()
        synchronized(lock) { worker = null }
        check(!releaseFailed) { "Microphone release could not be confirmed" }
    }
}
