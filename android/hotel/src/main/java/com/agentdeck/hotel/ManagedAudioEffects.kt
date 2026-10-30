package com.agentdeck.hotel

import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/** Observe the actual SDK AudioRecord session, not software AEC's unrelated render reference.
 * Android anonymizes other applications' session IDs. Never bind an anonymous or ambiguous session.
 * Enabled platform effects are not proof of physical echo rejection on this tablet/audio route.
 */
internal class ManagedAudioEffects(private val manager: AudioManager, private val emit: (JSONObject) -> Unit) {
    private var closed = false
    private var session = 0
    private var releaseFailed = false
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private val existing = manager.activeRecordingConfigurations.map { it.clientAudioSessionId }.toSet()
    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) { observe(configs) }
    }
    init { manager.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper())) }

    @Synchronized fun refresh() { if (!closed) observe(manager.activeRecordingConfigurations) }
    @Synchronized private fun observe(configs: List<AudioRecordingConfiguration>) {
        if (closed) return
        val candidates = configs.filter { it.clientAudioSessionId > 0 && it.clientAudioSessionId !in existing &&
            it.clientAudioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION }
        val next = candidates.singleOrNull()?.clientAudioSessionId ?: 0
        if (next != session) {
            releaseEffects()
            session = next
            if (next > 0) {
                aec = try { AcousticEchoCanceler.create(next) } catch (_: Exception) { null }
                ns = try { NoiseSuppressor.create(next) } catch (_: Exception) { null }
                try { aec?.let { if (it.hasControl()) it.enabled = true } } catch (_: Exception) { }
                try { ns?.let { if (it.hasControl()) it.enabled = true } } catch (_: Exception) { }
            }
        }
        val echo = try { aec?.enabled == true } catch (_: Exception) { false }
        val noise = try { ns?.enabled == true } catch (_: Exception) { false }
        emit(JSONObject().put("type", "audio_processing").put("aec", echo).put("noise_suppression", noise)
            .put("aec_control", try { aec?.hasControl() == true } catch (_: Exception) { false })
            .put("noise_suppression_control", try { ns?.hasControl() == true } catch (_: Exception) { false })
            .put("platform_session_verified", next > 0).put("acoustic_reference_qualified", false))
    }
    private fun releaseEffects() {
        try { aec?.release() } catch (_: Exception) { releaseFailed = true } finally { aec = null }
        try { ns?.release() } catch (_: Exception) { releaseFailed = true } finally { ns = null }
    }
    @Synchronized fun close() {
        if (closed) return
        closed = true
        try { manager.unregisterAudioRecordingCallback(callback) } finally { releaseEffects() }
        check(!releaseFailed) { "Platform audio effect release was not confirmed" }
    }
}
