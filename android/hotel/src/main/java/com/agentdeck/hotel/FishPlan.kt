package com.agentdeck.hotel

import org.json.JSONObject
/** Compare semantic release tags while accepting older bare version-code tags. */
object ReleaseVersion {
    fun newer(tag: String, current: String): Boolean {
        fun parts(v: String): List<Int>? {
            val p = v.trim().removePrefix("v").split(".")
            if (p.isEmpty() || p.any { it.toIntOrNull() == null }) return null
            return p.map { it.toInt() }
        }
        val a = parts(tag) ?: return false
        val b = parts(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** A bare run-number tag is newer only when it exceeds this build's versionCode. */
    fun offer(tag: String, versionName: String, versionCode: Long): Boolean {
        val bare = tag.trim().removePrefix("v")
        val run = bare.toLongOrNull()
        if (run != null && !bare.contains('.')) return run > versionCode
        return newer(bare, versionName)
    }
}

/**
 * Fish Agents is the only documented full-duplex route. Standalone Fish ASR is file transcription.
 * One session has one speaking-language setting, so the prompt asks for pair translation and we do not pin language.
 */
object FishPlan {
    const val CONFIG_REV = "3"
    const val AGENT_NAME = "Manzanilla Reception"
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
            Do not repeat the original words. The guest was already greeted in the guest language. Never greet. Stay silent until you hear a person. Never ask how you can help. Never add commentary.
            Keep numbers, dates, times, prices, names, room numbers, and currencies exact.
            If you cannot tell which of the two languages was spoken, stay silent.
            One or two short spoken sentences.
        """.trimIndent()
    }



    fun agentConfig(voiceId: String): JSONObject = JSONObject()
        .put("prompt", JSONObject()
            .put("system_prompt", prompt("en", "es"))
            .put("first_message_mode", "off"))
        .put("voice", JSONObject()
            .put("voice_id", voiceId)
            .put("speaking_language", "es")
            .put("expressive", false))
        .put("asr", JSONObject()
            .put("model", "deepgram:nova-3")
            .put("multilingual", true)
            .put("strict_language", false))
        .put("conversation", JSONObject()
            .put("max_duration_seconds", MAX_SECONDS)
            .put("record_audio", false)
            .put("timezone", TIMEZONE)
            .put("interruptible", true)
            .put("interruption_sensitivity", "low")
            .put("response_wait_ms", 700)
            .put("speculative_response", true)
            .put("hangup_after_seconds", 600))

    fun sessionBody(agentId: String, guest: String, staff: String): JSONObject = JSONObject()
        .put("agent_id", agentId)
        .put("timezone", TIMEZONE)
        .put("record_audio", false)
        .put("tool_events", false)
        .put("overrides", JSONObject()
            .put("system_prompt", prompt(guest, staff)))
}
