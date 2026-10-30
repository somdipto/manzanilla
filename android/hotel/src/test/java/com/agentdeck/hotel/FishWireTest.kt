package com.agentdeck.hotel

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FishWireTest {
    @Test fun recoveryUsesErrorTypeNeverNumericCode() {
        fun error(type: String, recoverable: Boolean = true) = JSONObject().put("type", "error")
            .put("error_type", type).put("code", 503).put("recoverable", recoverable)
        assertTrue(FishWire.recoverable(error("engine_error")))
        assertTrue(FishWire.recoverable(error("invalid_request")))
        assertFalse(FishWire.recoverable(error("server_restarting")))
        assertFalse(FishWire.recoverable(error("engine_error", false)))
        assertFalse(FishWire.recoverable(error("unknown")))
        assertFalse(FishWire.recoverable(JSONObject().put("code", "engine_error").put("recoverable", true)))
    }

    @Test fun acceptsOnlyTheConfiguredModelAndItsExactResourcePrefix() {
        assertTrue(FishWire.matchesModel("gemini-3.8-flash"))
        assertTrue(FishWire.matchesModel("models/gemini-3.8-flash"))
        for (id in listOf("gemini-3.8-flash-preview", "models/models/gemini-3.8-flash", "gemini-2.5-flash", " gemini-3.8-flash", "models/gemini-3.8-flash/"))
            assertFalse(FishWire.matchesModel(id))
    }
}
