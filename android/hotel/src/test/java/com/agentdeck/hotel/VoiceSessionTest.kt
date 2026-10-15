package com.agentdeck.hotel

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch

class VoiceSessionTest {
    @Test fun captureWaitsForBothPrerequisitesInEitherOrderAndOpensOnce() {
        for (greetingFirst in listOf(true, false)) {
            var captures = 0
            val owner = VoiceSession("guest", 1, "en", "es", "translation") {}
            val open = { captures++; Unit }
            if (greetingFirst) owner.greeted(open) else owner.ready(open)
            assertEquals(0, captures)
            if (greetingFirst) owner.ready(open) else owner.greeted(open)
            owner.ready(open)
            owner.greeted(open)
            assertEquals(1, captures)
        }
    }

    @Test fun stoppingBeforeEitherReadyOrGreetingFencesLateCaptureAndPlayback() {
        for (greetingFirst in listOf(true, false)) {
            val events = ArrayList<JSONObject>()
            val owner = VoiceSession("old", 1, "en", "es", "translation", events::add)
            var captures = 0
            var playback = 0
            var acknowledged = false
            if (greetingFirst) owner.greeted { captures++ } else owner.ready { captures++ }
            assertTrue(owner.close { acknowledged = true })
            owner.ready { captures++ }
            owner.greeted { captures++ }
            owner.use { playback++ }
            owner.phase("speaking")
            assertEquals(0, captures)
            assertEquals(0, playback)
            assertTrue(events.isEmpty())
            assertFalse(acknowledged)
            owner.detached(true)
            assertTrue(acknowledged)
        }
    }

    @Test fun acknowledgementAndTerminalFollowResourceDetachAndAudioOffOnlyOnce() {
        val observed = ArrayList<String>()
        val owner = VoiceSession("old", 3, "en", "es", "translation") { event ->
            observed.add(if (event.optString("type") == "audio_state") "audio:${event.getString("state")}" else event.optString("phase", event.getString("type")))
        }
        assertTrue(owner.close { observed.add("ack:first") })
        assertFalse(owner.close { observed.add("ack:second") })
        assertTrue(observed.isEmpty())
        observed.add("native:released")
        owner.detached(true, JSONObject().put("type", "reception_event").put("phase", "timeout"))
        assertEquals(listOf("native:released", "audio:off", "energy", "timeout", "ack:first", "ack:second"), observed)
        owner.detached(true)
        assertEquals(6, observed.size)
        owner.close { observed.add("ack:late") }
        assertEquals("ack:late", observed.last())
    }

    @Test fun failedReleaseIsUnknownAndNeverFalselyAcknowledgesMicOff() {
        val states = ArrayList<String>()
        val owner = VoiceSession("guest", 1, "en", "es", "translation") {
            if (it.optString("type") == "audio_state") states.add(it.getString("state"))
        }
        owner.close { states.add("ack") }
        owner.detached(false)
        assertEquals(listOf("unknown", "ack"), states)
    }

    @Test fun delayedOldCallbackCannotOpenCaptureOrPublishIntoRestartedConversation() {
        val events = ArrayList<JSONObject>()
        val old = VoiceSession("old", 8, "en", "es", "translation", events::add)
        val releaseCallback = CountDownLatch(1)
        var oldCaptures = 0
        val delayed = Thread {
            releaseCallback.await()
            old.ready { oldCaptures++ }
            old.greeted { oldCaptures++ }
            old.emit(JSONObject().put("type", "caption").put("text", "previous guest"))
        }
        delayed.start()
        old.close()
        old.detached(true)
        events.clear()
        val fresh = VoiceSession("fresh", 9, "fr", "de", "translation", events::add)
        var newCaptures = 0
        fresh.greeted { newCaptures++ }
        fresh.ready { newCaptures++ }
        releaseCallback.countDown()
        delayed.join()
        fresh.phase("listening")
        assertEquals(0, oldCaptures)
        assertEquals(1, newCaptures)
        assertEquals(1, events.size)
        assertEquals("fresh", events.single().getString("session_id"))
        assertEquals(9, events.single().getInt("generation"))
    }
}
