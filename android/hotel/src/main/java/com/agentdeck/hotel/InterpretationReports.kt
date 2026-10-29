package com.agentdeck.hotel

import org.json.JSONObject
import java.util.Locale

/** The model's caption report is a complete utterance, not a hint to match by text or order.
 * Only this display-only tool is accepted; it cannot invoke hotel or device operations.
 */
internal class InterpretationReports(private val guest: String, private val staff: String) {
    private val seen = HashSet<String>()
    @Synchronized fun hasSeen(id: String): Boolean = id in seen

    @Synchronized fun accept(event: JSONObject): JSONObject? {
        if (event.optString("type") != "client_tool.call" || event.optString("toolName") != FishPlan.CAPTION_TOOL ||
            event.opt("expectsResponse") != false) return null
        val id = event.opt("callId") as? String ?: return null
        // callId is opaque protocol data, not a URL path. Providers may include : / or .
        if (id.isBlank() || id.length > 200 || id.any { it.isISOControl() } || id in seen) return null
        val params = event.optJSONObject("params") ?: return null
        val original = params.opt("original") as? String ?: return null
        val translation = params.opt("translation") as? String ?: return null
        val rawLanguage = params.opt("source_language") as? String ?: return null
        val language = rawLanguage.trim().lowercase(Locale.ROOT).replace('_', '-').substringBefore('-')
        if (language !in setOf(guest, staff, "und") || original.isBlank() || original.length > 8000 || translation.length > 8000) return null
        // Uncertain language must not imply a translation direction.
        if (language == "und" && translation.isNotBlank()) return null
        if (language != "und" && translation.isBlank()) return null
        seen.add(id)
        return JSONObject().put("type", "interpretation").put("utterance_id", "fish:report:$id")
            .put("source_language", language).put("language_attribution", "model")
            .put("original", original).put("text", if (language == "und") "" else translation)
    }
}
