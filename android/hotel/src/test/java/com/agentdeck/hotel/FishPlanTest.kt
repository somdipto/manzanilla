package com.agentdeck.hotel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FishPlanTest {
    @Test fun sessionDoesNotPinLanguageAndDisablesRecording() {
        val body = FishPlan.sessionBody("agent-1", "fr", "es")
        assertEquals("agent-1", body.getString("agent_id"))
        assertEquals("Europe/Madrid", body.getString("timezone"))
        assertFalse(body.getBoolean("record_audio"))
        assertFalse(body.getBoolean("tool_events"))
        assertFalse(body.has("language"))
    }

    @Test fun agentConfigIsMultilingualInterpreterNotOpenRecording() {
        val config = FishPlan.agentConfig("voice-1")
        assertEquals("voice-1", config.getJSONObject("voice").getString("voice_id"))
        assertEquals("es", config.getJSONObject("voice").getString("speaking_language"))
        assertFalse(config.getJSONObject("voice").getBoolean("expressive"))
        assertEquals("deepgram:nova-3", config.getJSONObject("asr").getString("model"))
        assertTrue(config.getJSONObject("asr").getBoolean("multilingual"))
        assertFalse(config.getJSONObject("asr").getBoolean("strict_language"))
        val talk = config.getJSONObject("conversation")
        assertEquals(600, talk.getInt("max_duration_seconds"))
        assertFalse(talk.getBoolean("record_audio"))
        assertEquals("Europe/Madrid", talk.getString("timezone"))
        assertTrue(talk.getBoolean("interruptible"))
    }

    @Test fun releaseTagMustBeatInstalledTenOhTwo() {
        assertTrue(ReleaseVersion.newer("10.0.3", "10.0.2"))
        assertFalse(ReleaseVersion.newer("10.0.2", "10.0.2"))
        assertFalse(ReleaseVersion.newer("10", "10.0.2"))
        assertTrue(ReleaseVersion.newer("17", "10.0.2"))
        assertFalse(ReleaseVersion.offer("17", "10.0.3", 17))
        assertTrue(ReleaseVersion.offer("18", "10.0.3", 17))
         assertTrue(ReleaseVersion.offer("10.0.3", "10.0.2", 12))
         assertTrue(ReleaseVersion.offer("17.0.1", "10.0.7", 17))
         assertFalse(ReleaseVersion.offer("17.0.1", "17.0.1", 18))
    }


    @Test fun sideFollowsTheSelectedPairNotAFixedLanguage() {
        assertEquals("guest", PairSide.side("Where is the breakfast room?", "en", "es", "staff"))
        assertEquals("staff", PairSide.side("¿Dónde está el desayuno?", "en", "es", "guest"))
        assertEquals("guest", PairSide.side("部屋はどこですか", "ja", "en", "staff"))
        assertEquals("staff", PairSide.side("Wo ist das Zimmer bitte?", "fr", "de", "guest"))
        assertEquals("staff", PairSide.side("hmm", "en", "ar", "staff"))
        assertEquals("staff", PairSide.side("bonjour", "en", "fr", "guest", "fr"))
    }

}
