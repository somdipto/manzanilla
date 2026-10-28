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

    @Test fun bundledCredentialAllowsDirectTranslationWithoutADeviceKey() {
        assertEquals("", AudioPolicy.blockReason(false, SecretStore.Status.ABSENT, true))
        assertEquals("", AudioPolicy.blockReason(false, SecretStore.Status.ERROR, true))
        assertEquals("", AudioPolicy.blockReason(true, SecretStore.Status.ABSENT, true,
            directFishSelected = true))
    }

    @Test fun directSelectionNeverTreatsSavedHotelConnectionAsAUsableFishKey() {
        assertEquals("", AudioPolicy.blockReason(true, SecretStore.Status.ABSENT, false))
        assertTrue(AudioPolicy.blockReason(true, SecretStore.Status.ABSENT, false,
            directFishSelected = true).contains("One-time setup"))
        assertTrue(AudioPolicy.blockReason(true, SecretStore.Status.ERROR, false,
            directFishSelected = true).contains("saved voice key could not be opened"))
        assertEquals("", AudioPolicy.blockReason(true, SecretStore.Status.READY, false,
            directFishSelected = true))
    }
}
