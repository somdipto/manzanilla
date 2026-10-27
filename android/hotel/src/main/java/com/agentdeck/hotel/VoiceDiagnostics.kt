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

    /** One text block per session, times relative to that session's first event.
     * Input events observed while an output group is open are flagged as overlap:
     * the caller was heard over the agent's own speech. This is a timeline join of
     * recorded receipts, not acoustic proof of what the provider transcribed. */
    fun render(): String {
        val all = synchronized(lock) { entries.toList() }
        if (all.isEmpty()) return "No diagnostic events yet. Run a translation session, then return here."
        val out = StringBuilder()
        out.append("Voice diagnostics (test build)\n")
        out.append("Times are milliseconds from each session's first event. Speech boundaries are RMS estimates, ")
        out.append("not acoustic truth. No audio or speech content is recorded. ")
        out.append("[overlap] marks caller input observed while agent output was active.\n")
        all.groupBy { it.sessionId to it.generation }.toSortedMap(compareBy({ it.first }, { it.second }))
            .forEach { (key, points) ->
                val base = points.minOf { it.atMs }
                var outputActive = false
                var overlapCount = 0
                val lines = points.map { p ->
                    val overlap = when {
                        p.endpoint in OUTPUT_ACTIVE_ENDPOINTS -> { outputActive = true; false }
                        p.endpoint in OUTPUT_END_ENDPOINTS -> { outputActive = false; false }
                        outputActive && p.endpoint in INPUT_ENDPOINTS -> true
                        else -> false
                    }
                    if (overlap) overlapCount++
                    Triple(p, overlap, base)
                }
                out.append('\n').append("Session ${key.first} (generation ${key.second})")
                if (overlapCount > 0) out.append(" - overlap events: $overlapCount")
                out.append('\n')
                lines.forEach { (p, overlap, b) ->
                    out.append(String.format("+%06d ms  %s", p.atMs - b, p.endpoint))
                    if (overlap) out.append("  [overlap]")
                    if (p.boundaryEstimateMs != null) {
                        out.append(String.format("  (boundary estimate +%d ms)", p.boundaryEstimateMs - b))
                    }
                    out.append('\n')
                }
            }
        return out.toString()
    }

    private val OUTPUT_ACTIVE_ENDPOINTS = setOf(
        "raw_pcm_received",
        "raw_pcm_first_played_head_observed",
        "sdk_output_state_started_received",
        "sdk_decoded_received_not_audible",
        "sdk_non_silent_decoded_received_not_audible",
    )

    private val OUTPUT_END_ENDPOINTS = setOf(
        "provider_output_turn_complete_received",
        "sdk_output_state_ended_received",
        "output_interrupted_received",
    )

    private val INPUT_ENDPOINTS = setOf(
        "input_activity_started",
        "input_speech_boundary_estimate",
        "provider_input_final_received",
    )
}
