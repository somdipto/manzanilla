package com.agentdeck.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Lightweight animated vector icons made specifically for the AURA theme. */
object AuraIcons {
    private val ink = Color.rgb(25, 31, 42)
    private val cyan = Color.rgb(15, 173, 194)
    private val coral = Color.rgb(246, 87, 113)
    private val violet = Color.rgb(125, 92, 219)
    private val green = Color.rgb(30, 173, 116)

    private fun stroke(color: Int = ink, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = width
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    fun draw(c: Canvas, id: String, r: RectF, now: Long) {
        val cx = r.centerX(); val cy = r.centerY()
        val u = minOf(r.width(), r.height()) / 100f
        val t = now / 1000.0
        val p = stroke(width = 7f * u)
        when (id) {
            "chatgpt" -> {
                val b = RectF(cx - 35*u, cy - 27*u, cx + 35*u, cy + 24*u)
                c.drawRoundRect(b, 23*u, 23*u, p)
                c.drawLine(cx - 18*u, b.bottom - 1*u, cx - 31*u, cy + 37*u, p)
                for (i in -1..1) c.drawCircle(cx + i*18*u, cy, 4.7f*u,
                    fill(if (i == 0) coral else cyan))
            }
            "codex" -> {
                val q = stroke(ink, 8*u)
                c.drawLine(cx-12*u, cy-28*u, cx-37*u, cy, q)
                c.drawLine(cx-37*u, cy, cx-12*u, cy+28*u, q)
                c.drawLine(cx+12*u, cy-28*u, cx+37*u, cy, q)
                c.drawLine(cx+37*u, cy, cx+12*u, cy+28*u, q)
                c.drawLine(cx+6*u, cy-34*u, cx-7*u, cy+34*u, stroke(cyan, 6*u))
            }
            "claude" -> {
                c.save(); c.rotate((t*13.0).toFloat(), cx, cy)
                for (i in 0 until 8) {
                    val a = i * PI / 4
                    c.drawLine(cx+cos(a).toFloat()*12*u, cy+sin(a).toFloat()*12*u,
                        cx+cos(a).toFloat()*36*u, cy+sin(a).toFloat()*36*u,
                        stroke(if (i%2==0) coral else violet, 7*u))
                }
                c.restore(); c.drawCircle(cx, cy, 5*u, fill(ink))
            }
            "wispr" -> {
                for (i in 0..6) {
                    val wave = (13 + 23 * kotlin.math.abs(sin(t*2.6+i*.62))).toFloat()*u
                    val x = cx + (i-3)*11*u
                    c.drawLine(x, cy-wave/2, x, cy+wave/2,
                        stroke(if (i==3) coral else cyan, 6*u))
                }
            }
            "stream" -> for (row in -1..1) for (col in -1..1) {
                val rr = RectF(cx+col*24*u-7*u, cy+row*24*u-7*u,
                    cx+col*24*u+7*u, cy+row*24*u+7*u)
                c.drawRoundRect(rr, 4*u, 4*u, fill(if (row==col) violet else ink))
            }
            "tasks" -> {
                c.drawCircle(cx, cy, 34*u, p)
                c.drawLine(cx-24*u, cy, cx-7*u, cy+17*u, stroke(green, 8*u))
                c.drawLine(cx-7*u, cy+17*u, cx+28*u, cy-23*u, stroke(green, 8*u))
            }
            "files" -> {
                val f = Path().apply { moveTo(cx-38*u,cy-25*u); lineTo(cx-9*u,cy-25*u)
                    lineTo(cx+1*u,cy-15*u); lineTo(cx+37*u,cy-15*u); lineTo(cx+37*u,cy+29*u)
                    lineTo(cx-38*u,cy+29*u); close() }
                c.drawPath(f,p); c.drawLine(cx-34*u,cy-13*u,cx+33*u,cy-13*u,stroke(cyan,5*u))
            }
            "more" -> for (i in -1..1) c.drawCircle(cx+i*24*u,
                cy+sin(t*2+i).toFloat()*3*u, 7*u, fill(listOf(coral, violet, green)[i+1]))
            "comet" -> {
                c.drawArc(RectF(cx-29*u,cy-29*u,cx+29*u,cy+29*u),35f,286f,false,p)
                c.drawCircle(cx+22*u,cy-21*u,8*u,fill(coral))
                c.drawLine(cx-38*u,cy+28*u,cx+6*u,cy+7*u,stroke(cyan,6*u))
            }
            "chrome" -> {
                c.drawCircle(cx,cy,35*u,p); c.drawCircle(cx,cy,13*u,stroke(cyan,7*u))
                for (i in 0..2) { val a=(t*.45+i*2*PI/3).toFloat()
                    c.drawLine(cx+cos(a)*14*u,cy+sin(a)*14*u,cx+cos(a)*34*u,cy+sin(a)*34*u,
                        stroke(listOf(coral,green,violet)[i],6*u)) }
            }
            "spotify" -> {
                c.drawCircle(cx,cy,37*u,fill(green))
                for (i in 0..2) c.drawArc(RectF(cx-23*u,cy-17*u+i*12*u,cx+25*u,cy+15*u+i*8*u),
                    205f,125f,false,stroke(Color.WHITE,(6-i)*u))
            }
            "camera" -> {
                val b=RectF(cx-38*u,cy-25*u,cx+38*u,cy+27*u); c.drawRoundRect(b,12*u,12*u,p)
                c.drawCircle(cx,cy+1*u,18*u,stroke(cyan,7*u))
                c.drawCircle(cx+25*u,cy-12*u,4*u,fill(coral))
            }
            "pet" -> {
                c.drawRoundRect(RectF(cx-34*u,cy-30*u,cx+34*u,cy+31*u),25*u,25*u,p)
                val glance=sin(t*.8).toFloat()*5*u
                c.drawCircle(cx-14*u+glance,cy-5*u,5*u,fill(ink)); c.drawCircle(cx+14*u+glance,cy-5*u,5*u,fill(ink))
                c.drawArc(RectF(cx-19*u,cy-5*u,cx+19*u,cy+21*u),18f,144f,false,stroke(coral,5*u))
            }
            "games" -> {
                val b=RectF(cx-38*u,cy-21*u,cx+38*u,cy+23*u); c.drawRoundRect(b,17*u,17*u,p)
                c.drawLine(cx-25*u,cy-9*u,cx-25*u,cy+11*u,stroke(cyan,7*u)); c.drawLine(cx-35*u,cy+1*u,cx-15*u,cy+1*u,stroke(cyan,7*u))
                c.drawCircle(cx+20*u,cy-4*u,5*u,fill(coral)); c.drawCircle(cx+31*u,cy+7*u,5*u,fill(violet))
            }
            "themes" -> {
                c.drawCircle(cx-13*u,cy-5*u,25*u,stroke(coral,6*u)); c.drawCircle(cx+14*u,cy-5*u,25*u,stroke(cyan,6*u))
                c.drawCircle(cx,cy+17*u,25*u,stroke(violet,6*u))
            }
            "settings" -> {
                c.save(); c.rotate((t*8).toFloat(),cx,cy)
                c.drawCircle(cx,cy,27*u,stroke(ink,10*u))
                for(i in 0 until 8){ val a=i*PI/4; c.drawLine(cx+cos(a).toFloat()*28*u,cy+sin(a).toFloat()*28*u,
                    cx+cos(a).toFloat()*40*u,cy+sin(a).toFloat()*40*u,stroke(ink,9*u)) }
                c.restore(); c.drawCircle(cx,cy,8*u,fill(cyan))
            }
            else -> c.drawCircle(cx, cy, 25*u, p)
        }
    }
}
