package com.agentdeck.hotel

import org.junit.Assert.*
import org.junit.Test

class TurnPlaybackOwnerTest {
    @Test fun chunksFromOneTurnStayOrderedAndOtherDirectionFailsRatherThanMixing() {
        val owner = TurnPlaybackOwner()
        val played = ArrayList<Int>()
        fun append(side: String, chunk: Int): TurnPlaybackOwner.Decision {
            val result = owner.append(side, drained = false)
            if (result == TurnPlaybackOwner.Decision.ACCEPT) played.add(chunk)
            return result
        }
        assertEquals(TurnPlaybackOwner.Decision.ACCEPT, append("guest", 1))
        assertEquals(TurnPlaybackOwner.Decision.CONFLICT, append("staff", 90))
        assertEquals(TurnPlaybackOwner.Decision.ACCEPT, append("guest", 2))
        assertEquals(listOf(1, 2), played)
    }

    @Test fun takeoverWaitsForBothTurnCompleteAndActualPlaybackDrain() {
        val owner = TurnPlaybackOwner()
        assertEquals(TurnPlaybackOwner.Decision.ACCEPT, owner.append("guest", true))
        assertEquals(TurnPlaybackOwner.Decision.CONFLICT, owner.append("staff", true))
        owner.finish("guest")
        assertEquals(TurnPlaybackOwner.Decision.CONFLICT, owner.append("staff", false))
        assertEquals(TurnPlaybackOwner.Decision.ACCEPT, owner.append("staff", true))
        assertEquals(TurnPlaybackOwner.Decision.ACCEPT, owner.append("staff", false))
    }

    @Test fun interruptionFromNonOwnerCannotFlushCurrentSpeech() {
        val owner = TurnPlaybackOwner()
        owner.append("guest", true)
        assertFalse(owner.interrupt("staff"))
        assertEquals(TurnPlaybackOwner.Decision.ACCEPT, owner.append("guest", false))
        assertEquals(TurnPlaybackOwner.Decision.INTERRUPTED, owner.append("staff", false))
        owner.finish("staff")
        assertEquals(TurnPlaybackOwner.Decision.CONFLICT, owner.append("staff", false))
    }

    @Test fun interruptedChunksCannotReclaimPlaybackAfterOtherSessionTakesOver() {
        val owner = TurnPlaybackOwner()
        owner.append("guest", true)
        assertTrue(owner.interrupt("guest"))
        assertEquals(TurnPlaybackOwner.Decision.ACCEPT, owner.append("staff", true))
        assertEquals(TurnPlaybackOwner.Decision.INTERRUPTED, owner.append("guest", false))
        owner.finish("guest")
        assertEquals(TurnPlaybackOwner.Decision.CONFLICT, owner.append("guest", false))
        owner.finish("staff")
        assertEquals(TurnPlaybackOwner.Decision.ACCEPT, owner.append("guest", true))
    }

    @Test fun closeFencesLateOutputInterruptionAndTurnCompletion() {
        val owner = TurnPlaybackOwner()
        owner.append("guest", true)
        owner.close()
        assertFalse(owner.interrupt("guest"))
        owner.finish("guest")
        assertEquals(TurnPlaybackOwner.Decision.CLOSED, owner.append("guest", true))
        assertEquals(TurnPlaybackOwner.Decision.CLOSED, owner.append("staff", true))
        owner.close()
    }
}
