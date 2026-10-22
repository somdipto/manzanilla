package com.agentdeck.hotel

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class FishPlanTest {
    @Test fun existingCaptionToolMustMatchTheDisplayOnlyContract() {
        val expected = JSONObject("""{"name":"caption","expects_response":false,"arguments":[{"name":"source_language","description":"Selected code"}]}""")
        val actual = JSONObject(expected.toString()).put("tool_id", "provider-id")
        assertTrue(FishPlan.captionToolMatches(expected, actual))
        actual.put("expects_response", true)
        assertFalse(FishPlan.captionToolMatches(expected, actual))
        actual.put("expects_response", false).getJSONArray("arguments").getJSONObject(0).put("name", "hotel_action")
        assertFalse(FishPlan.captionToolMatches(expected, actual))
    }
    @Test fun captionToolIsTheOnlyAllowedToolAndPromptReportsEveryUtterance() {
        val config = FishPlan.agentConfig("synthetic", "de", "caption-tool")
        assertTrue(config.getJSONObject("tools").getBoolean("enabled"))
        assertEquals("[\"caption-tool\"]", config.getJSONObject("tools").getJSONArray("tool_ids").toString())
        assertEquals("language-v2", FishPlan.CAPTION_PROTOCOL)
        assertNotEquals("13", FishPlan.CONFIG_REV)
        val prompt = FishPlan.prompt("fr", "de")
        assertTrue(prompt.contains("French (fr)"))
        assertTrue(prompt.contains("German (de)"))
        assertTrue(prompt.contains(FishPlan.CAPTION_TOOL))
        assertTrue(prompt.contains("source_language und"))
        assertTrue(prompt.contains("BEFORE calling any tool"))
        assertTrue(prompt.contains("After emitting the translation, call ${FishPlan.CAPTION_TOOL}"))
        assertTrue(prompt.contains("do not speak the translation a second time"))
        assertFalse(prompt.contains("BEFORE speaking"))
        assertTrue(config.getJSONObject("prompt").getString("system_prompt").contains(FishPlan.REPORT_POLICY))
        assertTrue(FishPlan.sessionBody("synthetic", "fr", "de", "Bonjour")
            .getJSONObject("overrides").getString("system_prompt").contains(FishPlan.REPORT_POLICY))
        assertTrue(prompt.contains("never perform hotel or device actions"))
    }
    @Test fun conversationConfigRefreshesLegacyCacheAndUsesDocumentedVoiceSettings() {
        assertNotEquals("11", FishPlan.CONFIG_REV)
        val config = FishPlan.agentConfig("synthetic-voice", "bn")
        assertEquals("bn", config.getJSONObject("voice").getString("speaking_language"))
        assertEquals("s2.1-pro", config.getJSONObject("voice").getString("tts_model"))
        assertFalse(config.getJSONObject("voice").getBoolean("expressive"))
        assertEquals(300, config.getJSONObject("conversation").getInt("response_wait_ms"))
        assertEquals(600, config.getJSONObject("conversation").getInt("response_max_wait_ms"))
        assertTrue(config.getJSONObject("conversation").getBoolean("speculative_response"))
        assertFalse(config.getJSONObject("conversation").getBoolean("reengage_enabled"))
        assertTrue(config.getJSONObject("conversation").getBoolean("interruptible"))
        assertFalse(config.getJSONObject("conversation").getJSONObject("echo_guard").getBoolean("web_voice"))
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
            assertEquals(FishPlan.recognitionModel(guest, staff), overrides.getJSONObject("asr").getString("model"))
            assertEquals(overrides.getJSONObject("asr").getString("model"), session.getJSONObject("metadata").getString("asr_model"))
            assertEquals(FishPlan.CONFIG_REV, session.getJSONObject("metadata").getString("manzanilla_config_rev"))
            assertEquals(FishPlan.deviceTimezone(), session.getString("timezone"))
        }
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnsupportedStaff() {
        FishPlan.agentConfig("synthetic", "xx")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsSameLanguageSession() {
        FishPlan.sessionBody("synthetic", "es", "es", "")
    }
    @Test fun fastRecognitionRequiresBothLanguagesAndPreservesEveryOtherPair() {
        // Pin Fish's documented hosted switching set. A broad monolingual support list must
        // never make Chinese/Korean/Arabic (or a mixed pair) use an incompatible recognizer.
        val switching = setOf("en", "es", "fr", "de", "hi", "ru", "pt", "ja", "it", "nl")
        for (guest in DeskLanguages.codes) for (staff in DeskLanguages.codes) {
            if (guest == staff) continue
            val expected = if (guest in switching && staff in switching) "deepgram:nova-3" else "elevenlabs:scribe_v2_realtime"
            val session = FishPlan.sessionBody("synthetic", guest, staff, "")
            assertEquals("$guest/$staff", expected, session.getJSONObject("overrides").getJSONObject("asr").getString("model"))
        }
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownLanguageBeforeChoosingRecognition() {
        FishPlan.recognitionModel("xx", "en")
    }
}
