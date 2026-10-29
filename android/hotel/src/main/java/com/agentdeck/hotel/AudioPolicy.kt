package com.agentdeck.hotel

/**
 * Local readiness accepts a saved device credential, an embedded testing key,
 * or an enrolled hotel service. An embedded key is visible to APK recipients.
 */
object AudioPolicy {
    const val LIVE_AUDIO_ENABLED = true
    const val BLOCK_REASON = "Live translation is unavailable. Ask staff to check the audio credential in Settings."

    /** Local readiness only. Returned source IDs are localized by the native message producer. */
    fun blockReason(backendConfigured: Boolean, directKey: SecretStore.Status, bundledKeyAvailable: Boolean,
                    directFishSelected: Boolean = false): String {
        if (!LIVE_AUDIO_ENABLED) return BLOCK_REASON
        if (TranslationRouting.useManagedConnection(backendConfigured, directFishSelected) ||
            directKey == SecretStore.Status.READY || bundledKeyAvailable) return ""
        return if (directKey == SecretStore.Status.ERROR)
            "The saved voice key could not be opened. Tap Set up translation to check it or save your original Fish Audio key again."
        else "One-time setup: save your Fish Audio API key to start translation."
    }

    /** Evaluate before permission/capture, credential loading, ticket minting or provider connect. */
    fun allowStart(blocked: (String) -> Unit): Boolean {
        if (LIVE_AUDIO_ENABLED) return true
        blocked(BLOCK_REASON)
        return false
    }
}
