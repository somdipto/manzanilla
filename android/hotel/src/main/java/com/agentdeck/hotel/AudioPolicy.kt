package com.agentdeck.hotel

/** No live route has verified end-to-end free-only eligibility. Never fall back to another provider. */
object AudioPolicy {
    const val LIVE_AUDIO_ENABLED = false
    const val BLOCK_REASON = "Live audio is awaiting verified free-only eligibility. Ask reception staff to use the on-screen requests and messages."

    /** Evaluate before permission/capture, credential loading, ticket minting or provider connect. */
    fun allowStart(blocked: (String) -> Unit): Boolean {
        if (LIVE_AUDIO_ENABLED) return true
        blocked(BLOCK_REASON)
        return false
    }
}
