package com.agentdeck.ui

import android.graphics.*
import com.agentdeck.DeckState
import java.util.Calendar
import kotlin.math.*

/** The home-screen information instrument; all actions use the existing bridge routes. */
class DockInfoPane {
    private val aura = FrostedAura()
    private val white = Color.rgb(241, 247, 255)
    private val muted = Color.rgb(174, 199, 222)
    private val mint = Color.rgb(132, 241, 208)
    private val face = Typeface.create("sans-serif", Typeface.NORMAL)
    private var previousCard = -1
    private var changedAt = 0L
    private val dialValues = floatArrayOf(-1f, -1f)
    private var lastFrame = 0L

    private fun text(size: Float, color: Int = white) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; textSize = size; typeface = face
    }
    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private fun line(color: Int, size: Float) = fill(color).apply {
        style = Paint.Style.STROKE; strokeWidth = size; strokeCap = Paint.Cap.ROUND
    }

    fun draw(c: Canvas, width: Float, st: DeckState, now: Long,
        zone: (RectF, String) -> Unit,
        dots: (Canvas, String, Float, Float, Float, Int) -> Unit) {
        val card = st.statusCardIndex.coerceIn(0, 2)
        if (card != previousCard) { previousCard = card; changedAt = now }
        val dt = if (lastFrame == 0L) 0f else ((now-lastFrame)/1000f).coerceIn(0f,.1f)
        lastFrame = now
        val bounds = RectF(12f,12f,width-12f,250f)
        val material = fill(white).apply {
            shader = LinearGradient(12f,12f,width,250f,
                intArrayOf(Color.argb(240,22,43,65),Color.argb(220,20,49,71),Color.argb(239,29,37,61)),
                null,Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(RectF(12f,18f,width-12f,256f),31f,31f,fill(Color.argb(26,0,8,20)))
        c.drawRoundRect(bounds,31f,31f,material)
        val clip = Path().apply { addRoundRect(bounds,31f,31f,Path.Direction.CW) }
        c.save(); c.clipPath(clip)
        aura.draw(c,bounds,now)
        c.restore()
        c.drawRoundRect(bounds,31f,31f,line(Color.argb(70,212,235,255),1f))
        zone(bounds,"status_expand")

        val time=Calendar.getInstance().let { "%02d:%02d".format(it.get(Calendar.HOUR_OF_DAY),it.get(Calendar.MINUTE)) }
        dots(c,time,35f,33f,2.05f,white)
        val connectedColor = if(st.connected) mint else Color.rgb(255,184,133)
        c.drawCircle(37f,86f,3.5f,fill(connectedColor))
        c.drawText(if(st.connected) "PC connected" else "PC offline",49f,91f,text(13f,connectedColor))
        val shortLabel=if(st.shortLimitWindowMins>0) "${st.shortLimitWindowMins/60}h left" else "5h left"
        if(st.shortLimitRemaining>=0) {
            dial(c,69f,156f,shortLabel,st.shortLimitRemaining,0,dt,Color.rgb(138,221,253),dots)
            if(st.weeklyLimitRemaining>=0) dial(c,166f,156f,"Week left",st.weeklyLimitRemaining,1,dt,Color.rgb(218,193,255),dots)
        } else {
            dial(c,117f,156f,"Week left",st.weeklyLimitRemaining,1,dt,Color.rgb(218,193,255),dots)
        }
        c.drawText(if(st.shortLimitRemaining<0 && st.weeklyLimitRemaining<0) "Limits unavailable" else "Account allowance",
            35f,224f,text(11.5f,muted))
        c.drawLine(225f,35f,225f,227f,line(Color.argb(35,231,245,255),1f))

        // Large, directly selectable tabs, instead of non-interactive labels.
        val tabLabels=arrayOf("Updates","Media","Device")
        val indices=intArrayOf(1,0,2)
        tabLabels.forEachIndexed { i,label ->
            val tab=RectF(249f+i*107f,27f,349f+i*107f,60f)
            c.drawRoundRect(tab,16.5f,16.5f,fill(if(card==indices[i]) Color.argb(229,225,239,250) else Color.argb(12,255,255,255)))
            val p=text(13f,if(card==indices[i])Color.rgb(28,46,65)else muted)
            c.drawText(label,tab.centerX()-p.measureText(label)/2f,48f,p)
            zone(tab,"status_tab:${indices[i]}")
        }
        c.drawText(if(card==1) st.pcNotificationApp.ifBlank { "COMPANION" }.uppercase() else if(card==0) "NOW PLAYING" else "CONNECTION",
            253f,85f,text(10.5f,mint))
        val progress=((now-changedAt)/340f).coerceIn(0f,1f)
        c.save()
        c.clipRect(242f,92f,width-25f,195f)
        c.translate((1f-progress).pow(3)*18f*st.statusCardDirection,0f)
        val headline: String
        val detail: String
        when(card) {
            0 -> { headline=if(st.mediaActive) st.mediaTitle.ifBlank { "Windows media" } else "A little room for music."
                detail=if(st.mediaActive) listOf(st.mediaArtist,st.mediaAlbum).filter { it.isNotBlank() }.joinToString(" · ") else "Playback and album art appear here when you start listening." }
            1 -> { headline=st.pcNotificationBody.ifBlank { st.nowTask.ifBlank { st.pcNotificationTitle.ifBlank { "Ready when you are." } } }
                detail=if(st.pcNotificationBody.isNotBlank()) "Latest message · Open to read and respond" else st.nowDetail.ifBlank { "Your AI updates will arrive here while you work." } }
            else -> { headline=if(st.connected) "Connected to ${st.bridgeName.ifBlank { "your PC" }}" else "Let's reconnect your PC."
                detail=if(st.connected) "Volume ${st.deviceVolume}%  ·  Brightness ${st.deviceBrightness}%" else "Start the Manzanilla Bridge on your computer." }
        }
        drawLines(c,headline,253f,116f,width-283f,25f,2)
        val p=text(14f,muted)
        c.drawText(ellipsize(detail,p,width-283f),253f,181f,p)
        c.restore()
        fun button(left:Float,right:Float,label:String,id:String,primary:Boolean=false) {
            val r=RectF(left,201f,right,236f)
            c.drawRoundRect(r,17.5f,17.5f,fill(if(primary) mint else Color.argb(22,240,248,255)))
            val p=text(13f,if(primary)Color.rgb(17,53,50)else white)
            c.drawText(label,r.centerX()-p.measureText(label)/2f,223f,p);zone(r,id)
        }
        when(card) {
            1 -> { button(250f,409f,"Read & reply  ↗","status_expand",true)
                if(st.pcNotificationApp.lowercase() in setOf("chatgpt", "codex")) button(420f,554f,"Open on PC","chat_preview_open_pc") }
            0 -> { if(st.mediaActive) { button(250f,336f,"Previous","media_previous")
                    button(345f,471f,if(st.mediaStatus.contains("playing",true))"Pause" else "Play","media_toggle",true)
                    button(480f,555f,"Next","media_next") }
                else button(250f,410f,"Media details  ↗","status_expand",true) }
            else -> button(250f,449f,"Connection & controls  ↗","status_expand",true)
        }
    }

    private fun dial(c:Canvas,cx:Float,cy:Float,label:String,value:Int,index:Int,dt:Float,color:Int,
        dots:(Canvas,String,Float,Float,Float,Int)->Unit) {
        val radius=33f
        c.drawCircle(cx,cy,radius,line(Color.argb(29,224,238,252),4f))
        if(value>=0) {
            if(dialValues[index]<0)dialValues[index]=value.toFloat()
            dialValues[index]+=(value-dialValues[index])*(1-exp(-dt*8f))
            c.drawArc(RectF(cx-radius,cy-radius,cx+radius,cy+radius),-90f,dialValues[index]*3.6f,false,line(color,4f))
            val shown="$value%";val r=if(value==100)1.18f else 1.35f
            val w=(shown.length*4-1)*r*2.65f
            dots(c,shown,cx-w/2,cy-r*5.3f,r,white)
        } else { dialValues[index]=-1f;val p=text(23f,muted);c.drawText("—",cx-p.measureText("—")/2,cy+7,p) }
        val p=text(12f,muted);c.drawText(label,cx-p.measureText(label)/2,cy+52,p)
    }

    private fun ellipsize(s:String,p:Paint,width:Float):String {
        val clean=s.replace(Regex("\\s+")," ").trim()
        if(p.measureText(clean)<=width)return clean
        val n=p.breakText(clean,true,(width-p.measureText("…")).coerceAtLeast(1f),null)
        return clean.take(n).trimEnd()+"…"
    }
    private fun drawLines(c:Canvas,s:String,x:Float,y:Float,width:Float,size:Float,maxLines:Int) {
        val p=text(size);var rest=s.replace(Regex("\\s+")," ").trim()
        repeat(maxLines){i->
            if(rest.isEmpty())return
            if(i==maxLines-1){c.drawText(ellipsize(rest,p,width),x,y+i*(size+7),p);return}
            var n=p.breakText(rest,true,width,null).coerceAtLeast(1)
            if(n<rest.length) { val space=rest.lastIndexOf(' ',n);if(space>0)n=space }
            c.drawText(rest.take(n),x,y+i*(size+7),p);rest=rest.drop(n).trimStart()
        }
    }
}
