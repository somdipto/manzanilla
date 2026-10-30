package com.agentdeck.hotel

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FishCleanupCredentialTest {
    @Test fun restartResolvesOriginalCredentialOnlyForItsReservationAndHash() {
        val hash = FishCleanupCredential.hash("original")
        val persisted = FishCleanupCredential.encode("request-a", hash, "original")
        assertEquals("original", FishCleanupCredential.resolve(persisted, "request-a", hash))
        assertNull(FishCleanupCredential.resolve(persisted, "request-b", hash))
        assertNull(FishCleanupCredential.resolve(persisted, "request-a", FishCleanupCredential.hash("replacement")))
        assertNull(FishCleanupCredential.resolve(JSONObject(persisted!!).put("credential", "replacement").toString(), "request-a", hash))
    }

    @Test fun historicalRecoveryRequiresExactOriginalAndNeverInfersMissingProof() {
        val hash = FishCleanupCredential.hash("original")
        assertNull(FishCleanupCredential.encode("request-a", hash, "replacement"))
        assertNull(FishCleanupCredential.encode("request-a", null, "original"))
        assertNull(FishCleanupCredential.resolve(null, "request-a", hash))
        assertNull(FishCleanupCredential.resolve("damaged", "request-a", hash))
        assertNotNull(FishCleanupCredential.encode("request-a", hash, "original"))
    }

    @Test fun cleanupAuthorityCannotBeRedirectedToCreationOrAnotherReservationLookup() {
        assertTrue(FishCleanupCredential.isDirect("request-a", FishCleanupCredential.lookup("request-a"), true))
        assertFalse(FishCleanupCredential.isDirect("request-b", FishCleanupCredential.lookup("request-a"), true))
        assertFalse(FishCleanupCredential.isDirect("request-a", "https://api.fish.audio/v1/agent/sessions", false))
        assertFalse(FishCleanupCredential.isDirect("request-a", "https://other.invalid/v1/agent/sessions/one/end", false))
        assertTrue(FishCleanupCredential.isDirect("request-a", "https://api.fish.audio/v1/agent/sessions/one/end", false))
    }
}
