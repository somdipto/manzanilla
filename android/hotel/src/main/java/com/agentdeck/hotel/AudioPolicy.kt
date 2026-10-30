package com.agentdeck.hotel

/** Local configuration only, not authenticated hotel or provider reachability. */
object AudioPolicy {
    const val LIVE_AUDIO_ENABLED = true
    const val BLOCK_REASON = "Live translation is unavailable. Ask staff to check the audio credential in Settings."

    /** Local readiness only. Returned source IDs are localized by the native message producer. */
    fun blockReason(backendConfigured: Boolean, credentialsConfigured: Boolean,
                    directFishSelected: Boolean, distribution: String): String {
        if (distribution == "hotel") return if (backendConfigured) "" else "Translation needs hotel setup. Please ask staff."
        if (!LIVE_AUDIO_ENABLED) return BLOCK_REASON
        if (TranslationRouting.useManagedConnection(backendConfigured, directFishSelected, distribution) ||
            credentialsConfigured) return ""
        return "Fish Audio + Gemini: save both keys and a voice to translate."
    }

    /** Evaluate before permission/capture, credential loading, ticket minting or provider connect. */
    fun allowStart(blocked: (String) -> Unit): Boolean {
        if (LIVE_AUDIO_ENABLED) return true
        blocked(BLOCK_REASON)
        return false
    }
}
