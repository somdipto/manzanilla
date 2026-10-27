package com.agentdeck.hotel

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VoiceDiagnosticsTest {
    @Before fun reset() = VoiceDiagnostics.clear()

    private fun point(endpoint: String, atMs: Long, sequence: Long = 1) =
        AudioTurnTiming.Point(sequence, endpoint, atMs)

    // Event lines carry a relative timestamp; the legend line mentions [overlap] without one.
    private fun flaggedEventLines(text: String) =
        text.lines().filter { it.contains("[overlap]") && it.contains(" ms  ") }

    @Test fun inputDuringAgentOutputIsFlaggedAsOverlap() {
        val s = "s1"
        VoiceDiagnostics.record(s, 1, point("run_started_requested_config_rev_test", 0, 0))
        VoiceDiagnostics.record(s, 1, point("raw_pcm_received", 1000))
        VoiceDiagnostics.record(s, 1, point("input_activity_started", 1500))
        VoiceDiagnostics.record(s, 1, point("provider_input_final_received", 2100))
        VoiceDiagnostics.record(s, 1, point("provider_output_turn_complete_received", 3000))
        VoiceDiagnostics.record(s, 1, point("input_activity_started", 4000, 2))

        val text = VoiceDiagnostics.render()
        val lines = text.lines()
        val flagged = flaggedEventLines(text)
        assertEquals(2, flagged.size)
        assertTrue(flagged[0].contains("input_activity_started"))
        assertTrue(flagged[1].contains("provider_input_final_received"))
        val cleanInputs = lines.filter { it.contains("input_activity_started") && !it.contains("[overlap]") }
        assertEquals(1, cleanInputs.size)
        assertTrue(cleanInputs[0].contains("+004000"))
        assertTrue(text.contains("overlap events: 2"))
    }

    @Test fun interruptionEndsTheOverlapWindow() {
        val s = "s2"
        VoiceDiagnostics.record(s, 1, point("sdk_output_state_started_received", 1000))
        VoiceDiagnostics.record(s, 1, point("input_activity_started", 1200))
        VoiceDiagnostics.record(s, 1, point("output_interrupted_received", 1600))
        VoiceDiagnostics.record(s, 1, point("input_activity_started", 2000, 2))

        val text = VoiceDiagnostics.render()
        val lines = text.lines()
        val flagged = flaggedEventLines(text)
        assertEquals(1, flagged.size)
        assertTrue(flagged[0].contains("+000200"))
        assertTrue(lines.none { it.contains("+001000") && it.contains("[overlap]") })
    }

    @Test fun noOverlapWithoutAgentOutput() {
        val s = "s3"
        VoiceDiagnostics.record(s, 1, point("input_activity_started", 500))
        VoiceDiagnostics.record(s, 1, point("input_speech_boundary_estimate", 900))

        val text = VoiceDiagnostics.render()
        assertTrue(flaggedEventLines(text).isEmpty())
        assertFalse(text.contains("overlap events:"))
    }
}
