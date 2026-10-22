package com.agentdeck.hotel

import java.util.ArrayDeque
import java.nio.ByteBuffer

/** Monotonic endpoint observations, NOT acoustic latency or input/output association.
 * Packet RMS / sampled SDK input level is only a speech-boundary estimate. Provider finals are separate.
 * Raw PCM first-played means a sampled AudioTrack head passed the first submitted frame;
 * this is not a measurement at the speaker/ear. SDK decoded callbacks mean received only.
 */
internal class AudioTurnTiming(private val now: () -> Long, private val report: (Point) -> Unit) {
    data class Point(val sequence: Long, val endpoint: String, val atMs: Long, val boundaryEstimateMs: Long? = null)
    private data class Probe(val sequence: Long, val firstFrame: Long)
    private var input = 0L
    private var activeInput = false
    private var lastActivePacketAt = 0L
    private var providerFinal = 0L
    private var output = 0L
    private var currentOutput = 0L
    private var received = false
    private var nonSilent = false
    private val pending = ArrayDeque<Probe>()

    /** Diagnostic receipt in this run's monotonic clock; never an inferred provider span. */
    @Synchronized fun marker(endpoint: String) { report(Point(0, endpoint, now())) }
    @Synchronized fun inputLevel(level: Float, threshold: Float, at: Long = now()) {
        if (level > threshold) {
            if (!activeInput) {
                activeInput = true
                input++
                report(Point(input, "input_activity_started", at))
            }
            lastActivePacketAt = at
        } else if (activeInput) {
            activeInput = false
            report(Point(input, "input_speech_boundary_estimate", at, lastActivePacketAt))
        }
    }

    @Synchronized fun providerInputFinal() {
        report(Point(++providerFinal, "provider_input_final_received", now()))
    }

    private fun beginOutput() {
        if (currentOutput == 0L) { currentOutput = ++output; received = false; nonSilent = false }
    }

    @Synchronized fun rawReceived(firstFrame: Long) {
        beginOutput()
        if (received) return
        received = true
        report(Point(currentOutput, "raw_pcm_received", now()))
        pending.addLast(Probe(currentOutput, firstFrame))
    }

    /** A state receipt can open a group before any decoded frames arrive. */
    @Synchronized fun sdkOutputStarted() {
        beginOutput()
        report(Point(currentOutput, "sdk_output_state_started_received", now()))
    }

    /** Local SDK output-state grouping only; the SDK exposes no output-to-input association here.
     * WebRTC may keep delivering silent frames while the agent is idle. They must
     * not create the next output group minutes before that output actually starts.
     * Non-silent PCM may precede its state event, so it can also open a group.
     */
    @Synchronized fun sdkDecodedReceived(pcm: ByteBuffer? = null, sampleBytes: Int = 0) {
        val crossing = !nonSilent && pcm != null && containsActivity(pcm, sampleBytes)
        if (currentOutput == 0L && pcm != null && !crossing) return
        beginOutput()
        if (!received) {
            received = true
            report(Point(currentOutput, "sdk_decoded_received_not_audible", now()))
        }
        if (crossing) {
            nonSilent = true
            report(Point(currentOutput, "sdk_non_silent_decoded_received_not_audible", now()))
        }
    }

    private fun containsActivity(pcm: ByteBuffer, sampleBytes: Int): Boolean {
        val start = pcm.position()
        val bytes = minOf(pcm.remaining(), sampleBytes)
        var offset = 0
        while (offset + 1 < bytes) {
            val sample = ((pcm.get(start + offset).toInt() and 255) or (pcm.get(start + offset + 1).toInt() shl 8)).toShort().toInt()
            if (sample > 655 || sample < -655) return true
            offset += 2
        }
        return false
    }

    @Synchronized fun playbackHead(frames: Long) {
        while (pending.isNotEmpty() && frames > pending.peekFirst().firstFrame) {
            report(Point(pending.removeFirst().sequence, "raw_pcm_first_played_head_observed", now()))
        }
    }

    @Synchronized fun outputComplete(sdkState: Boolean = false) {
        if (currentOutput != 0L) report(Point(currentOutput,
            if (sdkState) "sdk_output_state_ended_received" else "provider_output_turn_complete_received", now()))
        currentOutput = 0
        received = false
        nonSilent = false
    }

    @Synchronized fun interrupted() {
        if (currentOutput != 0L) report(Point(currentOutput, "output_interrupted_received", now()))
        currentOutput = 0
        received = false
        nonSilent = false
        pending.clear()
    }
}

/** A bounded diagnostic handoff. Audio callbacks never wait for the writer (logcat).
 * The supplied dispatcher must enqueue work asynchronously. It is called at most
 * once per active drain; a slow logger cannot create one coroutine per PCM event.
 * Old diagnostic points may be dropped under pressure, but audio is never dropped.
 */
internal class AudioTimingLog(
    private val dispatch: (() -> Unit) -> Unit,
    private val write: (AudioTurnTiming.Point) -> Unit,
    private val dropped: (Int) -> Unit = {},
    private val capacity: Int = 128
) {
    private val lock = Any()
    private val pending = ArrayDeque<AudioTurnTiming.Point>()
    private var draining = false
    private var lost = 0

    init { require(capacity > 0) }

    fun offer(point: AudioTurnTiming.Point) {
        val start = synchronized(lock) {
            if (pending.size == capacity) { pending.removeFirst(); lost++ }
            pending.addLast(point)
            if (draining) false else { draining = true; true }
        }
        if (start) dispatch(::drain)
    }

    private fun drain() {
        while (true) {
            val next = synchronized(lock) {
                if (pending.isEmpty()) { draining = false; return }
                val item = pending.removeFirst()
                val count = lost
                lost = 0
                item to count
            }
            // A diagnostic failure must not wedge the queue or affect live audio.
            if (next.second > 0) try { dropped(next.second) } catch (_: Exception) { }
            try { write(next.first) } catch (_: Exception) { }
        }
    }
}
