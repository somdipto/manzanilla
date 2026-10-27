package com.agentdeck.hotel

/**
 * Public APKs use a staff-saved device credential or an enrolled hotel service.
 * A configured hotel service mints managed sessions instead of exposing its key.
 */
object AudioPolicy {
    const val LIVE_AUDIO_ENABLED = true
    const val BLOCK_REASON = "Live translation is unavailable. Ask staff to check the audio credential in Settings."

    /** Local setup readiness only; it does not establish provider access. */
    fun blockReason(backendConfigured: Boolean, directKey: SecretStore.Status, bundledKeyAvailable: Boolean): String {
        if (!LIVE_AUDIO_ENABLED) return BLOCK_REASON
        if (backendConfigured || directKey == SecretStore.Status.READY || bundledKeyAvailable) return ""
        return if (directKey == SecretStore.Status.ERROR)
            "The saved voice key could not be opened. Tap Set up translation to check it using your device screen lock."
        else "One-time setup: save your Fish Audio API key. Use your normal device PIN or pattern to open setup."
    }

    /** Evaluate before permission/capture, credential loading, ticket minting or provider connect. */
    fun allowStart(blocked: (String) -> Unit): Boolean {
        if (LIVE_AUDIO_ENABLED) return true
        blocked(BLOCK_REASON)
        return false
    }
}
