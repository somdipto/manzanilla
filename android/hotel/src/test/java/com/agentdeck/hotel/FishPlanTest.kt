package com.agentdeck.hotel

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class FishPlanTest {
    private fun captionDefinition() = JSONObject("""{
        "name":"caption","tool_type":"client","description":"Display-only captions",
        "expects_response":false,"arguments":[
            {"name":"source_language","description":"Selected code"},
            {"name":"original","description":"Exact original"},
            {"name":"translation","description":"Exact translation"}
        ]
    }""")

    @Test fun clientCaptionReadbackAcceptsWebhookDefaultsAndServerGeneratedFields() {
        val expected = captionDefinition()
        for (mode in listOf(null, "blocking", "fire_and_forget")) {
            val actual = JSONObject(expected.toString()).put("tool_id", "provider-id")
                .put("created_at", "server-time").put("execution_mode", mode)
                .put("timeout", 30)
            actual.getJSONArray("arguments").getJSONObject(0).put("required", false)
            assertTrue(FishPlan.captionToolMatches(expected, actual))
            assertTrue(FishPlan.captionToolMatches(JSONObject(expected.toString())
                .put("execution_mode", "fire_and_forget"), actual))
        }
    }

    @Test fun existingCaptionToolMustMatchEveryRequiredValueAndOrderedArgument() {
        val expected = captionDefinition()
        for (field in listOf("name", "tool_type", "description", "expects_response", "arguments")) {
            val actual = JSONObject(expected.toString()).apply { remove(field) }
            assertFalse(field, FishPlan.captionToolMatches(expected, actual))
            assertFalse(field, FishPlan.captionToolMatches(actual, expected))
        }
        for ((field, value) in listOf("name" to "other", "tool_type" to "webhook",
            "description" to "Changed policy", "expects_response" to true,
            "expects_response" to "false", "expects_response" to 0)) {
            assertFalse(field, FishPlan.captionToolMatches(expected, JSONObject(expected.toString()).put(field, value)))
        }
        for (field in listOf("name", "description")) {
            val actual = JSONObject(expected.toString())
            actual.getJSONArray("arguments").getJSONObject(1).put(field, "Changed argument")
            assertFalse(field, FishPlan.captionToolMatches(expected, actual))
            actual.getJSONArray("arguments").getJSONObject(1).remove(field)
            assertFalse(field, FishPlan.captionToolMatches(expected, actual))
        }
        val fewer = JSONObject(expected.toString())
        fewer.getJSONArray("arguments").remove(2)
        assertFalse(FishPlan.captionToolMatches(expected, fewer))
        val extra = JSONObject(expected.toString())
        extra.getJSONArray("arguments").put(JSONObject("""{"name":"action","description":"Not a caption"}"""))
        assertFalse(FishPlan.captionToolMatches(expected, extra))
        val reordered = JSONObject(expected.toString())
        val arguments = reordered.getJSONArray("arguments")
        val original = arguments.get(1)
        arguments.put(1, arguments.get(2)).put(2, original)
        assertFalse(FishPlan.captionToolMatches(expected, reordered))
    }

    @Test fun matchingMalformedDefinitionsAreNotReusableContracts() {
        for (field in listOf("name", "tool_type", "description", "expects_response")) {
            val malformed = captionDefinition().put(field, 0)
            assertFalse(field, FishPlan.captionToolMatches(malformed, JSONObject(malformed.toString())))
        }
        for (field in listOf("name", "description")) {
            val malformed = captionDefinition()
            malformed.getJSONArray("arguments").getJSONObject(0).put(field, 0)
            assertFalse(field, FishPlan.captionToolMatches(malformed, JSONObject(malformed.toString())))
        }
    }

    @Test fun onlyClientExecutionModeIsIgnoredAndOtherExpectedFieldsRemainRequired() {
        val expected = captionDefinition().put("tool_type", "webhook").put("execution_mode", "fire_and_forget")
        assertFalse(FishPlan.captionToolMatches(expected, JSONObject(expected.toString()).put("execution_mode", "blocking")))
        assertFalse(FishPlan.captionToolMatches(captionDefinition().put("tool_type", "webhook"), expected))
        val client = captionDefinition()
        client.getJSONArray("arguments").getJSONObject(0).put("required", true)
        val actual = JSONObject(client.toString())
        actual.getJSONArray("arguments").getJSONObject(0).put("required", false)
        assertFalse(FishPlan.captionToolMatches(client, actual))
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
