package com.agentdeck.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Lightweight, resolution-independent icon family for the Dockstation theme. */
object DockIcons {
    private val supported = setOf(
        "chatgpt", "codex", "claude", "wispr", "stream", "spotify", "chrome",
        "whatsapp", "files", "tasks", "agents", "settings", "notion", "comet",
        "discord", "slack", "youtube", "gmail", "zoom", "figma", "vscode",
        "excel", "word", "camera", "creative_cloud", "games", "themes", "more", "pet"
    )

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    fun draw(c: Canvas, id: String, r: RectF, timeMs: Long): Boolean {
        if (id !in supported) return false
        val s = minOf(r.width(), r.height())
        val u = s / 100f
        val cx = r.centerX()
        val cy = r.centerY()
        val white = Color.rgb(239, 248, 255)
        val cyan = Color.rgb(89, 218, 244)
        val gold = Color.rgb(255, 190, 74)
        val green = Color.rgb(105, 231, 185)
        val coral = Color.rgb(255, 112, 93)
        val p = stroke(white, 6.5f * u)
        val q = stroke(cyan, 5f * u)
        val phase = timeMs / 1000.0

        fun monogram(text: String, color: Int = white, size: Float = 54f) {
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = size * u
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            c.drawText(text, cx, cy - (t.ascent() + t.descent()) / 2f, t)
        }
        fun dot(x: Float, y: Float, color: Int, radius: Float = 5f) =
            c.drawCircle(cx + x * u, cy + y * u, radius * u, fill(color))
        fun ring(radius: Float, paint: Paint = p) = c.drawCircle(cx, cy, radius * u, paint)

        when (id) {
            "chatgpt" -> {
                for (i in 0 until 6) {
                    val a = i * PI / 3 + phase * .12
                    val x = cx + cos(a).toFloat() * 22f * u
                    val y = cy + sin(a).toFloat() * 22f * u
                    c.drawArc(RectF(x - 19f*u, y - 19f*u, x + 19f*u, y + 19f*u),
                        (i * 60 + 205).toFloat(), 125f, false, p)
                }
                dot(31f, 30f, green, 4.5f)
            }
            "codex" -> {
                c.drawPath(Path().apply { moveTo(cx-29*u,cy-23*u); lineTo(cx-48*u,cy); lineTo(cx-29*u,cy+23*u) }, p)
                c.drawPath(Path().apply { moveTo(cx+29*u,cy-23*u); lineTo(cx+48*u,cy); lineTo(cx+29*u,cy+23*u) }, p)
                c.drawLine(cx+10*u,cy-34*u,cx-10*u,cy+34*u,q)
                dot(38f, 31f, gold, 4.5f)
            }
            "claude" -> {
                c.save(); c.rotate((sin(phase*.7)*5).toFloat(),cx,cy)
                for (i in 0 until 10) {
                    val a=i*PI/5; val inner=11f*u; val outer=(35f+sin(phase+i)*2f).toFloat()*u
                    c.drawLine(cx+cos(a).toFloat()*inner,cy+sin(a).toFloat()*inner,
                        cx+cos(a).toFloat()*outer,cy+sin(a).toFloat()*outer,
                        stroke(if(i%3==0) gold else white,6f*u))
                }; c.restore()
            }
            "wispr" -> {
                val heights=floatArrayOf(18f,38f,62f,38f,18f)
                heights.forEachIndexed { i,h ->
                    val hh=h*(.82f+.18f*sin(phase*4+i).toFloat())*u; val x=cx+(i-2)*17*u
                    c.drawLine(x,cy-hh/2,x,cy+hh/2,stroke(if(i==2) gold else cyan,7f*u))
                }
            }
            "stream" -> for (row in 0..2) for (col in 0..2) {
                val color=if(row==2&&col==2) gold else if((row+col)%2==0) white else cyan
                c.drawRoundRect(RectF(cx+(col-1)*27*u-7*u,cy+(row-1)*27*u-7*u,
                    cx+(col-1)*27*u+7*u,cy+(row-1)*27*u+7*u),3*u,3*u,fill(color))
            }
            "spotify" -> {
                ring(38f, stroke(green, 6f*u))
                for(i in 0..2) c.drawArc(RectF(cx-25*u,cy-(22-i*12)*u,cx+27*u,cy+(14+i*8)*u),
                    205f,112f,false,stroke(green,(6-i)*u))
            }
            "chrome" -> {
                ring(38f,p); ring(15f,stroke(cyan,7f*u))
                for(a in listOf(-90f,30f,150f)) {
                    val rad=a*PI/180; c.drawLine(cx+cos(rad).toFloat()*15*u,cy+sin(rad).toFloat()*15*u,
                        cx+cos(rad).toFloat()*38*u,cy+sin(rad).toFloat()*38*u,stroke(gold,5f*u))
                }
            }
            "whatsapp" -> {
                c.drawPath(Path().apply {
                    addCircle(cx,cy-3*u,35*u,Path.Direction.CW); moveTo(cx-23*u,cy+23*u)
                    lineTo(cx-34*u,cy+40*u); lineTo(cx-10*u,cy+31*u)
                },p)
                c.drawArc(RectF(cx-17*u,cy-20*u,cx+20*u,cy+18*u),130f,105f,false,stroke(green,8f*u))
            }
            "files" -> {
                c.drawPath(Path().apply { moveTo(cx-40*u,cy-25*u); lineTo(cx-10*u,cy-25*u); lineTo(cx,cy-14*u); lineTo(cx+40*u,cy-14*u); lineTo(cx+40*u,cy+30*u); lineTo(cx-40*u,cy+30*u); close() },p)
                c.drawLine(cx-35*u,cy-13*u,cx+34*u,cy-13*u,stroke(gold,5f*u))
            }
            "tasks" -> {
                c.drawRoundRect(RectF(cx-33*u,cy-39*u,cx+33*u,cy+39*u),8*u,8*u,p)
                for(i in 0..2){ val y=cy+(-20+i*20)*u; c.drawLine(cx-20*u,y,cx-12*u,y+7*u,stroke(green,5f*u)); c.drawLine(cx-12*u,y+7*u,cx-2*u,y-6*u,stroke(green,5f*u)); c.drawLine(cx+8*u,y,cx+21*u,y,q) }
            }
            "agents", "pet" -> {
                c.drawRoundRect(RectF(cx-35*u,cy-29*u,cx+35*u,cy+31*u),17*u,17*u,p)
                dot(-14f,-3f,cyan,5f); dot(14f,-3f,cyan,5f)
                c.drawArc(RectF(cx-18*u,cy-4*u,cx+18*u,cy+20*u),20f,140f,false,stroke(green,5f*u))
                c.drawLine(cx,cy-29*u,cx,cy-42*u,q); dot(0f,-45f,gold,4.5f)
            }
            "settings" -> {
                ring(17f,stroke(gold,7f*u)); ring(36f,p)
                for(i in 0 until 8){ val a=i*PI/4; c.drawLine(cx+cos(a).toFloat()*36*u,cy+sin(a).toFloat()*36*u,cx+cos(a).toFloat()*46*u,cy+sin(a).toFloat()*46*u,p) }
            }
            "notion" -> monogram("N")
            "comet" -> {
                c.drawArc(RectF(cx-30*u,cy-30*u,cx+30*u,cy+30*u),30f,300f,false,p)
                c.drawArc(RectF(cx-17*u,cy-17*u,cx+17*u,cy+17*u),200f,255f,false,q)
                c.drawLine(cx-37*u,cy+24*u,cx-49*u,cy+35*u,stroke(gold,5f*u))
            }
            "discord", "games" -> {
                c.drawRoundRect(RectF(cx-41*u,cy-25*u,cx+41*u,cy+28*u),18*u,18*u,p)
                c.drawLine(cx-25*u,cy-10*u,cx-25*u,cy+11*u,q); c.drawLine(cx-35*u,cy,cx-15*u,cy,q)
                dot(20f,-4f,gold,5f); dot(31f,7f,green,5f)
            }
            "slack" -> {
                c.drawLine(cx-14*u,cy-38*u,cx-14*u,cy+38*u,p); c.drawLine(cx+14*u,cy-38*u,cx+14*u,cy+38*u,p)
                c.drawLine(cx-38*u,cy-14*u,cx+38*u,cy-14*u,q); c.drawLine(cx-38*u,cy+14*u,cx+38*u,cy+14*u,stroke(gold,5f*u))
            }
            "youtube" -> {
                c.drawRoundRect(RectF(cx-43*u,cy-29*u,cx+43*u,cy+29*u),13*u,13*u,stroke(coral,7f*u))
                c.drawPath(Path().apply { moveTo(cx-10*u,cy-16*u); lineTo(cx+19*u,cy); lineTo(cx-10*u,cy+16*u); close() },fill(white))
            }
            "gmail" -> {
                c.drawPath(Path().apply { moveTo(cx-40*u,cy+30*u); lineTo(cx-40*u,cy-29*u); lineTo(cx,cy+4*u); lineTo(cx+40*u,cy-29*u); lineTo(cx+40*u,cy+30*u) },p)
                c.drawLine(cx-39*u,cy-28*u,cx,cy+4*u,stroke(coral,6f*u)); c.drawLine(cx,cy+4*u,cx+39*u,cy-28*u,stroke(gold,6f*u))
            }
            "zoom" -> {
                c.drawRoundRect(RectF(cx-40*u,cy-27*u,cx+15*u,cy+27*u),10*u,10*u,p)
                c.drawPath(Path().apply { moveTo(cx+16*u,cy-12*u); lineTo(cx+42*u,cy-27*u); lineTo(cx+42*u,cy+27*u); lineTo(cx+16*u,cy+12*u); close() },q)
            }
            "figma" -> {
                val colors=intArrayOf(coral,gold,cyan,white,green)
                val pts=arrayOf(-13f to -27f,13f to -27f,-13f to 0f,13f to 0f,-13f to 27f)
                pts.forEachIndexed{i,(x,y)->c.drawCircle(cx+x*u,cy+y*u,13*u,fill(colors[i]))}
            }
            "vscode" -> {
                c.drawPath(Path().apply { moveTo(cx-42*u,cy-20*u); lineTo(cx-19*u,cy); lineTo(cx-42*u,cy+20*u); moveTo(cx-19*u,cy); lineTo(cx+30*u,cy-39*u); lineTo(cx+43*u,cy-32*u); lineTo(cx+43*u,cy+32*u); lineTo(cx+30*u,cy+39*u); close() },q)
            }
            "excel" -> monogram("X",green)
            "word" -> monogram("W",cyan)
            "camera" -> {
                c.drawRoundRect(RectF(cx-42*u,cy-28*u,cx+42*u,cy+31*u),9*u,9*u,p); ring(19f,q)
                c.drawRect(RectF(cx-22*u,cy-38*u,cx+8*u,cy-27*u),fill(white)); dot(31f,-17f,gold,4f)
            }
            "creative_cloud" -> {
                c.drawCircle(cx-17*u,cy,26*u,stroke(coral,7f*u)); c.drawCircle(cx+17*u,cy,26*u,stroke(gold,7f*u)); ring(18f,p)
            }
            "themes" -> {
                ring(38f,p); c.drawArc(RectF(cx-29*u,cy-29*u,cx+29*u,cy+29*u),-90f,120f,false,stroke(cyan,8f*u)); c.drawArc(RectF(cx-29*u,cy-29*u,cx+29*u,cy+29*u),30f,120f,false,stroke(gold,8f*u)); c.drawArc(RectF(cx-29*u,cy-29*u,cx+29*u,cy+29*u),150f,120f,false,stroke(coral,8f*u))
            }
            "more" -> { dot(-27f,0f,cyan,7f); dot(0f,0f,white,7f); dot(27f,0f,gold,7f) }
        }
        return true
    }
}
