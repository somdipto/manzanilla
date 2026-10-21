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
            for ((id, language) in listOf("one" to staff, "two" to staff, "three" to guest, "four" to guest, "five" to "und")) {
                val event = reports.accept(call(id, language))!!
                assertEquals(language, event.getString("source_language"))
                assertEquals("model", event.getString("language_attribution"))
                assertEquals("fish:report:$id", event.getString("utterance_id"))
            }
        }
    }

    @Test fun originalAndTranslationStayTogetherAndRepeatedTextIsNotDeduplicated() {
        val reports = InterpretationReports("fr", "de")
        val original = "<img src=x>\n  Ich brauche Zimmer 204."
        for (id in listOf("first", "second", "third")) {
            val event = reports.accept(call(id, "de-DE", original, "Je voudrais la chambre 204."))!!
            assertEquals(original, event.getString("original"))
            assertEquals("Je voudrais la chambre 204.", event.getString("text"))
            assertEquals("de", event.getString("source_language"))
        }
        assertNull(reports.accept(call("first", "fr", "Changed", "Changed")))
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
