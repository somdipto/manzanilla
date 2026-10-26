package com.agentdeck.hotel

/**
 * In-memory diagnostic timeline for the private test build.
 * Timestamps and event endpoint names only: never audio, transcripts, captions,
 * keys or device identifiers. Entries die with the process; nothing is persisted.
 */
internal object VoiceDiagnostics {
    private const val MAX_POINTS = 600

    data class Entry(
        val sessionId: String,
        val generation: Int,
        val sequence: Long,
        val endpoint: String,
        val atMs: Long,
        val boundaryEstimateMs: Long?,
    )

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    fun record(sessionId: String, generation: Int, point: AudioTurnTiming.Point) {
        synchronized(lock) {
            if (entries.size >= MAX_POINTS) entries.removeFirst()
            entries.addLast(
                Entry(sessionId, generation, point.sequence, point.endpoint, point.atMs, point.boundaryEstimateMs)
            )
        }
    }

    fun clear() = synchronized(lock) { entries.clear() }

    /** One text block per session, times relative to that session's first event. */
    fun render(): String {
        val all = synchronized(lock) { entries.toList() }
        if (all.isEmpty()) return "No diagnostic events yet. Run a translation session, then return here."
        val out = StringBuilder()
        out.append("Voice diagnostics (test build)\n")
        out.append("Times are milliseconds from each session's first event. Speech boundaries are RMS estimates, ")
        out.append("not acoustic truth. No audio or speech content is recorded.\n")
        all.groupBy { it.sessionId to it.generation }.toSortedMap(compareBy({ it.first }, { it.second }))
            .forEach { (key, points) ->
                val base = points.minOf { it.atMs }
                out.append('\n').append("Session ${key.first} (generation ${key.second})\n")
                points.forEach { p ->
                    out.append(String.format("+%06d ms  %s", p.atMs - base, p.endpoint))
                    if (p.boundaryEstimateMs != null) {
                        out.append(String.format("  (boundary estimate +%d ms)", p.boundaryEstimateMs - base))
                    }
                    out.append('\n')
                }
            }
        return out.toString()
    }
}
