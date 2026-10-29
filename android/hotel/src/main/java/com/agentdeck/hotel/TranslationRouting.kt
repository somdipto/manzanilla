package com.agentdeck.hotel

/** Distribution applies to new starts only; existing reservations retain their cleanup credential. */
internal object TranslationRouting {
    fun useManagedConnection(backendConfigured: Boolean, directFishSelected: Boolean, distribution: String): Boolean =
        backendConfigured && (distribution == "hotel" || !directFishSelected)
}
