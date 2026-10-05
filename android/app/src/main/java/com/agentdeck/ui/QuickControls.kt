package com.agentdeck.ui

import android.graphics.*
import com.agentdeck.DeckState

/** Code-drawn frosted material: no live blur, network assets, or full-screen bitmap copies. */
class QuickControls {
    private val ink = Color.rgb(234,245,253)
    private val aura = FrostedAura()
    private fun paint(color:Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color=color }
    private fun text(size:Float) = paint(ink).apply {
        textSize=size; typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    }
    fun draw(c:Canvas,w:Float,h:Float,st:DeckState,now:Long,zone:(RectF,String)->Unit) {
        val top=64f
        val body=RectF(10f,top,w-10f,h+30f)
        val shape=Path().apply { addRoundRect(body,34f,34f,Path.Direction.CW) }
        c.save(); c.clipPath(shape)
        c.drawRect(body,paint(Color.rgb(21,39,61)))
        aura.draw(c,body,now)
        c.drawRect(body,paint(Color.argb(12,204,225,245)))
        c.restore()
        c.drawRoundRect(body,34f,34f,paint(Color.argb(75,212,235,255)).apply { style=Paint.Style.STROKE;strokeWidth=1.5f })
        c.drawRoundRect(RectF(w/2-27f,76f,w/2+27f,80f),2f,2f,paint(Color.argb(150,188,219,238)))
        c.drawText("Quick controls",31f,119f,text(27f))
        c.drawText("Orange · ${if(st.connected) "PC connected" else "PC offline"}",32f,144f,text(13f))
        fun button(r:RectF,label:String,id:String) {
            c.drawRoundRect(r,19f,19f,paint(Color.argb(25,223,242,255)))
            val p=text(15f);c.drawText(label,r.centerX()-p.measureText(label)/2,r.centerY()+5f,p)
            zone(r,id)
        }
        button(RectF(w-166f,94f,w-30f,135f),"0 / #  Close","quick_close")
        val gap=18f;val col=(w-64f-gap)/2
        fun slider(left:Float,title:String,value:Int,id:String,minus:String,plus:String) {
            val r=RectF(left,165f,left+col,335f)
            c.drawRoundRect(r,27f,27f,paint(Color.argb(18,223,242,255)))
            c.drawText(title,left+19f,196f,text(18f))
            val valText="$value%";val vp=text(29f)
            c.drawText(valText,r.right-20f-vp.measureText(valText),202f,vp)
            val track=RectF(left+25f,234f,r.right-25f,247f)
            c.drawRoundRect(track,7f,7f,paint(Color.argb(43,209,230,249)))
            val cx=track.left+track.width()*value.coerceIn(0,100)/100f
            c.drawRoundRect(RectF(track.left,track.top,cx,track.bottom),7f,7f,paint(Color.rgb(126,225,219)))
            c.drawCircle(cx,track.centerY(),13f,paint(Color.WHITE))
            zone(RectF(track.left,219f,track.right,265f),"slider:$id")
            button(RectF(left+15f,281f,left+col/2-5f,321f),minus,if(id=="volume") "volume_down" else "brightness_down")
            button(RectF(left+col/2+5f,281f,r.right-15f,321f),plus,if(id=="volume") "volume_up" else "brightness_up")
        }
        // Same directional keypad mapping as the existing Display & Sound screen.
        slider(32f,"Volume",st.deviceVolume,"volume","4  Quieter","6  Louder")
        slider(32f+col+gap,"Brightness",st.deviceBrightness,"brightness","2  Dimmer","8  Brighter")
        val cell=(w-82f)/4
        val shortcuts=listOf(Triple("5  ${if(st.deviceVolume==0) "Unmute" else "Mute"}","volume_mute",0),
            Triple("1  Suhair","quick_pet",1),Triple("3  Settings","quick_settings",2),Triple("7  Learn keys","quick_keys",3))
        shortcuts.forEach{(label,id,i)->button(RectF(32f+i*(cell+6f),352f,32f+i*(cell+6f)+cell,403f),label,id)}
        val hint=text(13f)
        c.drawText("− / +  Volume     ·     Swipe down to close     ·     Controls affect Orange",32f,438f,hint)
    }
}
