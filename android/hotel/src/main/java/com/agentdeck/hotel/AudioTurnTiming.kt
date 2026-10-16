package com.agentdeck.hotel

import java.util.ArrayDeque

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
    private val pending = ArrayDeque<Probe>()

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
        if (currentOutput == 0L) { currentOutput = ++output; received = false }
    }

    @Synchronized fun rawReceived(firstFrame: Long) {
        beginOutput()
        if (received) return
        received = true
        report(Point(currentOutput, "raw_pcm_received", now()))
        pending.addLast(Probe(currentOutput, firstFrame))
    }

    /** Local SDK output-state grouping only; the SDK exposes no output-to-input association here. */
    @Synchronized fun sdkDecodedReceived() {
        beginOutput()
        if (received) return
        received = true
        report(Point(currentOutput, "sdk_decoded_received_not_audible", now()))
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
    }

    @Synchronized fun interrupted() {
        if (currentOutput != 0L) report(Point(currentOutput, "output_interrupted_received", now()))
        currentOutput = 0
        received = false
        pending.clear()
    }
}
