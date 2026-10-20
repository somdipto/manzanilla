package com.agentdeck.hotel

import org.junit.Assert.*
import org.junit.Test

class FishSessionAdmissionTest {
    @Test fun localReleaseDoesNotAdmitAnotherPaidSessionUntilRemoteAcknowledgement() {
        var disk: String? = null
        val gate = FishSessionAdmission(null) { disk = it; true }
        assertTrue(gate.acquire("old"))
        gate.requested("old")
        gate.ending("old")
        gate.localReleased("old", true)
        assertEquals("old", disk)
        assertFalse(gate.acquire("new"))
        gate.remoteEnded("old", true)
        assertNull(disk)
        assertTrue(gate.acquire("new"))
        gate.remoteEnded("old", true)
        gate.localReleased("old", true)
        assertEquals("new", disk)
        assertFalse(gate.acquire("third"))
    }
    @Test fun failedEndAndUncertainCreationRemainOwnedAcrossActivityAndProcessRestart() {
        var disk: String? = null
        val gate = FishSessionAdmission(null) { disk = it; true }
        assertTrue(gate.acquire("uncertain"))
        gate.requested("uncertain")
        gate.localReleased("uncertain", true)
        gate.remoteEnded("uncertain", false)
        assertFalse(gate.acquire("new"))
        val restarted = FishSessionAdmission(disk) { disk = it; true }
        assertFalse(restarted.acquire("new"))
        assertNotNull(restarted.reason())
        gate.ending("uncertain")
        gate.remoteEnded("uncertain", true)
        assertNull(disk)
    }
    @Test fun remoteAcknowledgementDoesNotConcealFailedMicrophoneRelease() {
        val gate = FishSessionAdmission(null) { true }
        assertTrue(gate.acquire("old"))
        gate.requested("old")
        gate.localReleased("old", false)
        gate.remoteEnded("old", true)
        assertFalse(gate.acquire("new"))
        gate.localReleased("old", true)
        assertTrue(gate.acquire("new"))
    }
    @Test fun noProviderAttemptCanReleaseReservationButPersistenceFailureFailsClosed() {
        var writable = false
        val gate = FishSessionAdmission(null) { writable }
        assertFalse(gate.acquire("old"))
        writable = true
        assertTrue(gate.acquire("old"))
        writable = false
        gate.localReleased("old", true)
        assertFalse(gate.acquire("new"))
        writable = true
        gate.localReleased("old", true)
        assertTrue(gate.acquire("new"))
    }
    @Test fun recoveredEndIsSingleFlightAndCannotReleaseANewerReservation() {
        var disk: String? = "old"
        val gate = FishSessionAdmission(disk) { disk = it; true }
        assertEquals("old", gate.recoveryReservation())
        assertTrue(gate.beginRecovery("old"))
        assertFalse(gate.beginRecovery("old"))
        assertFalse(gate.acquire("new"))
        gate.remoteEnded("old", false)
        assertEquals("old", gate.recoveryReservation())
        assertTrue(gate.beginRecovery("old"))
        gate.remoteEnded("old", true)
        assertNull(disk)
        assertTrue(gate.acquire("new"))
        gate.remoteEnded("old", true)
        assertEquals("new", disk)
    }
    @Test fun knownBudgetRejectionReleasesOnlyAfterLocalOffButAmbiguousFailuresStayOwned() {
        val gate = FishSessionAdmission(null) { true }
        assertTrue(gate.acquire("budget"))
        gate.requested("budget")
        assertTrue(gate.rejected("budget", managed = true, status = 429))
        assertEquals("unknown", gate.localAudioState())
        assertFalse(gate.acquire("new"))
        gate.localReleased("budget", true)
        assertEquals("off", gate.localAudioState())
        assertTrue(gate.acquire("new"))
        gate.requested("new")
        gate.localReleased("new", true)
        for (status in listOf(401, 409, 500, 503)) assertFalse(gate.rejected("new", managed = true, status = status))
        assertFalse(gate.acquire("third"))
    }
    @Test fun restoredBeforeDispatchCanReleaseAndOldCompletionCannotClearNewOwner() {
        var disk: String? = "before-dispatch"
        val gate = FishSessionAdmission(disk) { disk = it; true }
        assertEquals("before-dispatch", gate.recoveryReservation())
        assertTrue(gate.releaseRestoredBeforeDispatch("before-dispatch"))
        assertNull(disk)
        assertNull(gate.reason())
        assertTrue(gate.acquire("new"))
        assertFalse(gate.releaseRestoredBeforeDispatch("before-dispatch"))
        gate.remoteEnded("before-dispatch", true)
        assertEquals("new", disk)
    }
    @Test fun livePredispatchAgentSetupIsNotProcessDeathRecovery() {
        var disk: String? = null
        val gate = FishSessionAdmission(null) { disk = it; true }
        assertTrue(gate.acquire("live-agent-setup"))
        assertNull(gate.recoveryReservation())
        assertFalse(gate.releaseRestoredBeforeDispatch("live-agent-setup"))
        assertEquals("live-agent-setup", disk)
        assertFalse(gate.acquire("competing"))
    }
    @Test fun failedRestoredPredispatchClearRemainsBlockedAndCanRetryStorage() {
        var disk: String? = "before-dispatch"
        var writable = false
        val gate = FishSessionAdmission(disk) { if (writable) { disk = it; true } else false }
        assertFalse(gate.releaseRestoredBeforeDispatch("before-dispatch"))
        assertEquals("before-dispatch", disk)
        assertNotNull(gate.reason())
        assertEquals("off", gate.localAudioState())
        assertFalse(gate.acquire("new"))
        assertEquals("before-dispatch", gate.recoveryReservation())
        writable = true
        assertTrue(gate.releaseRestoredBeforeDispatch("before-dispatch"))
        assertNull(disk)
        assertTrue(gate.acquire("new"))
    }
    @Test fun receivedAuthoritativeManagedRejectionReleasesOnlyAfterLocalOff() {
        var disk: String? = null
        val gate = FishSessionAdmission(null) { disk = it; true }
        assertTrue(gate.acquire("rejected"))
        gate.requested("rejected")
        assertTrue(gate.rejected("rejected", managed = true, status = 503, creationNotCreated = true))
        assertEquals("rejected", disk)
        assertFalse(gate.acquire("new"))
        gate.localReleased("rejected", true)
        assertNull(disk)
        assertTrue(gate.acquire("new"))
        assertFalse(gate.rejected("rejected", managed = true, status = 503, creationNotCreated = true))
        assertEquals("new", disk)
    }
    @Test fun ambiguousManaged503AndDirectHeaderCannotReleasePaidOwnership() {
        for (managed in listOf(true, false)) {
            var disk: String? = null
            val gate = FishSessionAdmission(null) { disk = it; true }
            assertTrue(gate.acquire("dispatched"))
            gate.requested("dispatched")
            gate.localReleased("dispatched", true)
            assertFalse(gate.rejected("dispatched", managed = managed, status = 503, creationNotCreated = !managed))
            gate.retrySettledRelease()
            assertEquals("dispatched", disk)
            assertFalse(gate.acquire("new"))
            val restored = FishSessionAdmission(disk) { disk = it; true }
            assertEquals("dispatched", restored.recoveryReservation())
            assertFalse(restored.acquire("new"))
        }
    }
    @Test fun authoritativeRejectionStillRequiresConfirmedAudioAndPersistentRelease() {
        var writable = true
        val gate = FishSessionAdmission(null) { writable }
        assertTrue(gate.acquire("rejected"))
        gate.requested("rejected")
        gate.localReleased("rejected", false)
        assertTrue(gate.rejected("rejected", managed = true, status = 503, creationNotCreated = true))
        assertEquals("unknown", gate.localAudioState())
        assertFalse(gate.acquire("new"))
        writable = false
        gate.localReleased("rejected", true)
        assertFalse(gate.acquire("new"))
        writable = true
        gate.localReleased("rejected", true)
        assertTrue(gate.acquire("new"))
    }
    @Test fun documentedDirectCreationRefusalsAllowANewOwnerAfterLocalRelease() {
        for (status in listOf(404, 415)) {
            var disk: String? = null
            val gate = FishSessionAdmission(null) { disk = it; true }
            assertTrue(gate.acquire("rejected"))
            gate.requested("rejected")
            assertTrue(gate.rejected("rejected", managed = false, status = status))
            assertFalse(gate.acquire("too-early"))
            gate.localReleased("rejected", true)
            assertNull(disk)
            assertTrue(gate.acquire("next"))
        }
    }
    @Test fun confirmedEndCanRetryStorageWithoutClearingANewerOwner() {
        var writable = true
        var disk: String? = null
        val gate = FishSessionAdmission(null) { id -> if (writable) { disk = id; true } else false }
        assertTrue(gate.acquire("settled"))
        gate.requested("settled")
        gate.localReleased("settled", true)
        writable = false
        gate.remoteEnded("settled", true)
        gate.retrySettledRelease()
        assertEquals("settled", disk)
        assertFalse(gate.acquire("too-early"))
        writable = true
        gate.retrySettledRelease()
        assertNull(disk)
        assertTrue(gate.acquire("next"))
        gate.remoteEnded("settled", true)
        gate.retrySettledRelease()
        assertEquals("next", disk)
        assertFalse(gate.acquire("competing"))
    }
}
