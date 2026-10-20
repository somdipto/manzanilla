package com.agentdeck.hotel

/** One interpreter reservation, including uncertain creation/end and failed local release.
 * Persist the reservation before any paid creation. A process restart must fail closed;
 * an absent response is not evidence that the provider never created a session.
 */
internal class FishSessionAdmission(restored: String?, private val save: (String?) -> Boolean) {
    enum class Remote { NOT_CREATED, OUTSTANDING, ENDING, ACKNOWLEDGED, UNCERTAIN }
    private var owner = restored
    // Restored only once per process. Android has released the dead process's local media.
    private var restoredReservation = restored
    private var localOff = restored != null
    private var remote = if (restored == null) Remote.NOT_CREATED else Remote.UNCERTAIN

    @Synchronized fun acquire(id: String): Boolean {
        if (owner != null || !save(id)) return false
        owner = id
        restoredReservation = null
        localOff = false
        remote = Remote.NOT_CREATED
        return true
    }
    @Synchronized fun requested(id: String) { if (owner == id) remote = Remote.OUTSTANDING }
    @Synchronized fun ending(id: String) { if (owner == id) remote = Remote.ENDING }
    /** Managed proof applies only to a received failed response, never a lost response. */
    @Synchronized fun rejected(id: String, managed: Boolean, status: Int, creationNotCreated: Boolean = false): Boolean {
        val safe = if (managed) (creationNotCreated && status !in 200..299) || status == 400 || status == 422 || status == 429
            else status == 400 || status == 401 || status == 402 || status == 403 || status == 404 || status == 409 || status == 415 || status == 422 || status == 429
        if (!safe || owner != id) return false
        remote = Remote.NOT_CREATED
        clearIfSettled()
        return true
    }
    @Synchronized fun localAudioState(): String = if (owner != null && !localOff) "unknown" else "off"
    @Synchronized fun localReleased(id: String, off: Boolean) {
        if (owner != id) return
        localOff = off
        clearIfSettled()
    }
    @Synchronized fun remoteEnded(id: String, acknowledged: Boolean) {
        if (owner != id) return
        remote = if (acknowledged) Remote.ACKNOWLEDGED else Remote.UNCERTAIN
        clearIfSettled()
    }
    @Synchronized fun retrySettledRelease() { if (owner != null) clearIfSettled() }
    @Synchronized fun recoveryReservation(): String? =
        owner?.takeIf { localOff && (remote == Remote.UNCERTAIN || (it == restoredReservation && remote == Remote.NOT_CREATED)) }
    @Synchronized fun beginRecovery(id: String): Boolean {
        if (owner != id || !localOff || remote != Remote.UNCERTAIN) return false
        remote = Remote.ENDING
        return true
    }
    /** Caller has checked persisted metadata: it is committed before any session dispatch. */
    @Synchronized fun releaseRestoredBeforeDispatch(id: String): Boolean {
        if (owner != id || restoredReservation != id || !localOff ||
            (remote != Remote.UNCERTAIN && remote != Remote.NOT_CREATED)) return false
        remote = Remote.NOT_CREATED
        clearIfSettled()
        return owner == null
    }
    @Synchronized fun reason(): String? = when {
        owner == null -> null
        !localOff -> "The previous audio release is not confirmed. No competing microphone will be opened."
        remote == Remote.UNCERTAIN -> "The previous provider session end is unconfirmed. Retry cleanup or ask staff to verify the provider session."
        remote == Remote.ACKNOWLEDGED -> "The provider ended, but ownership could not be cleared from device storage. Ask staff to check device storage."
        remote == Remote.NOT_CREATED -> "No provider session was created, but ownership could not be cleared from device storage. Ask staff to check device storage."
        else -> "The previous provider session is still being closed. The microphone is off; wait before starting again."
    }
    private fun clearIfSettled() {
        if (localOff && (remote == Remote.NOT_CREATED || remote == Remote.ACKNOWLEDGED) && save(null)) owner = null
    }
}
