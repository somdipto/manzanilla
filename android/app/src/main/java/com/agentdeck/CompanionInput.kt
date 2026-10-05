package com.agentdeck

/** Context-first keypad routing. A modal surface owns keys before global PC shortcuts. */
object CompanionInput {
    fun quickAction(digit:Int?, role:String?, pound:Boolean):String? {
        if(pound || digit==0 || role==Fn.END)return "quick_close"
        return when {
            role==Fn.MINUS || digit==4 -> "volume_down"
            role==Fn.PLUS || digit==6 -> "volume_up"
            role==Fn.MUTE || digit==5 -> "volume_mute"
            digit==2 -> "brightness_down"
            digit==8 -> "brightness_up"
            digit==1 -> "quick_pet"
            digit==3 -> "quick_settings"
            digit==7 -> "quick_keys"
            else -> null // Never leak an unhandled drawer key into a PC command.
        }
    }
}
