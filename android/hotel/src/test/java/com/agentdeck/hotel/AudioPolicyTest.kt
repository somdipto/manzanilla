package com.agentdeck.hotel

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPolicyTest {
    @Test fun personalTranslationRequiresCompleteCredentialsWithoutManagedConnection() {
        for (direct in listOf(false, true)) {
            val reason = AudioPolicy.blockReason(false, false, direct, "personal-test")
            assertTrue(reason.contains("Fish Audio"))
            assertTrue(reason.contains("Gemini"))
            assertTrue(reason.contains("voice"))
            assertEquals("", AudioPolicy.blockReason(false, true, direct, "personal-test"))
        }
    }

    @Test fun approvedFishRouteNeverBlocksStart() {
        var blocked: String? = null
        assertTrue(AudioPolicy.LIVE_AUDIO_ENABLED)
        assertTrue(AudioPolicy.allowStart { blocked = it })
        assertNull(blocked)
    }

    @Test fun configuredManagedRouteDoesNotRequirePersonalCredentials() {
        assertEquals("", AudioPolicy.blockReason(true, false, false, "personal-test"))
        assertEquals("", AudioPolicy.blockReason(true, true, false, "personal-test"))
    }

    @Test fun directSelectionDoesNotTreatHotelEnrollmentAsCompletePersonalConfiguration() {
        assertTrue(AudioPolicy.blockReason(true, false, true, "personal-test").isNotBlank())
        assertEquals("", AudioPolicy.blockReason(true, true, true, "personal-test"))
    }

    @Test fun hotelNeedsEnrollmentRegardlessOfPersonalConfigurationOrPreference() {
        for (configured in listOf(false, true)) for (direct in listOf(false, true)) {
            val reason = AudioPolicy.blockReason(false, configured, direct, "hotel")
            assertTrue(reason.isNotBlank())
            assertFalse(reason.contains("Fish"))
            assertFalse(reason.contains("Settings"))
            assertEquals("", AudioPolicy.blockReason(true, configured, direct, "hotel"))
        }
    }

    @Test fun newStartRoutingHonorsDistributionAndPersonalPreference() {
        for (direct in listOf(false, true)) {
            assertFalse(TranslationRouting.useManagedConnection(false, direct, "hotel"))
            assertTrue(TranslationRouting.useManagedConnection(true, direct, "hotel"))
            assertFalse(TranslationRouting.useManagedConnection(false, direct, "personal-test"))
            assertEquals(!direct, TranslationRouting.useManagedConnection(true, direct, "personal-test"))
        }
    }
}
