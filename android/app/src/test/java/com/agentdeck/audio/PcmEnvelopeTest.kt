package com.agentdeck.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class PcmEnvelopeTest {
    private fun pcm(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample shr 8).toByte()
        }
    }

    @Test fun decodesSignedLittleEndianPcmAndNormalizesRms() {
        assertEquals(0f, PcmEnvelope.rms16le(pcm(0, 0)), 0f)
        assertEquals(0.5f, PcmEnvelope.rms16le(pcm(16384, -16384)), 0.00001f)
        assertEquals(64f / 32768f, PcmEnvelope.rms16le(byteArrayOf(0x40, 0)), 0.00001f)
        assertEquals(0.5f, PcmEnvelope.rms16le(byteArrayOf(0, 0x40)), 0.00001f)
        assertEquals(1f, PcmEnvelope.rms16le(pcm(-32768)), 0f)
        assertEquals(sqrt(0.5).toFloat(), PcmEnvelope.rms16le(pcm(-32768, 0)), 0.00001f)
        assertTrue(PcmEnvelope.rms16le(pcm(32767)) < 1f)
        assertEquals(0.5f, PcmEnvelope.rms16le(pcm(0, 16384, 0), 2, 2), 0f)
    }

    @Test fun emptyOddAndInvalidRangesAreSilent() {
        val bytes = pcm(16384)
        assertEquals(0f, PcmEnvelope.rms16le(byteArrayOf()), 0f)
        assertEquals(0f, PcmEnvelope.rms16le(byteArrayOf(1)), 0f)
        assertEquals(0f, PcmEnvelope.rms16le(bytes, -1, 2), 0f)
        assertEquals(0f, PcmEnvelope.rms16le(bytes, 0, 3), 0f)
        assertEquals(0f, PcmEnvelope.rms16le(bytes, 1, 2), 0f)
        assertEquals(0f, PcmEnvelope.rms16le(bytes, Int.MAX_VALUE, 2), 0f)
        assertEquals(0f, PcmEnvelope.rms16le(bytes, 0, 0), 0f)
    }

    @Test fun bufferedFutureSpeechDoesNotAnimateBeforePlaybackReachesIt() {
        val meter = PcmEnvelope()
        meter.written(pcm(0, 0), 0, 4)
        meter.written(pcm(-32768, -32768), 0, 4)
        assertEquals(0f, meter.currentEnergy(0, 0), 0f)
        assertEquals(0f, meter.currentEnergy(1, 50_000_000), 0f)
        assertTrue(meter.currentEnergy(2, 100_000_000) > 0.9f)
    }

    @Test fun attackAndReleaseAreSmoothedAndSilenceReachesZeroWithin150ms() {
        val meter = PcmEnvelope()
        meter.written(pcm(-32768, -32768), 0, 4)
        meter.written(pcm(0, 0), 0, 4)
        val attack = meter.currentEnergy(0, 0)
        assertTrue(attack > 0f && attack < 1f)
        val release = meter.currentEnergy(2, 50_000_000)
        assertTrue(release > 0f && release < attack)
        assertEquals(0f, meter.currentEnergy(3, 150_000_000), 0f)
    }

    @Test fun stalledPlaybackAndResetCannotLeaveEnergyStuck() {
        val meter = PcmEnvelope()
        meter.written(pcm(-32768, -32768), 0, 4)
        assertTrue(meter.currentEnergy(0, 0) > 0f)
        assertEquals(0f, meter.currentEnergy(0, 150_000_000), 0f)
        meter.reset()
        assertEquals(0f, meter.currentEnergy(0, 160_000_000), 0f)
        meter.written(pcm(16384), 0, 2)
        assertTrue(meter.currentEnergy(0, 170_000_000) > 0f)
        meter.reset()
        assertEquals(0f, meter.currentEnergy(0, 180_000_000), 0f)
    }

    @Test fun backwardsClockDoesNotChangeTheEnvelope() {
        val meter = PcmEnvelope()
        meter.written(pcm(16384, 16384), 0, 4)
        val before = meter.currentEnergy(0, 100_000_000)
        assertEquals(before, meter.currentEnergy(0, 50_000_000), 0f)
    }

    @Test fun boundedTimelineKeepsFrameAlignmentAfterOverflow() {
        val meter = PcmEnvelope()
        repeat(40) { meter.written(pcm(16384), 0, 2) }
        assertEquals(0f, meter.currentEnergy(0, 0), 0f)
        assertTrue(meter.currentEnergy(39, 50_000_000) > 0f)
        assertEquals(0f, meter.currentEnergy(40, 200_000_000), 0f)
    }
}
