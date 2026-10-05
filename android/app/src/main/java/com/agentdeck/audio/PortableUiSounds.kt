package com.agentdeck.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.agentdeck.R

/**
 * Lightweight, pre-decoded navigation cues for the ORBIT cross-axis UI.
 *
 * The source WAVs are private project resources extracted from the owner's
 * PSP 6.61 firmware image. SoundPool keeps them resident so navigation never
 * blocks the drawing thread or starts a MediaPlayer for a 40 ms response.
 */
object PortableUiSounds {
    private var pool: SoundPool? = null
    private val sounds = HashMap<Cue, Int>()
    private val ready = HashSet<Int>()

    enum class Cue { MOVE, CONFIRM, BACK, CATEGORY, OPTION, READY, FAILURE }

    fun init(context: Context) {
        if (pool != null) return
        val soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Route through the same media/game channel controlled by
                    // Manzanilla's volume slider. Some vendor builds mute the
                    // separate system-sonification stream completely.
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) synchronized(ready) { ready.add(sampleId) }
        }
        pool = soundPool
        sounds[Cue.MOVE] = soundPool.load(context, R.raw.portable_cursor, 1)
        sounds[Cue.CONFIRM] = soundPool.load(context, R.raw.portable_confirm, 1)
        sounds[Cue.BACK] = soundPool.load(context, R.raw.portable_cancel, 1)
        sounds[Cue.CATEGORY] = soundPool.load(context, R.raw.portable_category_open, 1)
        sounds[Cue.OPTION] = soundPool.load(context, R.raw.portable_option, 1)
        sounds[Cue.READY] = soundPool.load(context, R.raw.portable_system_success, 1)
        sounds[Cue.FAILURE] = soundPool.load(context, R.raw.portable_system_failure, 1)
    }

    fun play(cue: Cue, volume: Float = 0.94f): Boolean {
        val soundPool = pool ?: return false
        val sample = sounds[cue] ?: return false
        if (synchronized(ready) { sample !in ready }) return false
        return soundPool.play(sample, volume, volume, 1, 0, 1f) != 0
    }

    fun release() {
        pool?.release()
        pool = null
        sounds.clear()
        synchronized(ready) { ready.clear() }
    }
}
