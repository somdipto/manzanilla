package com.agentdeck.hotel

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPolicyTest {
    @Test fun approvedFishRouteNeverBlocksStart() {
        var blocked: String? = null
        assertTrue(AudioPolicy.LIVE_AUDIO_ENABLED)
        assertTrue(AudioPolicy.allowStart { blocked = it })
        assertNull(blocked)
    }
}
