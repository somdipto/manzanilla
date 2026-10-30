package com.agentdeck.hotel

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TranslationStreamTest {
    private fun delta(text: String? = null, finish: String? = null) = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("delta", JSONObject().apply { if (text != null) put("content", text) })
            .put("finish_reason", finish ?: JSONObject.NULL))).toString()

    @Test fun clausesSentToSynthesisAreExactlyCanonicalDisplayedTranslation() {
        val synthesis = mutableListOf<String>()
        val displayed = StringBuilder()
        val stream = TranslationStream(received = { displayed.append(it) }, clause = { synthesis.add(it) })
        stream.accept(delta("Can I leave"))
        assertEquals("Can I leave", displayed.toString())
        assertTrue(synthesis.isEmpty())
        stream.accept(delta(" my bag here?"))
        assertTrue(synthesis.isEmpty())
        stream.accept(delta(" Please keep [Room 204] unchanged."))
        assertEquals("Can I leave my bag here? Please keep (Room 204) unchanged.", displayed.toString())
        assertEquals(listOf("Can I leave my bag here?"), synthesis)
        stream.accept(delta(finish = "stop"))
        stream.accept("[DONE]")
        stream.finish()
        assertEquals("Can I leave my bag here? Please keep (Room 204) unchanged.", synthesis.joinToString(""))
        assertEquals(displayed.toString(), synthesis.joinToString(""))
    }

    @Test fun eofLengthFilteringToolsAndMissingTerminalAreFailures() {
        val eof = TranslationStream(received = {}, clause = {})
        eof.accept(delta("Partial translation"))
        assertThrows(IllegalStateException::class.java) { eof.finish() }
        for (reason in listOf("length", "content_filter", "tool_calls")) {
            val stream = TranslationStream(received = {}, clause = {})
            stream.accept(delta("Partial translation"))
            assertThrows(IllegalStateException::class.java) { stream.accept(delta(finish = reason)) }
        }
        val missing = TranslationStream(received = {}, clause = {})
        assertThrows(IllegalStateException::class.java) { missing.accept("[DONE]") }
        val tools = TranslationStream(received = {}, clause = {})
        val event = JSONObject(delta()).getJSONArray("choices").getJSONObject(0)
        event.getJSONObject("delta").put("tool_calls", JSONArray())
        assertThrows(IllegalStateException::class.java) { tools.accept(JSONObject().put("choices", JSONArray().put(event)).toString()) }
    }

    @Test fun unfinishedCanonicalDeltasSurviveProviderFailuresWithoutBeingSynthesized() {
        for (failure in listOf("error", "length", "content_filter", "eof")) {
            val ledger = TranslationRecords("owned", "en", "es")
            ledger.accept(JSONObject().put("type", "session_started").put("session_id", "asr-owned"))
            val original = "  Keep [Room 204] unchanged.\n"
            val record = ledger.accept(JSONObject().put("type", "committed").put("turn_index", 0)
                .put("text", original).put("language_code", "en"))!!
            val id = record.getString("utterance_id")
            ledger.update(id, "translation_status" to "streaming", "output_generation" to 1)
            val synthesis = mutableListOf<String>()
            val stream = TranslationStream(received = {
                ledger.update(id, "translation" to (ledger.get(id).getString("translation") + it))
            }, clause = { synthesis.add(it) })
            stream.accept(delta("Conserve [Room"))
            stream.accept(delta(" 204] sin"))
            assertThrows(IllegalStateException::class.java) {
                when (failure) {
                    "error" -> stream.accept(JSONObject().put("error", JSONObject()).toString())
                    "eof" -> stream.finish()
                    else -> stream.accept(delta(finish = failure))
                }
            }
            val failed = ledger.finishOutput(id, "error")
            assertEquals(original, failed.getString("original"))
            assertEquals("Conserve (Room 204) sin", failed.getString("translation"))
            assertEquals("error", failed.getString("translation_status"))
            assertTrue(synthesis.isEmpty())
        }
    }

    @Test fun cancellationRetainsUnstableTailAndLateDeltasCannotResurrectTerminalRecord() {
        val ledger = TranslationRecords("owned", "en", "es")
        ledger.accept(JSONObject().put("type", "session_started").put("session_id", "asr-owned"))
        val original = "  Keep my bag here, please.\n"
        val record = ledger.accept(JSONObject().put("type", "committed").put("turn_index", 0)
            .put("text", original).put("language_code", "en"))!!
        val id = record.getString("utterance_id")
        ledger.update(id, "translation_status" to "streaming", "output_generation" to 1)
        val synthesis = mutableListOf<String>()
        val stream = TranslationStream(received = {
            ledger.update(id, "translation" to (ledger.get(id).getString("translation") + it))
        }, clause = { synthesis.add(it) })
        stream.accept(delta("Deje mi bolsa aquí."))
        stream.accept(delta(" Por favor, [no"))
        val cancelled = ledger.finishOutput(id, "interrupted")
        assertEquals(original, cancelled.getString("original"))
        assertEquals("Deje mi bolsa aquí. Por favor, (no", cancelled.getString("translation"))
        assertEquals("cancelled", cancelled.getString("translation_status"))
        assertEquals(listOf("Deje mi bolsa aquí."), synthesis)
        assertThrows(IllegalArgumentException::class.java) { stream.accept(delta(" tarde]. Otra frase.")) }
        assertEquals(cancelled.toString(), ledger.get(id).toString())
        assertEquals(listOf("Deje mi bolsa aquí."), synthesis)
    }

    @Test fun completedWhitespaceAndSplitCanonicalMarkersAreNotDroppedOrAppendedTwice() {
        val displayed = StringBuilder()
        val synthesis = mutableListOf<String>()
        val stream = TranslationStream(received = { displayed.append(it) }, clause = { synthesis.add(it) })
        for (part in listOf(" \n ", "[", "Room 204", "]", " stays.", " \n\t")) stream.accept(delta(part))
        stream.accept(delta(finish = "stop"))
        stream.accept("[DONE]")
        stream.finish()
        assertEquals(" \n (Room 204) stays. \n\t", displayed.toString())
        assertEquals(displayed.toString(), synthesis.joinToString(""))
    }

    @Test fun receivedDeltaIsRetainedBeforeSynthesisFailure() {
        val displayed = StringBuilder()
        val stream = TranslationStream(received = { displayed.append(it) }, clause = { error("Fish unavailable") })
        stream.accept(delta("Keep [Room 204]."))
        assertThrows(IllegalStateException::class.java) { stream.accept(delta(" Unfinished tail")) }
        assertEquals("Keep (Room 204). Unfinished tail", displayed.toString())
    }

    @Test fun fishAudioIsBinaryAndEffectiveAsrConfigMustMatch() {
        val audio = byteArrayOf(0, -1, 3, 0, 4, 0)
        val frame = FishWire.audio(audio)
        val decoded = FishWire.decode(frame)
        assertArrayEquals(audio, decoded.get("data") as ByteArray)
        val started = JSONObject().put("session_id", "asr").put("config", FishWire.asrConfig().apply { remove("type") })
        FishWire.validateStarted(started)
        started.getJSONObject("config").getJSONObject("results").put("diarize", true)
        assertThrows(IllegalArgumentException::class.java) { FishWire.validateStarted(started) }
    }
}
