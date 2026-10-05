package com.agentdeck.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.agentdeck.net.BridgeClient
import kotlin.math.abs
import kotlin.math.min

/**
 * Streams the phone's microphone to the Bridge as raw PCM16 mono 16 kHz,
 * framed as binary WebSocket messages tagged 0x01. Started while a PTT key is
 * held (or dictation toggled on), stopped on release.
 */
class MicStreamer(context: Context, private val client: BridgeClient) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    companion object {
        const val SAMPLE_RATE = 16000
        const val TAG_AUDIO: Byte = 0x01
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    val isRunning get() = running

    @SuppressLint("MissingPermission")   // RECORD_AUDIO checked by MainActivity
    fun start() {
        if (running) return
        running = true
        thread = Thread {
            val previousMode = audioManager.mode
            // The Orange/ADOC desk phone exposes its handset through the
            // telephony route even though Android labels it "Built-In Mic".
            // Communication mode makes the HAL select that receiver path.
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, 4096)
                )
            } catch (e: Exception) {
                audioManager.mode = previousMode
                running = false
                return@Thread
            }

            try {
                rec.startRecording()
                val buf = ByteArray(1280)   // 40 ms @ 16 kHz PCM16
                var meterFrames = 0
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        var peak = 0
                        var i = 0
                        while (i + 1 < n) {
                            val sample = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
                            peak = maxOf(peak, abs(sample.toShort().toInt()))
                            i += 2
                        }
                        // Lift quiet handset speech without magnifying idle
                        // electrical noise or clipping already-healthy audio.
                        val gain = if (peak in 65..5000) min(12, 12000 / maxOf(1, peak)) else 1
                        if (gain > 1) {
                            i = 0
                            while (i + 1 < n) {
                                val sample = ((buf[i].toInt() and 0xff) or
                                    (buf[i + 1].toInt() shl 8)).toShort().toInt()
                                val boosted = (sample * gain).coerceIn(-32768, 32767)
                                buf[i] = (boosted and 0xff).toByte()
                                buf[i + 1] = ((boosted shr 8) and 0xff).toByte()
                                i += 2
                            }
                        }
                        if (++meterFrames % 25 == 0) {
                            Log.i("AgentDeckMic", "source=VOICE_COMMUNICATION peak=$peak gain=$gain")
                        }
                        client.sendBinary(TAG_AUDIO, buf, n)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
                audioManager.mode = previousMode
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread = null
    }
}
