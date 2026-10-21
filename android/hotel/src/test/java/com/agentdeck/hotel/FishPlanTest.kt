package com.agentdeck.hotel

import org.junit.Assert.*
import org.junit.Test

class FishPlanTest {
    @Test fun conversationConfigRefreshesLegacyCacheAndUsesDocumentedVoiceSettings() {
        assertNotEquals("8", FishPlan.CONFIG_REV)
        val config = FishPlan.agentConfig("synthetic-voice", "bn")
        assertEquals("bn", config.getJSONObject("voice").getString("speaking_language"))
        assertEquals("s2.1-pro", config.getJSONObject("voice").getString("tts_model"))
        assertTrue(config.getJSONObject("voice").getBoolean("expressive"))
        assertEquals(300, config.getJSONObject("conversation").getInt("response_wait_ms"))
        assertEquals(600, config.getJSONObject("conversation").getInt("response_max_wait_ms"))
        assertTrue(config.getJSONObject("conversation").getBoolean("speculative_response"))
        assertFalse(config.getJSONObject("conversation").getBoolean("reengage_enabled"))
        assertTrue(config.getJSONObject("conversation").getBoolean("interruptible"))
        assertEquals("elevenlabs:scribe_v2_realtime", config.getJSONObject("asr").getString("model"))
        assertTrue(config.getJSONObject("asr").getBoolean("multilingual"))
    }
    @Test fun everyPickerLanguageIsSupportedWithoutEnglishFallback() {
        for (staff in DeskLanguages.codes) {
            val guest = if (staff == "en") "es" else "en"
            val config = FishPlan.agentConfig("synthetic", staff)
            assertEquals(staff, config.getJSONObject("voice").getString("speaking_language"))
            val session = FishPlan.sessionBody("synthetic", guest, staff, "Synthetic greeting")
            val overrides = session.getJSONObject("overrides")
            assertEquals(staff, overrides.getString("language"))
            assertEquals("Synthetic greeting", overrides.getString("first_message"))
            assertFalse(overrides.has("asr_model"))
            assertEquals(FishPlan.deviceTimezone(), session.getString("timezone"))
        }
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnsupportedStaff() {
        FishPlan.agentConfig("synthetic", "xx")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsSameLanguageSession() {
        FishPlan.sessionBody("synthetic", "es", "es", "")
    }
}
