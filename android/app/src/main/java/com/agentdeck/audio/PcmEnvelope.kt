package com.agentdeck.audio

import kotlin.math.exp
import kotlin.math.sqrt

/** A fixed-size PCM meter indexed by played frames, not network arrival time. */
class PcmEnvelope {
    private val starts = LongArray(16)
    private val ends = LongArray(16)
    private val levels = FloatArray(16)
    private var head = 0
    private var count = 0
    private var writtenFrames = 0L
    private var level = 0f
    private var lastPoll = Long.MIN_VALUE
    private var lastPlayed = -1L
    private var lastAdvance = Long.MIN_VALUE
    private var lastAudible = Long.MIN_VALUE

    /** Call only for PCM bytes AudioTrack actually accepted. No slices are allocated. */
    @Synchronized fun written(pcm: ByteArray, offset: Int, byteCount: Int) {
        if (!validRange(pcm, offset, byteCount)) return
        if (count == levels.size) {
            head = (head + 1) % levels.size
            count--
        }
        val tail = (head + count) % levels.size
        starts[tail] = writtenFrames
        writtenFrames += byteCount / 2
        ends[tail] = writtenFrames
        levels[tail] = rms16le(pcm, offset, byteCount)
        count++
    }

    /** nowNanos must come from a monotonic clock; a backwards value cannot grow energy. */
    @Synchronized fun currentEnergy(playedFrames: Long, nowNanos: Long): Float {
        val now = if (lastPoll == Long.MIN_VALUE) nowNanos else maxOf(nowNanos, lastPoll)
        val frames = maxOf(0L, playedFrames)
        if (frames != lastPlayed) {
            lastPlayed = frames
            lastAdvance = now
        }
        while (count > 0 && frames >= ends[head]) {
            head = (head + 1) % levels.size
            count--
        }
        val target = if (count > 0 && frames >= starts[head]) levels[head] else 0f
        val stalled = lastAdvance != Long.MIN_VALUE && now - lastAdvance >= SILENCE_NANOS
        if (target > 0f && !stalled) lastAudible = now
        val silent = target == 0f && (lastAudible == Long.MIN_VALUE || now - lastAudible >= SILENCE_NANOS)
        if (stalled || silent) {
            level = 0f
        } else {
            val elapsed = if (lastPoll == Long.MIN_VALUE) 50_000_000L else now - lastPoll
            val timeConstant = if (target > level) ATTACK_NANOS else RELEASE_NANOS
            val blend = (1.0 - exp(-elapsed.toDouble() / timeConstant)).toFloat()
            level = (level + (target - level) * blend).coerceIn(0f, 1f)
        }
        lastPoll = now
        return level
    }

    @Synchronized fun reset() {
        head = 0
        count = 0
        writtenFrames = 0L
        level = 0f
        lastPoll = Long.MIN_VALUE
        lastPlayed = -1L
        lastAdvance = Long.MIN_VALUE
        lastAudible = Long.MIN_VALUE
    }

    companion object {
        private const val ATTACK_NANOS = 15_000_000.0
        private const val RELEASE_NANOS = 45_000_000.0
        // At a 20 Hz UI cadence this reaches zero within 150 ms of silence/stall.
        private const val SILENCE_NANOS = 120_000_000L

        private fun validRange(pcm: ByteArray, offset: Int, byteCount: Int): Boolean =
            offset >= 0 && byteCount > 0 && byteCount % 2 == 0 && offset % 2 == 0 &&
                byteCount <= pcm.size && offset <= pcm.size - byteCount

        /** Normalized RMS of signed little-endian PCM16; invalid ranges are silent. */
        fun rms16le(pcm: ByteArray, offset: Int = 0, byteCount: Int = pcm.size): Float {
            if (!validRange(pcm, offset, byteCount)) return 0f
            var sum = 0.0
            var at = offset
            val end = offset + byteCount
            while (at < end) {
                val sample = ((pcm[at].toInt() and 0xff) or (pcm[at + 1].toInt() shl 8)).toDouble()
                sum += sample * sample
                at += 2
            }
            return (sqrt(sum / (byteCount / 2)) / 32768.0).toFloat().coerceIn(0f, 1f)
        }
    }
}
