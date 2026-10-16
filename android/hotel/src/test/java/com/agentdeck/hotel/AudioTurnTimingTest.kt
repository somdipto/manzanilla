package com.agentdeck.hotel

import org.junit.Assert.*
import org.junit.Test

class AudioTurnTimingTest {
    private class Clock { var ms = 10L }

    @Test fun boundaryEstimateUsesLastActivePacketAndDoesNotInventProviderFinal() {
        val clock = Clock()
        val points = ArrayList<AudioTurnTiming.Point>()
        val timing = AudioTurnTiming({ clock.ms }, points::add)
        timing.inputLevel(0.2f, 0.01f)
        clock.ms = 110
        timing.inputLevel(0.3f, 0.01f)
        clock.ms = 210
        timing.inputLevel(0f, 0.01f)
        timing.inputLevel(0f, 0.01f)
        assertEquals(2, points.size)
        assertEquals("input_speech_boundary_estimate", points.last().endpoint)
        assertEquals(110L, points.last().boundaryEstimateMs)
        assertEquals(210L, points.last().atMs)
        clock.ms = 320
        timing.providerInputFinal()
        assertEquals("provider_input_final_received", points.last().endpoint)
        assertEquals(320L, points.last().atMs)
        assertNull(points.last().boundaryEstimateMs)
        clock.ms = 400
        timing.inputLevel(0.2f, 0.01f)
        assertEquals(2L, points.last().sequence)
    }

    @Test fun rawArrivalAndTurnCompletionAreNotAudibleUntilHeadPassesFirstFrame() {
        val clock = Clock()
        val points = ArrayList<AudioTurnTiming.Point>()
        val timing = AudioTurnTiming({ clock.ms }, points::add)
        timing.rawReceived(240)
        timing.rawReceived(480)
        timing.playbackHead(240)
        assertEquals(listOf("raw_pcm_received"), points.map { it.endpoint })
        clock.ms = 20
        timing.outputComplete()
        clock.ms = 70
        timing.playbackHead(241)
        timing.playbackHead(480)
        assertEquals(listOf("raw_pcm_received", "provider_output_turn_complete_received", "raw_pcm_first_played_head_observed"), points.map { it.endpoint })
        assertEquals(70L, points.last().atMs)
        assertEquals(1L, points.last().sequence)
    }

    @Test fun queuedTurnsHaveIndependentFirstPlayedObservations() {
        val clock = Clock()
        val points = ArrayList<AudioTurnTiming.Point>()
        val timing = AudioTurnTiming({ clock.ms }, points::add)
        timing.rawReceived(0)
        timing.outputComplete()
        timing.rawReceived(480)
        clock.ms = 30
        timing.playbackHead(1)
        clock.ms = 60
        timing.playbackHead(481)
        val played = points.filter { it.endpoint == "raw_pcm_first_played_head_observed" }
        assertEquals(listOf(1L, 2L), played.map { it.sequence })
        assertEquals(listOf(30L, 60L), played.map { it.atMs })
    }

    @Test fun interruptionCancelsPendingFirstPlayedAndNewTurnUsesNewSequence() {
        val points = ArrayList<AudioTurnTiming.Point>()
        val timing = AudioTurnTiming({ 10L }, points::add)
        timing.rawReceived(100)
        timing.interrupted()
        timing.playbackHead(200)
        assertFalse(points.any { it.endpoint == "raw_pcm_first_played_head_observed" })
        timing.rawReceived(0)
        timing.playbackHead(1)
        assertEquals(2L, points.last().sequence)
    }

    @Test fun sdkDecodedOutputNeverProducesAnAudibleMetricAndResetsPerOutputState() {
        val points = ArrayList<AudioTurnTiming.Point>()
        val timing = AudioTurnTiming({ 10L }, points::add)
        repeat(100) { timing.sdkDecodedReceived() }
        timing.playbackHead(10_000)
        timing.outputComplete(sdkState = true)
        timing.sdkDecodedReceived()
        assertEquals(listOf("sdk_decoded_received_not_audible", "sdk_output_state_ended_received", "sdk_decoded_received_not_audible"), points.map { it.endpoint })
        assertEquals(listOf(1L, 1L, 2L), points.map { it.sequence })
    }
}
