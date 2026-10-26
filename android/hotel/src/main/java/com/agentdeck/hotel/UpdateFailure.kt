package com.agentdeck.hotel

import java.io.IOException

/** Maps an update-check failure to a short, safe stage reference. Never carries URLs, hosts or exception payloads. */
object UpdateFailure {
    private val stageCode = Regex("^([a-z ]+) HTTP (\\d{3})$")

    fun describe(error: Throwable): String {
        var cause: Throwable? = error
        while (cause != null) {
            val match = stageCode.matchEntire(cause.message ?: "")
            if (match != null) {
                return "Could not check for updates (${match.groupValues[1]}, HTTP ${match.groupValues[2]}). Tap to retry."
            }
            cause = cause.cause
        }
        if (isNetwork(error)) return "Could not check for updates (network). Tap to retry."
        return "Could not check for updates (release data). Tap to retry."
    }

    private fun isNetwork(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is IOException) return true
            cause = cause.cause
        }
        return false
    }
}
