package com.agentdeck.hotel

/**
 * The live audio route is the Fish-hosted interpreter only.
 * Never fall back to another provider. The release credential is injected into the
 * APK at build time; a staff-saved credential overrides it on one device; a
 * configured hotel service mints managed sessions instead.
 */
object AudioPolicy {
    const val LIVE_AUDIO_ENABLED = true
    const val BLOCK_REASON = "Live translation is unavailable. Ask staff to check the audio credential in Settings."

    /** Evaluate before permission/capture, credential loading, ticket minting or provider connect. */
    fun allowStart(blocked: (String) -> Unit): Boolean {
        if (LIVE_AUDIO_ENABLED) return true
        blocked(BLOCK_REASON)
        return false
    }
}
