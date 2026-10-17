package com.agentdeck.hotel

import org.json.JSONArray
import org.json.JSONObject

/**
 * Fish-hosted speech recognition and synthesis with Gemini interpretation over LiveKit.
 * Scribe multilingual recognition hears both parties; each session supplies its language pair.
 * Language coverage and interruption quality require live acoustic verification, not picker counts.
 */
object FishPlan {
    // The agent and its sessions follow the device's own timezone, never a fixed hotel location.
    fun deviceTimezone(): String = java.util.TimeZone.getDefault().id

    const val CONFIG_REV = "8"
    const val AGENT_NAME = "Manzanilla Interpreter"
    const val MAX_SECONDS = 600
    const val CLIENT_CAP_MS = 540_000L
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
            Do not repeat the original words. You open the conversation with one short greeting in the guest language (your first message). After that, never greet again. Never ask how you can help. Never add commentary.
            You will sometimes hear your own spoken translation come back through the shared microphone. Recognize it as your own voice and stay silent; never translate or answer yourself.
            Music, television, and background chatter are not turns; stay silent for them. Only translate direct human speech.
            Keep numbers, dates, times, prices, names, room numbers, and currencies exact.
            If you cannot tell which of the two languages was spoken, stay silent.
            Translate the complete meaning faithfully. Do not shorten, summarize, or omit clauses, conditions, negations, or qualifiers; use as many sentences as needed.
        """.trimIndent()
    }



    // Fish pins one speaking language from a 7-language allowlist; an interpreter speaks two,
    // so the pin follows the staff language when available and English otherwise.
    private val SPEAKING_SUPPORTED = setOf("en", "ja", "zh", "ko", "es", "fr", "de")

    // Accuracy over speed for an interpreter: Scribe v2 Realtime (4.3% WER, ~99 languages) beats
    // Nova-3 (5.3% WER, 10-language switching) in independent benchmarks; the ~0.5 s saved per turn
    // is not worth the extra mistranslations. https://benchmarks.coval.ai/benchmarks/word-error-rate
    private val GENERIC_PROMPT = """
        You are a spoken interpreter between a hotel guest and a receptionist who share one microphone. Each conversation tells you the two languages.
        When you hear one language, speak only the translation in the other language. Switch the spoken output language on every utterance to the other person's language.
        Do not repeat the original words. Open with the configured greeting in the guest's language, then translate only. Never ask how you can help. Never add commentary.
        Keep numbers, dates, times, prices, names, room numbers, and currencies exact. If you cannot tell which language was spoken, stay silent.
        Translate the complete meaning faithfully; use as many sentences as needed.
    """.trimIndent()

    fun agentConfig(voiceId: String, staff: String): JSONObject = JSONObject()
        .put("prompt", JSONObject()
            .put("system_prompt", GENERIC_PROMPT)
            .put("first_message_mode", "fixed")
            .put("first_message", "Hello! How can I help you today?"))
        .put("voice", JSONObject()
            .put("voice_id", voiceId)
            .put("speaking_language", if (staff in SPEAKING_SUPPORTED) staff else "en")
            .put("expressive", true))
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
            .put("timezone", deviceTimezone())
            .put("interruptible", true)
            .put("interruption_sensitivity", "balanced")
            // Fish's documented Fast preset; the default 550 ms adds dead air to every reply.
            .put("response_wait_ms", 450)
            // Left unset so Fish keeps its automatic ceiling (wait + 0.5 s).
            // Kept off: an observed duplicate response followed provider-error recovery with this on.
            .put("speculative_response", false))

    fun sessionBody(agentId: String, guest: String, staff: String, greet: String): JSONObject = JSONObject()
        .put("agent_id", agentId)
        .put("timezone", deviceTimezone())
        .put("record_audio", false)
        .put("tool_events", false)
        .put("world_context", false)
        .put("overrides", JSONObject()
            .put("system_prompt", prompt(guest, staff))
            .apply { if (greet.isNotBlank()) put("first_message", greet) })
}
