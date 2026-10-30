package com.agentdeck.hotel

/** Terminal output generations cannot be resurrected by late packets, records or callbacks. */
internal class TranslationOutput {
    var generation: Long = 0
        private set
    private var terminal = false
    fun accept(number: Long, status: String): Boolean {
        if (number !in 1..0xffffffffL || number < generation) return false
        if (number > generation) { generation = number; terminal = false }
        if (status in setOf("complete", "interrupted", "error")) terminal = true
        return true
    }
    fun plays(number: Long): Boolean = number > 0 && number == generation && !terminal
    fun stale(number: Long): Boolean = number < generation || number == generation && terminal
}
