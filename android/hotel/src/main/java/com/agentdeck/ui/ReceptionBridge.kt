package com.agentdeck.ui

import org.json.JSONObject

/** Bounds the serialized local-page request before parsing, independently of field limits. */
internal object ReceptionBridge {
    // A valid note contains 4,000 + 100 + 20 characters. JSON can escape each
    // character as six code units (\u0001), plus the action/session envelope.
    // The old 8,192 limit silently discarded valid notes before native review.
    const val MAX_MESSAGE_CHARS = 32 * 1024
    private val actions = setOf(
        "info", "start", "stop", "appearance", "night", "settings", "voice_setup", "languages", "resolve_direction",
        "checkin", "operator", "taxi", "question", "message", "confirm", "silence",
        "activity", "mute", "exit", "apply_update", "check_updates", "watch_request", "unwatch_request"
    )

    fun decode(raw: String): JSONObject? {
        if (raw.length > MAX_MESSAGE_CHARS) return null
        val message = try { JSONObject(raw) } catch (_: Exception) { return null }
        if (message.optString("action") == "resolve_direction") {
            val allowed = setOf("action", "session_id", "generation", "utterance_id", "revision", "role")
            if (message.keys().asSequence().any { it !in allowed } ||
                message.opt("session_id") !is String || message.optString("session_id").isBlank() ||
                message.opt("generation") !is Int || message.optInt("generation") < 0 ||
                message.opt("utterance_id") !is String || message.optString("utterance_id").isBlank() ||
                message.optString("utterance_id").length > 256 ||
                message.opt("revision") !is Int || message.optInt("revision") < 1 ||
                message.optString("role") !in setOf("guest", "receptionist")) return null
        }
        return message.takeIf { it.optString("action") in actions }
    }
}
