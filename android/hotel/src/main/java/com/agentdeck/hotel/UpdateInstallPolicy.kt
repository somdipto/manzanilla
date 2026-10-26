package com.agentdeck.hotel

/** One downloaded update gets one automatic installation attempt.
 * Android may still require confirmation. A refusal or dismissed prompt must not
 * become a foreground/watchdog retry loop; an explicit tap can retry safely.
 */
internal class UpdateInstallPolicy {
    enum class Decision { DEFER, PERMISSION, INSTALL, IN_PROGRESS, MANUAL_RETRY }

    private var attemptedAutomatically = false
    @Volatile var reserved = false
        private set

    fun reserve(idle: Boolean, permitted: Boolean, explicit: Boolean = false): Decision {
        if (reserved) return Decision.IN_PROGRESS
        if (!idle) return Decision.DEFER
        if (!explicit && attemptedAutomatically) return Decision.MANUAL_RETRY
        if (!permitted) return Decision.PERMISSION
        reserved = true
        attemptedAutomatically = true
        return Decision.INSTALL
    }

    /** Release local admission after Android requests user action or returns a result. */
    fun settled() { reserved = false }

    /** A prior process already dispatched this immutable update; its next attempt is manual. */
    fun restoreAttempted() { attemptedAutomatically = true }

    /** New/cancelled immutable download, never called merely because a prompt closes. */
    fun reset() { reserved = false; attemptedAutomatically = false }
}
