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

    @Test fun silentDecodedFramesAreNotFirstNonSilentAudioAndInputClockRemainsIndependent() {
        var clock = 100L
        val points = ArrayList<AudioTurnTiming.Point>()
        val timing = AudioTurnTiming({ clock }, points::add)
        val pcm = java.nio.ByteBuffer.wrap(byteArrayOf(0, 0, 0, 0, 0, 4))
        timing.sdkDecodedReceived(pcm, 4)
        assertTrue("Idle silent frames must not open an output group", points.isEmpty())
        clock = 160L
        timing.sdkDecodedReceived(pcm, 6)
        repeat(10) { timing.sdkDecodedReceived(pcm, 6) }
        assertEquals(0, pcm.position())
        assertEquals(listOf("sdk_decoded_received_not_audible", "sdk_non_silent_decoded_received_not_audible"), points.map { it.endpoint })
        assertEquals(160L, points.last().atMs)
        assertNull(points.last().boundaryEstimateMs)
        timing.interrupted()
        clock = 200L
        timing.sdkDecodedReceived(pcm, 6)
        assertEquals(2L, points.last().sequence)
        assertEquals(200L, points.last().atMs)
    }

    @Test fun silentFramesBetweenRepliesCannotTimestampTheNextReplyEarly() {
        var clock = 100L
        val points = ArrayList<AudioTurnTiming.Point>()
        val timing = AudioTurnTiming({ clock }, points::add)
        val speech = java.nio.ByteBuffer.wrap(byteArrayOf(0, 4))
        val silence = java.nio.ByteBuffer.wrap(byteArrayOf(0, 0))
        timing.sdkDecodedReceived(speech, 2)
        timing.outputComplete(sdkState = true)
        repeat(100) { clock += 10; timing.sdkDecodedReceived(silence, 2) }
        assertEquals(3, points.size)
        clock = 2500L
        timing.sdkDecodedReceived(speech, 2)
        assertEquals(2L, points.last().sequence)
        assertEquals(2500L, points.last().atMs)
        assertEquals(2500L, points[3].atMs)
    }

    @Test fun speakingStateOpensOutputGroupWithoutClaimingAudioHasArrived() {
        var clock = 100L
        val points = ArrayList<AudioTurnTiming.Point>()
        val timing = AudioTurnTiming({ clock }, points::add)
        timing.sdkOutputStarted()
        assertEquals(listOf("sdk_output_state_started_received"), points.map { it.endpoint })
        clock = 120L
        timing.sdkDecodedReceived(java.nio.ByteBuffer.wrap(byteArrayOf(0, 0)), 2)
        clock = 140L
        timing.sdkDecodedReceived(java.nio.ByteBuffer.wrap(byteArrayOf(0, 4)), 2)
        assertEquals(listOf(100L, 120L, 140L), points.map { it.atMs })
        assertTrue(points.all { it.sequence == 1L })
        timing.outputComplete(sdkState = true)
        clock = 200L
        timing.sdkDecodedReceived(java.nio.ByteBuffer.wrap(byteArrayOf(0, 4)), 2)
        // An audio callback can beat the state event without splitting one output.
        timing.sdkOutputStarted()
        assertEquals(2L, points.last().sequence)
    }

    @Test fun diagnosticWriterIsDispatchedOnceAndNeverRunsOnOffer() {
        val dispatched = ArrayList<() -> Unit>()
        val written = ArrayList<AudioTurnTiming.Point>()
        val log = AudioTimingLog(dispatched::add, written::add)
        val timing = AudioTurnTiming({ 15L }, log::offer)
        timing.sdkDecodedReceived(java.nio.ByteBuffer.wrap(byteArrayOf(0, 4)), 2)
        assertTrue(written.isEmpty())
        assertEquals(1, dispatched.size)
        dispatched.removeAt(0).invoke()
        assertEquals(listOf("sdk_decoded_received_not_audible", "sdk_non_silent_decoded_received_not_audible"), written.map { it.endpoint })
        // Receipt time is captured on the producer, not delayed until the logger runs.
        assertTrue(written.all { it.atMs == 15L })
        timing.outputComplete(sdkState = true)
        assertEquals(1, dispatched.size)
        dispatched.removeAt(0).invoke()
        assertEquals("sdk_output_state_ended_received", written.last().endpoint)
    }

    @Test fun stalledDiagnosticWriterHasBoundedBacklogAndReportsMissingPoints() {
        val dispatched = ArrayList<() -> Unit>()
        val written = ArrayList<AudioTurnTiming.Point>()
        val dropped = ArrayList<Int>()
        val log = AudioTimingLog(dispatched::add, written::add, dropped::add, capacity = 3)
        repeat(1000) { log.offer(AudioTurnTiming.Point(it.toLong(), "observation", it.toLong())) }
        assertTrue(written.isEmpty())
        assertEquals(1, dispatched.size)
        dispatched.single().invoke()
        assertEquals(listOf(997L, 998L, 999L), written.map { it.sequence })
        assertEquals(listOf(997), dropped)
    }

    @Test fun failedDiagnosticWriteDoesNotBlockFollowingObservationsOrFutureDrains() {
        val dispatched = ArrayList<() -> Unit>()
        val written = ArrayList<Long>()
        val log = AudioTimingLog(dispatched::add, { point ->
            if (point.sequence == 1L) throw IllegalStateException("fixture logger failure")
            written.add(point.sequence)
        })
        log.offer(AudioTurnTiming.Point(1, "first", 0))
        log.offer(AudioTurnTiming.Point(2, "second", 1))
        dispatched.removeAt(0).invoke()
        log.offer(AudioTurnTiming.Point(3, "third", 2))
        dispatched.removeAt(0).invoke()
        assertEquals(listOf(2L, 3L), written)
    }

    @Test fun diagnosticWriterDoesNotHoldTheProducerLock() {
        val dispatched = ArrayList<() -> Unit>()
        val written = ArrayList<Long>()
        lateinit var log: AudioTimingLog
        log = AudioTimingLog(dispatched::add, { point ->
            written.add(point.sequence)
            if (point.sequence == 1L) {
                val producer = Thread { log.offer(AudioTurnTiming.Point(2, "second", 1)) }
                producer.start()
                producer.join(1000)
                assertFalse("Audio producer waited for diagnostic I/O", producer.isAlive)
            }
        })
        log.offer(AudioTurnTiming.Point(1, "first", 0))
        dispatched.removeAt(0).invoke()
        assertEquals(listOf(1L, 2L), written)
        assertTrue(dispatched.isEmpty())
    }

}
