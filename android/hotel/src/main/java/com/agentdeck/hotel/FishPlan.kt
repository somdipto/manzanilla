package com.agentdeck.hotel

import org.json.JSONArray
import org.json.JSONObject

/**
 * Fish-hosted speech recognition and synthesis with Gemini interpretation over LiveKit.
 * Scribe multilingual recognition hears both parties; each session supplies its language pair.
 * Language coverage and interruption quality require live acoustic verification, not picker counts.
 */
object FishPlan {
    const val CONFIG_REV = "6"
    const val AGENT_NAME = "Manzanilla Interpreter"
    const val MAX_SECONDS = 600
    const val CLIENT_CAP_MS = 540_000L
    const val TIMEZONE = "Europe/Madrid"
    const val DEFAULT_VOICE = "802e3bc2b27e49c2995d23ef70e6ac89"

    fun prompt(guest: String, staff: String): String {
        val g = DeskLanguages.english(guest)
        val s = DeskLanguages.english(staff)
        return """
            You are a spoken interpreter between a guest and a hotel receptionist who share one microphone.
            Guest language: $g ($guest). Hotel language: $s ($staff).
            When you hear the guest language, speak only the translation in the hotel language, so the receptionist can hear it.
            When you hear the hotel language, speak only the translation in the guest language, so the guest can hear it.
            Switch the spoken output language on every utterance to the other person's language, regardless of the configured speaking language.
            Do not repeat the original words. The guest was already greeted in the guest language. Never greet. Stay silent until you hear a person. Never ask how you can help. Never add commentary.
            Keep numbers, dates, times, prices, names, room numbers, and currencies exact.
            If you cannot tell which of the two languages was spoken, stay silent.
            One short spoken sentence.
        """.trimIndent()
    }



    fun agentConfig(voiceId: String, staff: String): JSONObject = JSONObject()
        .put("prompt", JSONObject()
            .put("system_prompt", prompt("en", "es"))
            .put("first_message_mode", "off"))
        .put("voice", JSONObject()
            .put("voice_id", voiceId)
            .put("speaking_language", staff)
            .put("expressive", false))
        .put("llm", JSONObject().put("model", "google/gemini-3.8-flash"))
        .put("asr", JSONObject()
            .put("model", "elevenlabs:scribe_v2_realtime")
            .put("multilingual", true)
            .put("strict_language", false))
        .put("tools", JSONObject().put("enabled", false).put("tool_ids", JSONArray()))
        .put("knowledge_base", JSONObject().put("enabled", false).put("knowledge_source_ids", JSONArray()))
        .put("conversation", JSONObject()
            .put("max_duration_seconds", MAX_SECONDS)
            .put("record_audio", false)
            .put("timezone", TIMEZONE)
            .put("interruptible", true)
            .put("interruption_sensitivity", "balanced")
            // Fish's documented Fast preset; the default 550 ms adds dead air to every reply.
            .put("response_wait_ms", 450)
            // Left unset so Fish keeps its automatic ceiling (wait + 0.5 s).
            // Kept off: an observed duplicate response followed provider-error recovery with this on.
            .put("speculative_response", false))

    fun sessionBody(agentId: String, guest: String, staff: String): JSONObject = JSONObject()
        .put("agent_id", agentId)
        .put("timezone", TIMEZONE)
        .put("record_audio", false)
        .put("tool_events", false)
        .put("world_context", false)
        .put("overrides", JSONObject()
            .put("language", staff)
            .put("system_prompt", prompt(guest, staff)))
}
