package com.agentdeck.hotel

import org.json.JSONObject

/** App-owned recognition ledger. Provider text is evidence, never reconstructed from translation. */
internal class TranslationRecords(private val session: String, private val guest: String, private val staff: String) {
    private val records = linkedMapOf<String, JSONObject>()
    private val languages = mutableMapOf<String, MutableSet<String>>()
    private var recognitionSession = ""

    @Synchronized fun accept(event: JSONObject): JSONObject? {
        if (event.optString("type") == "session_started") {
            check(recognitionSession.isEmpty())
            recognitionSession = event.getString("session_id").also { require(it.isNotBlank()) }
            return null
        }
        val kind = event.optString("type")
        if (kind !in setOf("speech_start", "draft", "committed")) return null
        require(recognitionSession.isNotEmpty())
        val index = event.getInt("turn_index").also { require(it >= 0) }
        val id = "$recognitionSession:$index"
        val old = records[id]
        if (old != null && (old.optString("translation_status") == "cancelled" || old.optString("playback_status") == "interrupted")) return null
        if (old != null && old.optString("recognition_status") != "partial") return null
        val final = kind == "committed"
        val original = if (kind == "speech_start") old?.optString("original").orEmpty() else event.getString("text")
        require(original.length <= 16000)
        val detected = event.optString("language_code").takeIf { it.isNotBlank() && it != "und" }
        if (detected != null) languages.getOrPut(id) { mutableSetOf() }.add(detected)
        val speech = speechText(original)
        val degraded = event.optBoolean("degraded")
        val mismatch = detected != null && detected != guest && detected != staff
        val mixed = (languages[id]?.size ?: 0) > 1
        val uncertain = degraded || !event.optBoolean("user_turn", true) || detected == null || ambiguous(speech)
        val reason = when {
            mismatch -> "mismatch"
            mixed -> "mixed"
            guest == staff -> "same_language"
            uncertain || !final -> "uncertain"
            else -> "recognized"
        }
        val role = if (final && reason == "recognized") if (detected == guest) "guest" else "receptionist" else null
        val next = JSONObject().put("session_id", session).put("utterance_id", id)
            .put("revision", (old?.optInt("revision") ?: 0) + 1)
            .put("source_language", if (role != null) detected else JSONObject.NULL)
            .put("target_language", if (role == "guest") staff else if (role != null) guest else JSONObject.NULL)
            .put("role", role ?: JSONObject.NULL).put("original", original).put("translation", "")
            .put("recognition_status", if (degraded) "degraded" else if (final) "final" else "partial")
            .put("translation_status", if (!final) "waiting" else if (speech.isEmpty()) "ignored" else if (role == null) "needs_direction" else "waiting")
            .put("playback_status", "idle").put("output_generation", 0)
            .put("direction_reason", reason).put("detected_language", detected ?: JSONObject.NULL)
        records[id] = next
        return copy(next)
    }

    @Synchronized fun resolve(id: String, revision: Int, role: String): JSONObject {
        require(role == "guest" || role == "receptionist")
        val record = records[id] ?: throw IllegalArgumentException("Unknown utterance")
        require(record.getInt("revision") == revision && record.getString("recognition_status") in setOf("final", "degraded") &&
            record.getString("translation_status") == "needs_direction" && record.getString("direction_reason") != "mismatch")
        return update(id, "role" to role, "source_language" to if (role == "guest") guest else staff,
            "target_language" to if (role == "guest") staff else guest,
            "direction_reason" to "manual", "translation_status" to "waiting")
    }

    @Synchronized fun update(id: String, vararg changes: Pair<String, Any>): JSONObject {
        val record = records.getValue(id)
        if (record.getString("translation_status") == "cancelled" || record.getString("playback_status") == "interrupted") {
            require(changes.all { (key, value) -> record.opt(key) == value }) { "Output is terminal" }
            return copy(record)
        }
        changes.forEach { (key, value) -> record.put(key, value) }
        record.put("revision", record.getInt("revision") + 1)
        return copy(record)
    }

    @Synchronized fun finishOutput(id: String, status: String): JSONObject {
        require(status == "complete" || status == "interrupted" || status == "error")
        val previous = records.getValue(id).getString("translation_status")
        val translation = if (previous == "complete") previous else when (status) {
            "interrupted" -> "cancelled"
            else -> status
        }
        return update(id, "translation_status" to translation, "playback_status" to status)
    }

    @Synchronized fun get(id: String): JSONObject = copy(records.getValue(id))

    companion object {
        private val sounds = Regex("\\[[^\\[\\]]*\\]")
        private val speakers = Regex("<\\|speaker:\\d+\\|>")
        private val whitespace = Regex("\\s+")
        fun speechText(text: String): String = text.replace(speakers, "").replace(sounds, "").replace(whitespace, " ").trim()
        fun canonical(text: String): String = text.replace('[', '(').replace(']', ')')
        private fun ambiguous(text: String): Boolean {
            val words = Regex("[\\p{L}\\p{N}]+").findAll(text).map { it.value }.toList()
            if (words.none { word -> word.any(Char::isLetter) }) return true
            // Unsegmented CJK/Thai phrases are not automatically single names/words.
            if (text.any { it in '\u3400'..'\u9fff' || it in '\u3040'..'\u30ff' || it in '\u0e00'..'\u0e7f' })
                return text.count(Char::isLetter) < 4
            return words.size < 2
        }
        private fun copy(value: JSONObject) = JSONObject().apply {
            for (key in value.keys()) put(key, value.get(key))
        }
    }
}
