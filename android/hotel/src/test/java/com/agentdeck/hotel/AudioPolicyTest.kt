package com.agentdeck.hotel

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AudioPolicyTest {
    @Test fun unreadableSavedKeyIsNotReportedAsMissingSetup() {
        val missing = AudioPolicy.blockReason(false, SecretStore.Status.ABSENT, false, false, "personal-test")
        val unreadable = AudioPolicy.blockReason(false, SecretStore.Status.ERROR, false, false, "personal-test")
        assertTrue(missing.contains("One-time setup"))
        assertTrue(unreadable.contains("saved voice key could not be opened"))
        assertNotEquals(missing, unreadable)
        assertEquals("", AudioPolicy.blockReason(false, SecretStore.Status.READY, false, false, "personal-test"))
        assertEquals("", AudioPolicy.blockReason(true, SecretStore.Status.ERROR, false, false, "personal-test"))
    }

    @Test fun approvedFishRouteNeverBlocksStart() {
        var blocked: String? = null
        assertTrue(AudioPolicy.LIVE_AUDIO_ENABLED)
        assertTrue(AudioPolicy.allowStart { blocked = it })
        assertNull(blocked)
    }

    @Test fun bundledCredentialAllowsPersonalTranslationWithoutADeviceKey() {
        for (status in SecretStore.Status.values()) {
            assertEquals("", AudioPolicy.blockReason(false, status, true, false, "personal-test"))
            assertEquals("", AudioPolicy.blockReason(true, status, true, true, "personal-test"))
        }
    }

    @Test fun directSelectionNeverTreatsSavedHotelConnectionAsAUsableFishKey() {
        assertEquals("", AudioPolicy.blockReason(true, SecretStore.Status.ABSENT, false, false, "personal-test"))
        assertTrue(AudioPolicy.blockReason(true, SecretStore.Status.ABSENT, false, true, "personal-test").contains("One-time setup"))
        assertTrue(AudioPolicy.blockReason(true, SecretStore.Status.ERROR, false, true, "personal-test").contains("saved voice key could not be opened"))
        assertEquals("", AudioPolicy.blockReason(true, SecretStore.Status.READY, false, true, "personal-test"))
    }

    @Test fun hotelNeedsEnrollmentRegardlessOfPersonalKeyOrPreference() {
        for (status in SecretStore.Status.values()) for (bundled in listOf(false, true)) for (direct in listOf(false, true)) {
            val reason = AudioPolicy.blockReason(false, status, bundled, direct, "hotel")
            assertTrue(reason.isNotBlank())
            assertFalse(reason.contains("Fish"))
            assertFalse(reason.contains("Settings"))
            assertEquals("", AudioPolicy.blockReason(true, status, bundled, direct, "hotel"))
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
