package com.agentdeck.hotel

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class TranslationPcmTest {
    private fun packet(sequence: Int, samples: Int = 960, generation: Int = 1, rate: Int = 24000): ByteArray =
        ByteBuffer.allocate(16 + samples * 2).put(byteArrayOf(77, 90, 80, 49))
            .putInt(generation).putInt(sequence).putInt(rate).array()

    @Test fun creditTracksRenderedSamplesIncludingPartialTailAndEof() {
        val playback = TranslationPcm(1)
        repeat(6) { playback.accept(TranslationPcm.parse(packet(it))) }
        assertNull(playback.rendered(959))
        assertEquals(0L, playback.rendered(960))
        assertEquals(4L, playback.rendered(4800))
        playback.accept(TranslationPcm.parse(packet(6, samples = 37)))
        playback.accept(TranslationPcm.parse(packet(7, samples = 0)))
        assertEquals(5L, playback.rendered(5760))
        assertFalse(playback.complete)
        assertNull(playback.rendered(5796))
        assertEquals(7L, playback.rendered(5797))
        assertTrue(playback.complete)
        assertNull(playback.rendered(6000))
    }

    @Test fun rejectsWrongSequenceGenerationRateSizeAndDuplicateEof() {
        val playback = TranslationPcm(1)
        assertThrows(IllegalArgumentException::class.java) { playback.accept(TranslationPcm.parse(packet(1))) }
        assertThrows(IllegalArgumentException::class.java) { playback.accept(TranslationPcm.parse(packet(0, generation = 2))) }
        assertThrows(IllegalArgumentException::class.java) { TranslationPcm.parse(packet(0, rate = 16000)) }
        assertThrows(IllegalArgumentException::class.java) { TranslationPcm.parse(packet(0, samples = 961)) }
        assertThrows(IllegalArgumentException::class.java) { TranslationPcm.parse(packet(0).copyOf(17)) }
        assertThrows(IllegalArgumentException::class.java) { TranslationPcm.parse(packet(0, generation = 0)) }
        playback.accept(TranslationPcm.parse(packet(0, samples = 0)))
        assertThrows(IllegalArgumentException::class.java) { playback.accept(TranslationPcm.parse(packet(1, samples = 0))) }
        assertFalse(playback.complete)
        assertEquals(0L, playback.rendered(0))
        assertTrue(playback.complete)
    }

    @Test fun payloadIsRetainedWithoutCopyAndHeaderIsUnsigned() {
        val bytes = packet(-1, generation = -1)
        val parsed = TranslationPcm.parse(bytes)
        assertSame(bytes, parsed.bytes)
        assertEquals(4294967295L, parsed.generation)
        assertEquals(4294967295L, parsed.sequence)
    }

    @Test fun parsesPacketsProducedByTheWorkerPacker() {
        // Exact bytes from voice_worker.contract.audio_packet(7, 0, bytes 0x00..0x05) and
        // its EOF packet. The managed route credits playback only when the worker and this
        // renderer agree on the framing byte for byte, so the layout is pinned here.
        val playback = TranslationPcm(7)
        val packet = TranslationPcm.parse(byteArrayOf(
            0x4d, 0x5a, 0x50, 0x31, 0x00, 0x00, 0x00, 0x07, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x5d, 0xc0.toByte(), 0x00, 0x01, 0x02, 0x03, 0x04, 0x05))
        assertEquals(7L, packet.generation)
        assertEquals(0L, packet.sequence)
        assertEquals(6, packet.length)
        playback.accept(packet)
        assertNull(playback.rendered(2))
        assertEquals(0L, playback.rendered(3))
        playback.accept(TranslationPcm.parse(byteArrayOf(
            0x4d, 0x5a, 0x50, 0x31, 0x00, 0x00, 0x00, 0x07, 0x00, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x5d, 0xc0.toByte())))
        assertFalse(playback.complete)
        assertEquals(1L, playback.rendered(3))
        assertTrue(playback.complete)
    }

    @Test fun unconsumedInputCannotGrowAnUnboundedQueue() {
        val playback = TranslationPcm(1)
        repeat(6) { playback.accept(TranslationPcm.parse(packet(it))) }
        assertThrows(IllegalArgumentException::class.java) { playback.accept(TranslationPcm.parse(packet(6))) }
        playback.accept(TranslationPcm.parse(packet(6, samples = 0)))
        assertFalse(playback.complete)
        assertEquals(6L, playback.rendered(5760))
    }
}
