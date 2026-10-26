package com.agentdeck.hotel

import org.junit.Assert.*
import org.junit.Test

class UpdateInstallPolicyTest {
    @Test fun anActiveConversationDefersInstallationWithoutConsumingTheAutomaticAttempt() {
        val policy = UpdateInstallPolicy()
        repeat(3) {
            assertEquals(UpdateInstallPolicy.Decision.DEFER, policy.reserve(idle = false, permitted = true))
            assertFalse(policy.reserved)
        }
        assertEquals(UpdateInstallPolicy.Decision.INSTALL, policy.reserve(idle = true, permitted = true))
        assertTrue(policy.reserved)
        assertEquals(UpdateInstallPolicy.Decision.IN_PROGRESS, policy.reserve(idle = true, permitted = true))
    }

    @Test fun returningWithInstallPermissionContinuesWithoutAnotherExplicitTap() {
        val policy = UpdateInstallPolicy()
        assertEquals(UpdateInstallPolicy.Decision.PERMISSION, policy.reserve(idle = true, permitted = false))
        assertFalse(policy.reserved)
        assertEquals(UpdateInstallPolicy.Decision.PERMISSION, policy.reserve(idle = true, permitted = false))
        assertEquals(UpdateInstallPolicy.Decision.INSTALL, policy.reserve(idle = true, permitted = true))
        assertTrue(policy.reserved)
    }

    @Test fun aFailureOrDismissedPromptNeverBecomesAnAutomaticRetryLoop() {
        val policy = UpdateInstallPolicy()
        assertEquals(UpdateInstallPolicy.Decision.INSTALL, policy.reserve(idle = true, permitted = true))
        policy.settled()
        repeat(5) {
            assertEquals(UpdateInstallPolicy.Decision.MANUAL_RETRY, policy.reserve(idle = true, permitted = true))
            assertFalse(policy.reserved)
        }
        assertEquals(UpdateInstallPolicy.Decision.INSTALL, policy.reserve(idle = true, permitted = true, explicit = true))
        assertTrue(policy.reserved)
        policy.settled()
        assertEquals(UpdateInstallPolicy.Decision.MANUAL_RETRY, policy.reserve(idle = true, permitted = true))
    }

    @Test fun explicitRetryCannotBypassBusyAdmissionAndCancelledDownloadCanBeReplaced() {
        val policy = UpdateInstallPolicy()
        assertEquals(UpdateInstallPolicy.Decision.DEFER, policy.reserve(idle = false, permitted = true, explicit = true))
        assertEquals(UpdateInstallPolicy.Decision.INSTALL, policy.reserve(idle = true, permitted = true))
        policy.reset()
        assertFalse(policy.reserved)
        assertEquals(UpdateInstallPolicy.Decision.DEFER, policy.reserve(idle = false, permitted = true))
        assertEquals(UpdateInstallPolicy.Decision.INSTALL, policy.reserve(idle = true, permitted = true))
    }

    @Test fun restartingTheAppDoesNotRepeatAnAlreadyDispatchedInstallation() {
        val restored = UpdateInstallPolicy()
        restored.restoreAttempted()
        assertEquals(UpdateInstallPolicy.Decision.MANUAL_RETRY, restored.reserve(idle = true, permitted = true))
        assertFalse(restored.reserved)
        assertEquals(UpdateInstallPolicy.Decision.INSTALL, restored.reserve(idle = true, permitted = true, explicit = true))
    }
}
