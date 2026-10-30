package com.agentdeck.hotel

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TranslationRecordsTest {
    private fun ledger(guest: String = "en", staff: String = "es") = TranslationRecords("owned", guest, staff).also {
        it.accept(JSONObject().put("type", "session_started").put("session_id", "asr-owned"))
    }
    private fun event(index: Int, text: String, code: String? = "en", type: String = "committed") =
        JSONObject().put("type", type).put("turn_index", index).put("text", text).apply { if (code != null) put("language_code", code) }

    @Test fun repeatedSpeakersRouteOnlyFromTheirOwnFinalEvidence() {
        val ledger = ledger()
        for ((index, code) in listOf("en", "en", "en", "es", "es", "en").withIndex()) {
            val row = ledger.accept(event(index, "A full utterance?", code))!!
            assertEquals(if (code == "en") "guest" else "receptionist", row.getString("role"))
            assertEquals(if (code == "en") "es" else "en", row.getString("target_language"))
        }
        val unknown = ledger.accept(event(6, "204", null))!!
        assertTrue(unknown.isNull("role"))
        assertEquals("needs_direction", unknown.getString("translation_status"))
    }

    @Test fun draftsReplaceOriginalAndFinalCannotReplayOrMutatePriorSnapshot() {
        val ledger = ledger()
        val draft = ledger.accept(event(0, "Can I", type = "draft"))!!
        val original = "  Can I leave my bag here?\n"
        val final = ledger.accept(event(0, original))!!
        assertEquals(draft.getString("utterance_id"), final.getString("utterance_id"))
        assertEquals("Can I", draft.getString("original"))
        assertEquals(original, final.getString("original"))
        assertTrue(final.getInt("revision") > draft.getInt("revision"))
        assertNull(ledger.accept(event(0, original)))
        assertNull(ledger.accept(event(0, "late draft", type = "draft")))
        val updated = ledger.update(final.getString("utterance_id"), "translation" to "Same text sent to TTS.", "translation_status" to "cancelled", "playback_status" to "interrupted")
        assertEquals(original, updated.getString("original"))
        assertEquals("", final.getString("translation"))
        assertEquals("cancelled", updated.getString("translation_status"))
        val id = final.getString("utterance_id")
        assertThrows(IllegalArgumentException::class.java) { ledger.update(id, "translation_status" to "complete", "translation" to "Late result") }
        assertEquals(updated.getInt("revision"), ledger.get(id).getInt("revision"))
    }

    @Test fun interruptedPartialCannotBeRewrittenByLateRecognition() {
        val ledger = ledger()
        val partial = ledger.accept(event(0, "A preserved partial", type = "draft"))!!
        val id = partial.getString("utterance_id")
        val cancelled = ledger.update(id, "translation_status" to "cancelled", "playback_status" to "interrupted")
        assertNull(ledger.accept(event(0, "A late final")))
        assertNull(ledger.accept(event(0, "A late draft", type = "draft")))
        assertEquals(cancelled.toString(), ledger.get(id).toString())
        assertThrows(IllegalArgumentException::class.java) { ledger.update(id, "original" to "A late replacement") }
    }

    @Test fun manualDirectionRequiresUnresolvedFinalAndCurrentRevision() {
        val ledger = ledger()
        val uncertain = ledger.accept(event(0, "María"))!!
        val resolved = ledger.resolve(uncertain.getString("utterance_id"), uncertain.getInt("revision"), "receptionist")
        assertEquals("es", resolved.getString("source_language"))
        assertEquals("en", resolved.getString("target_language"))
        assertEquals("manual", resolved.getString("direction_reason"))
        assertThrows(IllegalArgumentException::class.java) { ledger.resolve(uncertain.getString("utterance_id"), uncertain.getInt("revision"), "guest") }
        val partial = ledger.accept(event(1, "A partial", type = "draft"))!!
        assertThrows(IllegalArgumentException::class.java) { ledger.resolve(partial.getString("utterance_id"), partial.getInt("revision"), "guest") }
    }

    @Test fun thirdLanguageMixedAndDegradedCannotBecomeConfidentRoles() {
        val ledger = ledger()
        val third = ledger.accept(event(0, "Une autre langue", "fr"))!!
        assertEquals("mismatch", third.getString("direction_reason"))
        assertThrows(IllegalArgumentException::class.java) { ledger.resolve(third.getString("utterance_id"), third.getInt("revision"), "guest") }
        ledger.accept(event(1, "A draft phrase", "en", "draft"))
        val mixed = ledger.accept(event(1, "Una frase final", "es"))!!
        assertEquals("mixed", mixed.getString("direction_reason"))
        assertTrue(mixed.isNull("role"))
        val degraded = ledger.accept(event(2, "A damaged final").put("degraded", true))!!
        assertEquals("degraded", degraded.getString("recognition_status"))
        val confirmed = ledger.resolve(degraded.getString("utterance_id"), degraded.getInt("revision"), "guest")
        assertEquals("manual", confirmed.getString("direction_reason"))
        assertEquals("degraded", confirmed.getString("recognition_status"))
        val backchannel = ledger.accept(event(3, "Yes of course").put("user_turn", false))!!
        assertTrue(backchannel.isNull("role"))
    }

    @Test fun identicalLanguagesRequireManualSideAndSoundTagsStayOnlyInOriginal() {
        val ledger = ledger("ar", "ar")
        val original = "[laughter] أين غرفتي؟"
        val row = ledger.accept(event(0, original, "ar"))!!
        assertEquals("same_language", row.getString("direction_reason"))
        assertEquals(original, row.getString("original"))
        val resolved = ledger.resolve(row.getString("utterance_id"), row.getInt("revision"), "guest")
        assertEquals("ar", resolved.getString("source_language"))
        assertEquals("ar", resolved.getString("target_language"))
        assertEquals("أين غرفتي؟", TranslationRecords.speechText(original))
        val sound = ledger.accept(event(1, "[laughter]", "ar"))!!
        assertEquals("ignored", sound.getString("translation_status"))
    }
    @Test fun playbackTerminationPreservesCompletedTranslationAndCancelsOnlyUnfinishedText() {
        for (status in listOf("interrupted", "error")) {
            for (complete in listOf(false, true)) {
                val ledger = ledger()
                val source = ledger.accept(event(0, "Exact original"))!!
                val id = source.getString("utterance_id")
                ledger.update(id, "translation" to "Preserved translation",
                    "translation_status" to if (complete) "complete" else "streaming")
                val result = ledger.finishOutput(id, status)
                assertEquals("Exact original", result.getString("original"))
                assertEquals("Preserved translation", result.getString("translation"))
                assertEquals(status, result.getString("playback_status"))
                assertEquals(if (complete) "complete" else if (status == "interrupted") "cancelled" else "error",
                    result.getString("translation_status"))
            }
        }
    }
}
