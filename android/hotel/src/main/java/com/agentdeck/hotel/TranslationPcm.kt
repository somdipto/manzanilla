package com.agentdeck.hotel

/** Application-owned wire framing and render-credit ledger; no PCM payload copies. */
internal class TranslationPcm(val generation: Long) {
    data class Packet(val generation: Long, val sequence: Long, val bytes: ByteArray) {
        val length: Int get() = bytes.size - 16
    }
    private data class End(val sequence: Long, val samples: Long)
    private val pending = ArrayDeque<End>()
    private var next = 0L
    private var samples = 0L
    private var eof = false
    var complete = false
        private set

    fun accept(packet: Packet) {
        require(packet.generation == generation && packet.sequence == next && !eof)
        require(pending.size < if (packet.length == 0) 7 else 6)
        samples += packet.length / 2
        pending.addLast(End(next++, samples))
        eof = packet.length == 0
    }

    /** Only the AudioTrack playback head, not writes or queue arrival, may advance credit. */
    fun rendered(head: Long): Long? {
        var sequence: Long? = null
        while (pending.isNotEmpty() && pending.first().samples <= head) sequence = pending.removeFirst().sequence
        if (eof && pending.isEmpty()) complete = true
        return sequence
    }

    companion object {
        private fun uint(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (i in offset until offset + 4) value = (value shl 8) or (bytes[i].toLong() and 255)
            return value
        }
        fun generation(bytes: ByteArray): Long {
            require(bytes.size >= 16 && bytes[0] == 77.toByte() && bytes[1] == 90.toByte() &&
                bytes[2] == 80.toByte() && bytes[3] == 49.toByte())
            return uint(bytes, 4).also { require(it > 0) }
        }
        fun parse(bytes: ByteArray): Packet {
            val generation = generation(bytes)
            require(bytes.size <= 1936 && bytes.size % 2 == 0 && uint(bytes, 12) == 24000L)
            return Packet(generation, uint(bytes, 8), bytes)
        }
    }
}
