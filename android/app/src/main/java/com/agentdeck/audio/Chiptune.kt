package com.agentdeck.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.MediaPlayer
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * Tiny NES-style synth: square-wave jingles rendered on the fly.
 * No assets, no MediaPlayer — pure 8-bit bleeps like it's 1985.
 */
object Chiptune {

    private const val SR = 22050
    private const val BOOT_MUSIC_OFFSET_MS = 10_000
    private val bootHandler = Handler(Looper.getMainLooper())
    private var bootPlayer: MediaPlayer? = null
    @Volatile private var bootSfxTrack: AudioTrack? = null
    @Volatile private var bootGeneration = 0

    // note frequencies (Hz)
    private const val C4 = 261.63; private const val D4 = 293.66; private const val E4 = 329.63
    private const val EB4 = 311.13; private const val FS4 = 369.99; private const val AB4 = 415.30
    private const val F4 = 349.23; private const val G4 = 392.00; private const val A4 = 440.00
    private const val B4 = 493.88; private const val C5 = 523.25; private const val D5 = 587.33
    private const val E5 = 659.25; private const val F5 = 698.46; private const val G5 = 783.99
    private const val A5 = 880.00; private const val B5 = 987.77; private const val C6 = 1046.50
    private const val A2 = 110.00; private const val B2 = 123.47
    private const val R = 0.0    // rest

    /** Game-Boy-ish startup: da-ding! */
    fun boot() = play(listOf(C4 to 90, G4 to 90, C5 to 90, E5 to 220), vol = 0.5)

    /** Original comic-hero/web-swing stinger; not derived from any film or game score. */
    fun webStart() = play(listOf(D4 to 70, A4 to 70, D5 to 85, F5 to 95,
        A5 to 110, C6 to 260), vol = 0.48, duty = 0.34)

    /** User-composed 5–7 second Webby cue, rendered as a compact 8-bit console lead. */
    fun webSaverTheme() = play(listOf(
        C4 to 300, R to 42, EB4 to 300, R to 42, G4 to 320, R to 42,
        FS4 to 300, R to 42, EB4 to 300, R to 42, C4 to 430, R to 150,
        C4 to 300, R to 42, EB4 to 300, R to 42, G4 to 300, R to 42,
        AB4 to 340, R to 42, G4 to 300, R to 42, FS4 to 300, R to 42,
        EB4 to 320, R to 42, C4 to 650
    ), vol = 0.34, duty = 0.25)

    /** Short descending sign-off used when leaving the web theme. */
    fun webOff() = play(listOf(A5 to 80, F5 to 90, D5 to 110, A4 to 220),
        vol = 0.38, duty = 0.30)

    /** Fluid launcher cues: soft glassy sine partials with very short tails. */
    fun orbitMove() = playSoft(listOf(740.0 to 32, 988.0 to 48), vol = 0.23)
    /** Hard, short metal-on-glass strike for horizontal XMB category travel. */
    fun orbitMetal() = playMetallicStrike()
    fun orbitConfirm() = playSoft(listOf(523.25 to 48, 783.99 to 58, 1046.5 to 105), vol = 0.28)
    fun orbitBack() = playSoft(listOf(783.99 to 45, 587.33 to 55, 392.0 to 92), vol = 0.24)
    fun orbitReady() = playSoft(listOf(523.25 to 65, 659.25 to 70, 783.99 to 82, 1046.5 to 160), vol = 0.30)

    private fun playMetallicStrike() {
        thread(isDaemon = true) {
            try {
                val durationMs = 118
                val count = SR * durationMs / 1000
                val buf = ShortArray(count)
                var seed = 0x51A7C3
                for (i in 0 until count) {
                    val t = i.toDouble() / SR
                    seed = seed * 1664525 + 1013904223
                    val noise = (((seed ushr 16) and 0xffff) / 32768.0) - 1.0
                    val attack = (t / .0035).coerceIn(0.0, 1.0)
                    val decay = kotlin.math.exp(-t * 31.0)
                    val ring = sin(2.0 * PI * 1_180.0 * t) * .58 +
                        sin(2.0 * PI * 1_947.0 * t) * .27 +
                        sin(2.0 * PI * 2_731.0 * t) * .15
                    val transient = noise * kotlin.math.exp(-t * 92.0) * .42
                    val sample = (ring + transient) * attack * decay
                    buf[i] = (sample * Short.MAX_VALUE * .72).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(SR)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(buf.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(buf, 0, buf.size)
                track.play()
                Thread.sleep(durationMs.toLong() + 25L)
                track.release()
            } catch (_: Exception) {}
        }
    }

    /** full boot theme — 8-bit SaaS launch music, ~8s of NES melody */
    fun bootMusic(context: Context, durationMs: Long = 13_800L, duckAtMs: Long = 11_300L) {
        stopBootMusic()
        val generation = bootGeneration
        try {
            val resourceId = context.resources.getIdentifier(
                "manzanilla_boot_music", "raw", context.packageName
            )
            if (resourceId == 0) throw IllegalStateException("Boot score resource is missing")
            val player = MediaPlayer.create(context.applicationContext, resourceId)
                ?: throw IllegalStateException("Unable to open boot score")
            bootPlayer = player
            player.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            player.isLooping = false
            player.setVolume(0.54f, 0.54f)
            player.seekTo(BOOT_MUSIC_OFFSET_MS)
            player.start()

            bootHandler.postDelayed({
                if (generation == bootGeneration && bootPlayer === player) {
                    player.setVolume(0.17f, 0.17f)
                }
            }, duckAtMs)
            bootHandler.postDelayed({
                if (generation == bootGeneration && bootPlayer === player) {
                    try { player.stop() } catch (_: Exception) {}
                    player.release()
                    bootPlayer = null
                }
            }, durationMs)
            playStorySfx(durationMs.toInt(), generation)
        } catch (_: Exception) {
            playBootSoundscape()
        }
    }

    /** Original synthesized score shared with Windows onboarding. No firmware samples. */
    fun dockstationBoot(context: Context) {
        stopBootMusic()
        try {
            val id = context.resources.getIdentifier("dockstation_arrival", "raw", context.packageName)
            val player = MediaPlayer.create(context.applicationContext, id) ?: return
            bootPlayer = player
            player.setVolume(0.42f, 0.42f)
            player.setOnCompletionListener { if (bootPlayer === it) bootPlayer = null; it.release() }
            player.start()
        } catch (_: Exception) { /* Startup remains usable even without audio. */ }
    }

    fun stopBootMusic() {
        bootGeneration += 1
        bootPlayer?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        bootPlayer = null
        bootSfxTrack?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        bootSfxTrack = null
    }

    private fun playStorySfx(durationMs: Int, generation: Int) {
        thread(isDaemon = true) {
            try {
                val count = SR * durationMs / 1000
                val buf = ShortArray(count)
                var seed = 0x2A6D31F

                fun env(t: Double, start: Double, length: Double): Double {
                    val p = (t - start) / length
                    if (p <= 0.0 || p >= 1.0) return 0.0
                    val attack = (p / 0.12).coerceAtMost(1.0)
                    return attack * (1.0 - p).pow(1.7)
                }

                fun sweep(t: Double, start: Double, length: Double,
                          from: Double, to: Double): Double {
                    val local = t - start
                    if (local <= 0.0 || local >= length) return 0.0
                    val k = (to - from) / length
                    return sin(2.0 * PI * (from * local + 0.5 * k * local * local)) *
                        env(t, start, length)
                }

                val signalNotes = doubleArrayOf(C4, E4, G4, C5, E5)
                val logoNotes = doubleArrayOf(C4, G4, C5, E5, G5)
                for (i in 0 until count) {
                    val t = i.toDouble() / SR
                    seed = seed * 1664525 + 1013904223
                    val noise = (((seed ushr 16) and 0xffff) / 32768.0) - 1.0
                    var sample = 0.0

                    sample += sweep(t, 2.45, 0.72, 92.0, 620.0) * 0.16
                    sample += sin(2.0 * PI * 196.0 * t) * env(t, 2.68, 0.38) * 0.09

                    for (n in 0..4) {
                        val start = 4.35 + n * 0.20
                        sample += sin(2.0 * PI * (330.0 + n * 82.0) * t) *
                            env(t, start, 0.24) * 0.07
                    }

                    for (n in signalNotes.indices) {
                        val start = 8.72 + n * 0.24
                        val pulse = if (sin(2.0 * PI * signalNotes[n] * t) >= 0.0) 1.0 else -1.0
                        sample += pulse * env(t, start, 0.27) * 0.08
                    }

                    sample += noise * env(t, 11.18, 0.20) * 0.25
                    sample += sweep(t, 11.22, 0.58, 78.0, 1_760.0) * 0.22
                    sample += sin(2.0 * PI * 58.0 * t) * env(t, 11.28, 0.52) * 0.15

                    for (n in logoNotes.indices) {
                        val start = 11.92 + n * 0.18
                        val pulse = if (sin(2.0 * PI * logoNotes[n] * t) >= 0.0) 1.0 else -1.0
                        sample += pulse * env(t, start, 0.24) * 0.075
                    }
                    val resolve = env(t, 12.82, 0.88)
                    sample += sin(2.0 * PI * C4 * t) * resolve * 0.10
                    sample += sin(2.0 * PI * E4 * t) * resolve * 0.08
                    sample += sin(2.0 * PI * G4 * t) * resolve * 0.07
                    sample += sin(2.0 * PI * C5 * t) * resolve * 0.05

                    // Each readable feature card gets its own restrained identity ping.
                    val featureStarts = doubleArrayOf(14.08, 16.28, 18.48)
                    val featureRoots = doubleArrayOf(C4, E4, G4)
                    for (f in featureStarts.indices) {
                        val root = featureRoots[f]
                        sample += sin(2.0 * PI * root * t) * env(t, featureStarts[f], 0.52) * 0.10
                        sample += sin(2.0 * PI * root * 1.5 * t) * env(t, featureStarts[f] + 0.10, 0.48) * 0.07
                        sample += sweep(t, featureStarts[f] + 1.72, 0.30, root, root * 2.0) * 0.08
                    }
                    val finalReady = env(t, 20.72, 0.86)
                    sample += sin(2.0 * PI * C4 * t) * finalReady * 0.09
                    sample += sin(2.0 * PI * G4 * t) * finalReady * 0.07
                    sample += sin(2.0 * PI * C5 * t) * finalReady * 0.06

                    val shaped = sample / (1.0 + abs(sample))
                    buf[i] = (shaped * Short.MAX_VALUE * 0.78).toInt().toShort()
                }

                if (generation != bootGeneration) return@thread
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(SR)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(buf.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                bootSfxTrack = track
                track.write(buf, 0, buf.size)
                track.play()
                Thread.sleep(durationMs.toLong() + 120L)
                if (bootSfxTrack === track) bootSfxTrack = null
                try { track.release() } catch (_: Exception) {}
            } catch (_: Exception) {}
        }
    }

    /** menu move blip */
    fun blip() = play(listOf(C5 to 35), vol = 0.35)

    /** select / accept */
    fun select() = play(listOf(E5 to 45, G5 to 70), vol = 0.4)

    /** agent finished: little victory fanfare */
    fun done() = play(listOf(G4 to 70, C5 to 70, E5 to 70, G5 to 160), vol = 0.45)

    /** agent needs you: polite two-tone */
    fun needsYou() = play(listOf(E5 to 90, C5 to 160), vol = 0.45)

    /** error buzz */
    fun error() = play(listOf(B2 to 120, A2 to 200), vol = 0.5, duty = 0.25)

    /** ptt open mic */
    fun listen() = play(listOf(C5 to 40, E5 to 40, G5 to 40), vol = 0.35)

    /**
     * Retro sci-fi soundscape synchronized to the 9.25-second cinematic boot:
     * ocean drone, CRT energize, pixel notes, portal whoosh, feature pings and ready bloom.
     */
    private fun playBootSoundscape() {
        thread(isDaemon = true) {
            try {
                val durationMs = 9_250
                val count = SR * durationMs / 1000
                val buf = ShortArray(count)
                var seed = 0x13579BDF

                fun envelope(time: Double, start: Double, length: Double): Double {
                    val p = (time - start) / length
                    if (p <= 0.0 || p >= 1.0) return 0.0
                    return sin(PI * p).pow(2.0)
                }

                fun sweptTone(time: Double, start: Double, length: Double,
                              from: Double, to: Double): Double {
                    val local = (time - start).coerceAtLeast(0.0)
                    val k = (to - from) / length
                    return sin(2.0 * PI * (from * local + 0.5 * k * local * local)) *
                        envelope(time, start, length)
                }

                val buildNotes = doubleArrayOf(C4, E4, G4, C5, E5, G5, B5, C6)
                val featureNotes = doubleArrayOf(C5, E5, G5)
                for (i in 0 until count) {
                    val t = i.toDouble() / SR
                    seed = seed * 1664525 + 1013904223
                    val noise = (((seed ushr 16) and 0xffff) / 32768.0) - 1.0
                    var sample = 0.0

                    val bedFade = 1.0 - (t / 6.1).coerceIn(0.0, 1.0)
                    sample += sin(2.0 * PI * 55.0 * t) * 0.08 * bedFade
                    sample += sin(2.0 * PI * (110.0 + sin(t * 1.7) * 1.5) * t) * 0.045 * bedFade
                    sample += noise * 0.018 * bedFade

                    sample += sweptTone(t, 0.05, 0.38, 90.0, 320.0) * 0.13
                    sample += sweptTone(t, 3.02, 0.62, 105.0, 1_420.0) * 0.22
                    sample += noise * envelope(t, 3.08, 0.46) * 0.07

                    for (n in buildNotes.indices) {
                        val start = 3.48 + n * 0.145
                        val env = envelope(t, start, 0.19)
                        val square = if (sin(2.0 * PI * buildNotes[n] * t) >= 0.0) 1.0 else -1.0
                        sample += square * env * 0.075
                    }

                    sample += sweptTone(t, 4.88, 1.12, 140.0, 1_080.0) * 0.18
                    sample += noise * envelope(t, 4.9, 1.0) * 0.09
                    sample += sin(2.0 * PI * 62.0 * t) * envelope(t, 5.55, 0.34) * 0.20

                    val resolve = doubleArrayOf(C5, E5, G5, C6)
                    for (n in resolve.indices) {
                        val start = 6.03 + n * 0.15
                        val env = envelope(t, start, 0.24)
                        val pulse = if (sin(2.0 * PI * resolve[n] * t) >= 0.0) 1.0 else -1.0
                        sample += pulse * env * 0.095
                    }

                    for (n in featureNotes.indices) {
                        val start = 6.80 + n * 0.50
                        sample += sin(2.0 * PI * featureNotes[n] * t) *
                            envelope(t, start, 0.30) * 0.13
                    }

                    val ready = envelope(t, 8.28, 0.88)
                    sample += sin(2.0 * PI * C5 * t) * ready * 0.10
                    sample += sin(2.0 * PI * E5 * t) * ready * 0.08
                    sample += sin(2.0 * PI * G5 * t) * ready * 0.07
                    sample += sin(2.0 * PI * C6 * t) * ready * 0.05

                    val shaped = sample / (1.0 + abs(sample))
                    buf[i] = (shaped * Short.MAX_VALUE * 0.82).toInt().toShort()
                }

                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(SR)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(buf.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(buf, 0, buf.size)
                track.play()
                Thread.sleep(durationMs.toLong() + 120L)
                track.release()
            } catch (_: Exception) {}
        }
    }

    private fun playSoft(notes: List<Pair<Double, Int>>, vol: Double) {
        thread(isDaemon = true) {
            try {
                val totalMs = notes.sumOf { it.second } + 90
                val buf = ShortArray(SR * totalMs / 1000)
                var cursor = 0
                for ((freq, ms) in notes) {
                    val n = SR * ms / 1000
                    for (i in 0 until n) {
                        if (cursor >= buf.size) break
                        val attack = (i / (SR * .006)).coerceIn(0.0, 1.0)
                        val release = ((n - i) / (SR * .030)).coerceIn(0.0, 1.0)
                        val env = attack * release * release
                        val t = i.toDouble() / SR
                        val fundamental = sin(2.0 * PI * freq * t)
                        val shimmer = sin(2.0 * PI * freq * 2.01 * t) * .22
                        val body = sin(2.0 * PI * freq * .501 * t) * .10
                        buf[cursor++] = ((fundamental + shimmer + body) * env * vol *
                            Short.MAX_VALUE * .58).toInt().coerceIn(Short.MIN_VALUE.toInt(),
                            Short.MAX_VALUE.toInt()).toShort()
                    }
                    val gap = SR * 7 / 1000
                    repeat(gap) { if (cursor < buf.size) buf[cursor++] = 0 }
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(SR)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(buf.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(buf, 0, buf.size)
                track.play()
                Thread.sleep(totalMs.toLong() + 80L)
                track.release()
            } catch (_: Exception) {}
        }
    }

    private fun play(notes: List<Pair<Double, Int>>, vol: Double = 0.4, duty: Double = 0.5) {
        thread(isDaemon = true) {
            try {
                val totalMs = notes.sumOf { it.second } + 30
                val buf = ShortArray(SR * totalMs / 1000)
                var idx = 0
                for ((freq, ms) in notes) {
                    val n = SR * ms / 1000
                    if (freq <= 0.0) {
                        repeat(n) { if (idx < buf.size) buf[idx++] = 0 }
                        continue
                    }
                    val period = SR / freq
                    for (i in 0 until n) {
                        if (idx >= buf.size) break
                        val phase = (i % period) / period
                        val sq = if (phase < duty) 1.0 else -1.0
                        // soft attack/decay to avoid clicks
                        val env = minOf(1.0, i / 80.0, (n - i) / 200.0).coerceAtLeast(0.0)
                        // a whisper of vibrato for charm
                        val vib = 1.0 + 0.003 * sin(2 * PI * i * 6.0 / SR)
                        buf[idx++] = (sq * env * vib * vol * Short.MAX_VALUE * 0.6).toInt().toShort()
                    }
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(SR)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(buf.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(buf, 0, buf.size)
                track.play()
                Thread.sleep(totalMs.toLong() + 80)
                track.release()
            } catch (_: Exception) {}
        }
    }
}
