package com.agentdeck.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReceptionBridgeTest {
    @Test fun translationSetupActionReachesTheAuthenticatedNativeScreen() {
        assertNotNull(ReceptionBridge.decode("{\"action\":\"voice_setup\"}"))
    }
    private fun note(character: String): JSONObject = JSONObject()
        .put("action", "message")
        .put("session_id", "00000000-0000-4000-8000-000000000001")
        .put("generation", 1)
        .put("arguments", JSONObject()
            .put("original_request", character.repeat(4000))
            .put("guest_name", character.repeat(100))
            .put("room_number", character.repeat(20)))

    @Test fun maximumBackslashNoteReachesNativeReviewWithoutLosingText() {
        val request = note("\\")
        val raw = request.toString()
        assertTrue("Reproduces the previous bridge rejection", raw.length > 8192)
        val decoded = ReceptionBridge.decode(raw)
        assertNotNull(decoded)
        assertEquals("message", decoded!!.getString("action"))
        assertEquals("\\".repeat(4000), decoded.getJSONObject("arguments").getString("original_request"))
        assertEquals("\\".repeat(100), decoded.getJSONObject("arguments").getString("guest_name"))
        assertEquals("\\".repeat(20), decoded.getJSONObject("arguments").getString("room_number"))
    }

    @Test fun maximumNoteFitsEvenWithSixCharacterJsonEscapes() {
        val raw = note("\u0001").toString()
        assertTrue(raw.contains("\\u0001"))
        assertTrue(raw.length >= 4120 * 6)
        assertTrue(raw.length <= ReceptionBridge.MAX_MESSAGE_CHARS)
        val decoded = ReceptionBridge.decode(raw)
        assertNotNull(decoded)
        assertEquals("\u0001".repeat(4000), decoded!!.getJSONObject("arguments").getString("original_request"))
    }

    @Test fun serializedRequestLimitRemainsBoundedAndInclusive() {
        val request = JSONObject().put("action", "info").put("padding", "")
        val extra = ReceptionBridge.MAX_MESSAGE_CHARS - request.toString().length
        val raw = request.put("padding", "x".repeat(extra)).toString()
        assertEquals(ReceptionBridge.MAX_MESSAGE_CHARS, raw.length)
        assertNotNull(ReceptionBridge.decode(raw))
        assertNull(ReceptionBridge.decode(request.put("padding", "x".repeat(extra + 1)).toString()))
    }

    @Test fun malformedOrUnknownActionsStillCannotEnterTheNativeBridge() {
        assertNull(ReceptionBridge.decode("not-json"))
        assertNull(ReceptionBridge.decode("{}"))
        assertNull(ReceptionBridge.decode("{\"action\":\"execute_script\"}"))
        assertNotNull(ReceptionBridge.decode("{\"action\":\"confirm\",\"approved\":false}"))
    }
}
