package com.agentdeck

import org.junit.Assert.*
import org.junit.Test

class CompanionInputTest {
    @Test fun keypadMatchesDisplayAndSound() {
        val expected=mapOf(2 to "brightness_down",8 to "brightness_up",4 to "volume_down",6 to "volume_up",5 to "volume_mute")
        expected.forEach { (digit,action) -> assertEquals(action,CompanionInput.quickAction(digit,null,false)) }
    }
    @Test fun physicalVolumeKeysAreLocal() {
        assertEquals("volume_down",CompanionInput.quickAction(null,Fn.MINUS,false))
        assertEquals("volume_up",CompanionInput.quickAction(null,Fn.PLUS,false))
        assertEquals("volume_mute",CompanionInput.quickAction(null,Fn.MUTE,false))
    }
    @Test fun drawerAlwaysHasAnExit() {
        assertEquals("quick_close",CompanionInput.quickAction(0,null,false))
        assertEquals("quick_close",CompanionInput.quickAction(null,null,true))
        assertEquals("quick_close",CompanionInput.quickAction(null,Fn.END,false))
    }
    @Test fun unknownKeysCannotTriggerPcActions() {
        assertNull(CompanionInput.quickAction(null,Fn.CALL,false))
        assertNull(CompanionInput.quickAction(9,null,false))
        assertNull(CompanionInput.quickAction(null,"unknown",false))
    }
    @Test fun shortcutsAreExplicit() {
        assertEquals("quick_pet",CompanionInput.quickAction(1,null,false))
        assertEquals("quick_settings",CompanionInput.quickAction(3,null,false))
        assertEquals("quick_keys",CompanionInput.quickAction(7,null,false))
    }
}
