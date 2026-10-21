package com.agentdeck.hotel

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InterpreterCaptionsTest {
    private fun raw(side: String, id: String, text: String, final: Boolean = true) =
        UnpairedCaptions.caption(id, side, "", "", if (side == "assistant") "text" else "original", "replace", text, final)

    private fun assertCaption(events: List<JSONObject>, id: String, side: String, field: String,
                              text: String, final: Boolean = true) {
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals("caption", event.getString("type"))
        assertEquals(id, event.getString("utterance_id"))
        assertEquals(side, event.getString("side"))
        assertEquals(field, event.getString("field"))
        assertEquals("replace", event.getString("operation"))
        assertEquals(text, event.getString("text"))
        assertEquals(final, event.getBoolean("final"))
        assertEquals("", event.getString("from"))
        assertEquals("", event.getString("to"))
        assertEquals("unlinked", event.getString("association"))
        assertEquals("unknown", event.getString("speaker_attribution"))
    }

    @Test fun onePersonSpeakingTwiceDoesNotInventAlternatingSpeakers() {
        val captions = InterpreterCaptions()
        assertCaption(captions.accept(raw("human", "u1", "Hola, ¿tiene una habitación libre?")),
            "u1", "human", "original", "Hola, ¿tiene una habitación libre?")
        assertCaption(captions.accept(raw("human", "u2", "¿Y el desayuno a qué hora empieza?")),
            "u2", "human", "original", "¿Y el desayuno a qué hora empieza?")
    }

    @Test fun ambiguousWordsAndDigitsRemainUnknownWithoutLanguageClaims() {
        val captions = InterpreterCaptions()
        assertCaption(captions.accept(raw("human", "u1", "No no no")), "u1", "human", "original", "No no no")
        assertCaption(captions.accept(raw("human", "u2", "205")), "u2", "human", "original", "205")
        assertCaption(captions.accept(raw("assistant", "a1", "Sí, por supuesto.")),
            "a1", "assistant", "text", "Sí, por supuesto.")
    }

    @Test fun repeatingTranslatedWordsIsNotDeletedAsAnEcho() {
        val captions = InterpreterCaptions()
        val text = "Hello, do you have a room free for tonight?"
        assertCaption(captions.accept(raw("human", "request", "Hola, ¿tiene una habitación libre para esta noche?")),
            "request", "human", "original", "Hola, ¿tiene una habitación libre para esta noche?")
        assertCaption(captions.accept(raw("assistant", "a1", text)), "a1", "assistant", "text", text)
        assertCaption(captions.accept(raw("human", "u1", text, false)), "u1", "human", "original", text, false)
        assertCaption(captions.accept(raw("human", "u1", text)), "u1", "human", "original", text)
        assertCaption(captions.accept(raw("human", "u2", text)), "u2", "human", "original", text)
    }

    @Test fun multipleSegmentsOfAnUtteranceStayIndependentFromMultipleOutputSegments() {
        val captions = InterpreterCaptions()
        assertCaption(captions.accept(raw("human", "u1", "Quiero una habitación.")),
            "u1", "human", "original", "Quiero una habitación.")
        assertCaption(captions.accept(raw("human", "u2", "Para dos noches.")),
            "u2", "human", "original", "Para dos noches.")
        assertCaption(captions.accept(raw("assistant", "a1", "I would like a room")),
            "a1", "assistant", "text", "I would like a room")
        assertCaption(captions.accept(raw("assistant", "a2", "for two nights.")),
            "a2", "assistant", "text", "for two nights.")
    }

    @Test fun missingOutputCannotConsumeTheNextOutputAsAFifoTranslation() {
        val captions = InterpreterCaptions()
        assertCaption(captions.accept(raw("human", "missing", "¿Hay wifi?")),
            "missing", "human", "original", "¿Hay wifi?")
        assertCaption(captions.accept(raw("human", "next", "¿A qué hora es el desayuno?")),
            "next", "human", "original", "¿A qué hora es el desayuno?")
        assertCaption(captions.accept(raw("assistant", "a2", "What time is breakfast?")),
            "a2", "assistant", "text", "What time is breakfast?")
    }

    @Test fun interruptedOutputAndLaterSegmentsKeepTheirOwnIdentityAndFinality() {
        val captions = InterpreterCaptions()
        assertCaption(captions.accept(raw("human", "u1", "Necesito una habitación.")),
            "u1", "human", "original", "Necesito una habitación.")
        assertCaption(captions.accept(raw("assistant", "a1", "I need a", false)),
            "a1", "assistant", "text", "I need a", false)
        assertCaption(captions.accept(raw("human", "u2", "No, dos habitaciones.")),
            "u2", "human", "original", "No, dos habitaciones.")
        assertCaption(captions.accept(raw("assistant", "a2", "No, two rooms.")),
            "a2", "assistant", "text", "No, two rooms.")
        assertCaption(captions.accept(raw("assistant", "a1", "I need a room.")),
            "a1", "assistant", "text", "I need a room.")
    }

    @Test fun greetingIsAssistantOutputEvenWhenAHumanCaptionHasArrived() {
        val captions = InterpreterCaptions()
        assertCaption(captions.accept(raw("human", "u1", "Hola")), "u1", "human", "original", "Hola")
        assertCaption(captions.accept(raw("assistant", "greeting", "Welcome. How can I help?")),
            "greeting", "assistant", "text", "Welcome. How can I help?")
    }

    @Test fun duplicateFinalDoesNotEmitAgainAndDifferentFinalRevisionRetainsIdentity() {
        val captions = InterpreterCaptions()
        for (side in listOf("human", "assistant")) {
            val id = "$side-segment"
            val field = if (side == "human") "original" else "text"
            assertCaption(captions.accept(raw(side, id, "Room two", false)), id, side, field, "Room two", false)
            assertCaption(captions.accept(raw(side, id, "Room 205.")), id, side, field, "Room 205.")
            assertTrue(captions.accept(raw(side, id, "Room 205.")).isEmpty())
            assertTrue(captions.accept(raw(side, id, "Room two", false)).isEmpty())
            assertCaption(captions.accept(raw(side, id, "Room 205, please.")), id, side, field, "Room 205, please.")
        }
    }

    @Test fun finalEmptyTextStillBlocksLateInterims() {
        val captions = InterpreterCaptions()
        assertCaption(captions.accept(raw("human", "u1", "")), "u1", "human", "original", "")
        assertTrue(captions.accept(raw("human", "u1", "unfinished", false)).isEmpty())
        assertTrue(captions.accept(raw("human", "u1", "")).isEmpty())
    }

    @Test fun segmentMetadataIsNotEvictedDuringACappedSession() {
        val captions = InterpreterCaptions()
        assertCaption(captions.accept(raw("human", "first", "First.")), "first", "human", "original", "First.")
        repeat(10000) { captions.accept(raw("human", "u$it", "205")) }
        assertTrue(captions.accept(raw("human", "first", "late", false)).isEmpty())
        assertTrue(captions.accept(raw("human", "first", "First.")).isEmpty())
        assertCaption(captions.accept(raw("human", "first", "First, corrected.")),
            "first", "human", "original", "First, corrected.")
    }

    @Test fun separateRunsDoNotRetainFinality() {
        val first = InterpreterCaptions()
        first.accept(raw("human", "u1", "Old final."))
        val next = InterpreterCaptions()
        assertCaption(next.accept(raw("human", "u1", "New interim", false)),
            "u1", "human", "original", "New interim", false)
    }

    @Test fun providerFieldsRemainIndependentEvenIfInputAndOutputIdsCoincide() {
        val captions = InterpreterCaptions()
        assertCaption(captions.accept(raw("human", "shared", "205")),
            "shared", "human", "original", "205")
        assertCaption(captions.accept(raw("assistant", "shared", "205", false)),
            "shared", "assistant", "text", "205", false)
        assertCaption(captions.accept(raw("assistant", "shared", "205.")),
            "shared", "assistant", "text", "205.")
        assertTrue(captions.accept(raw("human", "shared", "late", false)).isEmpty())
    }

    @Test fun captionsWithoutAProviderIdentityOrKnownTransportSideAreIgnored() {
        val captions = InterpreterCaptions()
        assertTrue(captions.accept(raw("human", "", "Text")).isEmpty())
        assertTrue(captions.accept(raw("guest", "u1", "Text")).isEmpty())
    }
}
