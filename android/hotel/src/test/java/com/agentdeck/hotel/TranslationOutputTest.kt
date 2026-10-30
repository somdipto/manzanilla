package com.agentdeck.hotel

import org.junit.Assert.*
import org.junit.Test

class TranslationOutputTest {
    @Test fun announcementRequiredAndTerminalOutputNeverReplays() {
        val output = TranslationOutput()
        assertFalse(output.plays(1)) // Track arrived before reliable announcement.
        assertTrue(output.accept(1, "queued"))
        assertTrue(output.plays(1))
        output.accept(1, "playing")
        output.accept(1, "interrupted")
        assertFalse(output.plays(1))
        assertTrue(output.stale(1))
        output.accept(1, "playing") // A late record cannot resurrect the cancelled track.
        assertFalse(output.plays(1))
        output.accept(2, "queued")
        assertTrue(output.plays(2))
        assertFalse(output.accept(1, "complete"))
        assertTrue(output.plays(2))
        output.accept(2, "error")
        assertFalse(output.plays(2))
        assertFalse(output.accept(0, "playing"))
    }

    @Test fun newSessionHasNoOutputFromOldSession() {
        val previous = TranslationOutput()
        previous.accept(42, "playing")
        val next = TranslationOutput()
        assertFalse(next.plays(42))
        next.accept(1, "queued")
        assertFalse(next.plays(42))
        assertTrue(next.plays(1))
    }
}
