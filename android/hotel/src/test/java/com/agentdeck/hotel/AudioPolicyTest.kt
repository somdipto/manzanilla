package com.agentdeck.hotel

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AudioPolicyTest {
    @Test fun unreadableSavedKeyIsNotReportedAsMissingSetup() {
        val missing = AudioPolicy.blockReason(false, SecretStore.Status.ABSENT, false)
        val unreadable = AudioPolicy.blockReason(false, SecretStore.Status.ERROR, false)
        assertTrue(missing.contains("One-time setup"))
        assertTrue(unreadable.contains("saved voice key could not be opened"))
        assertNotEquals(missing, unreadable)
        assertEquals("", AudioPolicy.blockReason(false, SecretStore.Status.READY, false))
        assertEquals("", AudioPolicy.blockReason(true, SecretStore.Status.ERROR, false))
    }

    @Test fun approvedFishRouteNeverBlocksStart() {
        var blocked: String? = null
        assertTrue(AudioPolicy.LIVE_AUDIO_ENABLED)
        assertTrue(AudioPolicy.allowStart { blocked = it })
        assertNull(blocked)
    }
}
