package com.agentdeck.hotel

/** A personal Fish selection must not silently use a previously saved hotel service. */
internal object TranslationRouting {
    fun useManagedConnection(backendConfigured: Boolean, directFishSelected: Boolean): Boolean =
        backendConfigured && !directFishSelected
}
