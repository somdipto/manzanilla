package com.agentdeck.hotel

/** Caller holds its playback lock. A turn owns playback until generation AND playback end.
 * Concurrent translation is an explicit failure, never an arbitrary mix or a silently lost turn.
 */
internal class TurnPlaybackOwner {
    enum class Decision { ACCEPT, INTERRUPTED, CONFLICT, CLOSED }
    private var owner: String? = null
    private var complete = false
    private var closed = false
    private val interrupted = HashSet<String>()

    fun append(session: String, drained: Boolean): Decision {
        if (closed) return Decision.CLOSED
        if (session in interrupted) return Decision.INTERRUPTED
        if (complete && drained) { owner = null; complete = false }
        if (owner != null && (owner != session || complete)) return Decision.CONFLICT
        owner = session
        return Decision.ACCEPT
    }

    /** True only when this session owns the output being flushed. */
    fun interrupt(session: String): Boolean {
        if (closed) return false
        interrupted.add(session)
        if (owner != session) return false
        owner = null
        complete = false
        return true
    }

    /** Interrupted chunks remain fenced until their ordered provider turn-end boundary. */
    fun finish(session: String) {
        if (closed) return
        interrupted.remove(session)
        if (owner == session) complete = true
    }

    fun close() { closed = true; owner = null; interrupted.clear() }
}
