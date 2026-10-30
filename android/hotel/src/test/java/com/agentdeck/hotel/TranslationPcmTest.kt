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

    @Test fun unconsumedInputCannotGrowAnUnboundedQueue() {
        val playback = TranslationPcm(1)
        repeat(6) { playback.accept(TranslationPcm.parse(packet(it))) }
        assertThrows(IllegalArgumentException::class.java) { playback.accept(TranslationPcm.parse(packet(6))) }
        playback.accept(TranslationPcm.parse(packet(6, samples = 0)))
        assertFalse(playback.complete)
        assertEquals(6L, playback.rendered(5760))
    }
}
