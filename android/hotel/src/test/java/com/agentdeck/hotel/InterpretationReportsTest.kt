package com.agentdeck.hotel

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InterpretationReportsTest {
    private fun call(id: String, language: String, original: String = "204", translation: String = if (language == "und") "" else "Translation") =
        JSONObject().put("type", "client_tool.call").put("toolName", FishPlan.CAPTION_TOOL)
            .put("callId", id).put("expectsResponse", false).put("params", JSONObject()
                .put("source_language", language).put("original", original).put("translation", translation))

    @Test fun everyPairReportsTheActualSourceCodeWithoutEnglishOrTurnOrderDefaults() {
        for (guest in DeskLanguages.codes) for (staff in DeskLanguages.codes) {
            if (guest == staff) continue
            val reports = InterpretationReports(guest, staff)
            for ((id, language) in listOf("one" to staff, "two" to staff, "three" to "und", "four" to guest, "five" to guest, "six" to "und")) {
                val event = reports.accept(call(id, language, translation = if (language == "und") " \n\t " else "Translation"))!!
                assertEquals(language, event.getString("source_language"))
                assertEquals("model", event.getString("language_attribution"))
                assertEquals("fish:report:$id", event.getString("utterance_id"))
                assertEquals(if (language == "und") "" else "Translation", event.getString("text"))
            }
        }
    }

    @Test fun originalAndTranslationStayTogetherAndRepeatedTextIsNotDeduplicated() {
        val reports = InterpretationReports("fr", "de")
        val original = "<img src=x>\n  Ich brauche Zimmer 204."
        for (id in listOf("first", "second", "third")) {
            val translation = "  Je voudrais la chambre 204. \n"
            val event = reports.accept(call(id, "de-DE", original, translation))!!
            assertEquals(original, event.getString("original"))
            assertEquals(translation, event.getString("text"))
            assertEquals("de", event.getString("source_language"))
        }
        assertNull(reports.accept(call("first", "fr", "Changed", "Changed")))
    }

    @Test fun uncertainReportsHaveCanonicalEmptyTextAfterEitherSpeaker() {
        val reports = InterpretationReports("fr", "de")
        for ((index, language) in listOf("de", "und", "fr", "und").withIndex()) {
            val original = " \n 204 — María. \t"
            val event = reports.accept(call("turn-$index", language, original,
                if (language == "und") " \n\t " else "  Chambre 204. \n"))!!
            assertEquals(language, event.getString("source_language"))
            assertEquals(original, event.getString("original"))
            assertEquals(if (language == "und") "" else "  Chambre 204. \n", event.getString("text"))
        }
        assertNull(reports.accept(call("uncertain", "und", translation = " \n204 ")))
        assertNull(reports.accept(call("known", "de", translation = " \n\t ")))
        assertNotNull(reports.accept(call("uncertain", "und")))
        assertNotNull(reports.accept(call("known", "de")))
    }

    @Test fun validatesToolTypeLanguageAndPayloadWithoutAuthorizingActions() {
        val reports = InterpretationReports("fr", "de")
        assertNull(reports.accept(call("a", "en")))
        assertNull(reports.accept(call("b", "und", translation = "Guessed direction")))
        assertNull(reports.accept(call("empty", "de", translation = "")))
        assertNull(reports.accept(call("c", "fr", original = " ")))
        assertNull(reports.accept(call("d", "de", original = "x".repeat(8001))))
        assertNull(reports.accept(call("e", "de").put("toolName", "book_room")))
        assertNull(reports.accept(call("f", "de").put("expectsResponse", true)))
        assertNull(reports.accept(call("g", "de").put("expectsResponse", "false")))
        assertNull(reports.accept(call("h", "de").put("type", "tool.started")))
        assertNull(reports.accept(call("bad\nid", "de")))
        assertNull(reports.accept(call("", "de")))
        assertNull(reports.accept(call(" ".repeat(10), "de")))
        assertNull(reports.accept(call("x".repeat(201), "de")))
        assertNotNull(reports.accept(call("x".repeat(200), "de")))
        assertNotNull(reports.accept(call("provider:turn/2.abc", "de")))
        val numeric = call("numeric", "de")
        numeric.getJSONObject("params").put("original", 204)
        assertNull(reports.accept(numeric))
        assertNotNull(reports.accept(call("a", "de"))) // Invalid attempts do not consume valid IDs.
    }

    @Test fun ownershipAndDeduplicationArePerSession() {
        val previous = InterpretationReports("fr", "de")
        assertNotNull(previous.accept(call("same", "de")))
        val next = InterpretationReports("zh", "ja")
        assertNotNull(next.accept(call("same", "ja")))
        val emitted = ArrayList<JSONObject>()
        val owner = VoiceSession("new-session", 7, "zh", "ja", "translation", emitted::add)
        owner.close()
        next.accept(call("late", "zh"))?.let(owner::emit)
        assertTrue(emitted.isEmpty())
    }
}
