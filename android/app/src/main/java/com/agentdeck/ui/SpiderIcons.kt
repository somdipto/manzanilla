package com.agentdeck.ui

import android.graphics.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Animated comic/web icon family drawn as resolution-independent vector geometry. */
object SpiderIcons {
    private val red = Color.rgb(225, 31, 54)
    private val blue = Color.rgb(48, 112, 214)
    private val cream = Color.rgb(246, 244, 232)
    private val ink = Color.rgb(7, 17, 38)

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = width
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    fun draw(c: Canvas, id: String, r: RectF, now: Long, selected: Boolean = false) {
        val cx = r.centerX(); val cy = r.centerY(); val u = minOf(r.width(), r.height()) / 100f
        val phase = now / 1000.0
        c.drawCircle(cx, cy, 45f * u, fill(Color.argb(235, 8, 20, 47)))
        c.drawCircle(cx, cy, 44f * u, stroke(if (selected) cream else red, 5f * u))

        // The lightweight outer web rotates; the symbol remains still and legible.
        c.save(); c.rotate((phase * 16.0).toFloat(), cx, cy)
        val web = stroke(Color.argb(175, 126, 176, 255), 2.2f * u)
        for (i in 0 until 8) {
            val a = i * PI * 2 / 8
            c.drawLine(cx + cos(a).toFloat() * 25f * u, cy + sin(a).toFloat() * 25f * u,
                cx + cos(a).toFloat() * 40f * u, cy + sin(a).toFloat() * 40f * u, web)
        }
        c.drawCircle(cx, cy, 31f * u, web); c.drawCircle(cx, cy, 39f * u, web)
        c.restore()

        val p = stroke(cream, 7f * u)
        when (id) {
            "chatgpt" -> {
                val b = RectF(cx - 25f*u, cy - 19f*u, cx + 25f*u, cy + 17f*u)
                c.drawRoundRect(b, 12f*u, 12f*u, p)
                c.drawLine(cx - 11f*u, b.bottom, cx - 20f*u, cy + 28f*u, p)
                for (i in -1..1) c.drawCircle(cx + i*13f*u, cy, 3.5f*u, fill(red))
            }
            "codex" -> {
                c.drawLine(cx-25f*u,cy,cx-8f*u,cy-18f*u,p); c.drawLine(cx-25f*u,cy,cx-8f*u,cy+18f*u,p)
                c.drawLine(cx+25f*u,cy,cx+8f*u,cy-18f*u,p); c.drawLine(cx+25f*u,cy,cx+8f*u,cy+18f*u,p)
                c.drawLine(cx+5f*u,cy-25f*u,cx-6f*u,cy+25f*u,stroke(red,5f*u))
            }
            "claude", "themes" -> drawMask(c, cx, cy, u, phase)
            "wispr" -> for(i in -3..3) {
                val h=(12+26*(1-kotlin.math.abs(i)/4f))*(.75+.25*kotlin.math.abs(sin(phase*3+i))).toFloat()*u
                c.drawLine(cx+i*9f*u,cy-h/2,cx+i*9f*u,cy+h/2,stroke(if(i%2==0) red else cream,5f*u))
            }
            "stream" -> for(y in -1..1) for(x in -1..1)
                c.drawRoundRect(RectF(cx+(x*16-5)*u,cy+(y*16-5)*u,cx+(x*16+5)*u,cy+(y*16+5)*u),3f*u,3f*u,fill(if(x==0&&y==0) red else cream))
            "tasks" -> { c.drawCircle(cx,cy,26f*u,p); c.drawLine(cx-17f*u,cy,cx-4f*u,cy+13f*u,p); c.drawLine(cx-4f*u,cy+13f*u,cx+22f*u,cy-18f*u,p) }
            "files" -> { val f=Path().apply{moveTo(cx-28f*u,cy-20f*u);lineTo(cx-5f*u,cy-20f*u);lineTo(cx+5f*u,cy-11f*u);lineTo(cx+28f*u,cy-11f*u);lineTo(cx+28f*u,cy+23f*u);lineTo(cx-28f*u,cy+23f*u);close()};c.drawPath(f,p) }
            "more" -> for(i in -1..1)c.drawCircle(cx+i*17f*u,cy,6f*u,fill(if(i==0)red else cream))
            "comet", "chrome" -> { c.drawCircle(cx,cy,25f*u,p); c.drawArc(RectF(cx-25f*u,cy-12f*u,cx+25f*u,cy+12f*u),0f,360f,false,p); c.drawLine(cx,cy-25f*u,cx,cy+25f*u,p) }
            "spotify" -> for(i in 0..2)c.drawArc(RectF(cx-27f*u,cy+(-20+i*10)*u,cx+27f*u,cy+(12+i*10)*u),205f,130f,false,stroke(if(i==0)red else cream,5f*u))
            "camera" -> { c.drawRoundRect(RectF(cx-29f*u,cy-20f*u,cx+29f*u,cy+22f*u),7f*u,7f*u,p);c.drawCircle(cx,cy+1f*u,12f*u,p);c.drawLine(cx-17f*u,cy-20f*u,cx-8f*u,cy-29f*u,p);c.drawLine(cx-8f*u,cy-29f*u,cx+7f*u,cy-29f*u,p) }
            "pet" -> { c.drawOval(RectF(cx-29f*u,cy-15f*u,cx-4f*u,cy+16f*u),p);c.drawOval(RectF(cx+4f*u,cy-15f*u,cx+29f*u,cy+16f*u),p) }
            "games" -> { c.drawRoundRect(RectF(cx-31f*u,cy-18f*u,cx+31f*u,cy+22f*u),14f*u,14f*u,p);c.drawLine(cx-20f*u,cy-7f*u,cx-20f*u,cy+11f*u,p);c.drawLine(cx-29f*u,cy+2f*u,cx-11f*u,cy+2f*u,p);c.drawCircle(cx+17f*u,cy-4f*u,4f*u,fill(red));c.drawCircle(cx+26f*u,cy+7f*u,4f*u,fill(blue)) }
            "settings" -> { c.drawCircle(cx,cy,22f*u,p);c.drawCircle(cx,cy,7f*u,p);for(i in 0 until 8){val a=i*PI/4;c.drawLine(cx+cos(a).toFloat()*25f*u,cy+sin(a).toFloat()*25f*u,cx+cos(a).toFloat()*33f*u,cy+sin(a).toFloat()*33f*u,p)} }
            else -> drawMask(c, cx, cy, u, phase)
        }
    }

    private fun drawMask(c: Canvas, cx: Float, cy: Float, u: Float, phase: Double) {
        val mask=Path().apply{moveTo(cx,cy-30f*u);cubicTo(cx-30f*u,cy-28f*u,cx-31f*u,cy+13f*u,cx,cy+30f*u);cubicTo(cx+31f*u,cy+13f*u,cx+30f*u,cy-28f*u,cx,cy-30f*u);close()}
        c.drawPath(mask,fill(red));c.drawPath(mask,stroke(cream,3f*u))
        val blink=(sin(phase*1.7)*.08).toFloat()
        val left=Path().apply{moveTo(cx-22f*u,cy-10f*u);lineTo(cx-5f*u,cy-2f*u);lineTo(cx-18f*u,cy+(17f+blink)*u);close()}
        val right=Path().apply{moveTo(cx+22f*u,cy-10f*u);lineTo(cx+5f*u,cy-2f*u);lineTo(cx+18f*u,cy+(17f+blink)*u);close()}
        c.drawPath(left,fill(cream));c.drawPath(right,fill(cream))
    }
}
