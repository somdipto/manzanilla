package com.agentdeck.hotel

import org.json.JSONObject

/** Fish segment IDs revise one speaker's text; they do not identify translation pairs or languages.
 * Keep each provider field independent. Speaker side stays provider-reported: with one shared
 * microphone the device cannot attribute a voice, so human speech is never relabeled locally.
 */
internal class InterpreterCaptions {
    private class Segment(val side: String) {
        var finalText: String? = null
    }
    // Metadata lives only with this run (capped by FishPlan.CLIENT_CAP_MS). Do not evict segment
    // identities mid-session: delayed revisions must retain finality.
    private val humans = HashMap<String, Segment>()
    private val assistants = HashMap<String, Segment>()

    @Synchronized fun accept(caption: JSONObject): List<JSONObject> {
        val id = caption.optString("utterance_id")
        val side = caption.optString("side")
        val field = caption.optString("field")
        if (id.isEmpty() || (side != "human" && side != "assistant") ||
            field != (if (side == "human") "original" else "text")) return emptyList()
        val segments = if (side == "human") humans else assistants
        val segment = segments.getOrPut(id) {
            Segment(if (side == "human") "human" else "assistant")
        }
        val text = caption.optString("text")
        val final = caption.optBoolean("final")
        if (segment.finalText != null && (!final || segment.finalText == text)) return emptyList()
        if (final) segment.finalText = text
        return listOf(UnpairedCaptions.caption(id, segment.side, "", "", field, "replace", text, final)
            .put("association", "unlinked")
            .put("speaker_attribution", if (segment.side == "guest" || segment.side == "staff") "selected" else "unknown"))
    }
}
