package com.agentdeck.ui

import android.graphics.*
import kotlin.math.*

/** Original, resolution-independent startup film. Wallpaper is painted by DeckView. */
object DockstationBootScene {
    const val TOTAL = 10_500L
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mint = Color.rgb(136, 239, 221)
    private val white = Color.rgb(241, 250, 255)
    private val muted = Color.rgb(189, 218, 237)
    private val titles = arrayOf("Speak. Then keep going.", "Your team, within reach.", "The update. Not the interruption.", "A shortcut to your day.")
    private val captions = arrayOf("Voice reply · Review your words · Confirm send", "Custom roles · Shared Loops · You stay in control", "AI messages · Task progress · A glance at Orange", "Number keys · PC actions · Swipe up for controls")
    private val labels = arrayOf("VOICE", "YOUR AGENTS", "INFO PANE", "PHYSICAL CONTROL")
    fun homeProgress(t: Long): Float = ease(((t - 9_200f) / 1_300f).coerceIn(0f, 1f))
    private fun ease(x: Float) = x * x * (3 - 2 * x)
    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, centered: Boolean = true) {
        ink.shader = null; ink.style = Paint.Style.FILL; ink.color = color
        ink.typeface = Typeface.create("sans-serif", Typeface.NORMAL); ink.textSize = size
        ink.textAlign = if (centered) Paint.Align.CENTER else Paint.Align.LEFT
        c.drawText(s, x, y, ink)
    }
    private fun card(c: Canvas, r: RectF) {
        ink.style = Paint.Style.FILL
        ink.shader = LinearGradient(r.left, r.top, r.right, r.bottom, intArrayOf(0xb52d5778.toInt(), 0xe00b223e.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, 26f, 26f, ink); ink.shader = null
        ink.style = Paint.Style.STROKE; ink.strokeWidth = 1.2f; ink.color = 0x709bcfe6
        c.drawRoundRect(r, 26f, 26f, ink); ink.style = Paint.Style.FILL
    }
    fun render(c: Canvas, w: Int, h: Int, t: Long, logo: Bitmap, source: Rect) {
        val save = c.save(); val scale = min(w / 854f, h / 480f)
        c.translate((w - 854 * scale) / 2, (h - 480 * scale) / 2); c.scale(scale, scale)
        ink.color = 0x42081831; c.drawRect(0f, 0f, 854f, 480f, ink)
        val intro = 1f - ease(((t - 950f) / 700f).coerceIn(0f, 1f))
        val cx = 427f; val cy = 86f + 87f * intro; val radius = 34f + 27f * intro
        for (i in 0..2) {
            ink.style = Paint.Style.STROKE; ink.strokeWidth = if (i == 0) 1.6f else 1f
            ink.color = Color.argb(155 - i * 40, 151, 226, 238)
            val r = radius + 9 + i * 11 + sin(t / 1050.0 + i).toFloat() * 3
            c.drawCircle(cx, cy, r, ink)
        }
        ink.style = Paint.Style.FILL; ink.color = white; ink.isFilterBitmap = true
        c.drawBitmap(logo, source, RectF(cx-radius*.68f, cy-radius*.74f, cx+radius*.68f, cy+radius*.74f), ink)
        text(c, "Manzanilla", cx, cy + radius + 38, 26f + 12 * intro, white)
        if (t < 1550) text(c, "A calmer way to work.", cx, 314f, 21f, muted)
        if (t >= 1550 && t < 9200) {
            val index = ((t - 1550) / 1850).toInt().coerceIn(0, 3)
            val local = (t - 1550 - index * 1850).toFloat()
            val fade = min(ease((local / 260).coerceIn(0f,1f)), ease(((1850-local)/220).coerceIn(0f,1f)))
            val layer = c.saveLayerAlpha(0f, 174f, 854f, 428f, (fade*255).toInt().coerceIn(0,255))
            c.translate(0f, (1-fade)*12f)
            card(c, RectF(60f, 188f, 794f, 408f))
            text(c, labels[index], 427f, 222f, 13f, mint)
            when(index) {
                0 -> for(i in -16..16) {
                    val amplitude = 5f + abs(sin(t / 230.0 + i*.44)).toFloat() * (24-abs(i))
                    ink.color = mint; c.drawRoundRect(RectF(427+i*9f-2,270-amplitude,427+i*9f+2,270+amplitude),2f,2f,ink)
                }
                1 -> for(i in -1..1) {
                    val x = 427+i*95f; ink.style=Paint.Style.STROKE;ink.strokeWidth=1.5f;ink.color=mint
                    c.drawCircle(x,269f,24f,ink);if(i<1)c.drawLine(x+25,269f,x+69,269f,ink);ink.style=Paint.Style.FILL
                    text(c, arrayOf("01","02","03")[i+1],x,276f,17f,white)
                }
                2 -> { ink.color=mint;c.drawCircle(245f,267f,5f,ink);text(c,"Messages, without switching windows",445f,275f,18f,white) }
                3 -> for(i in 1..5) { val x=283+i*48f; ink.style=Paint.Style.STROKE;ink.color=mint;ink.strokeWidth=1f;c.drawRoundRect(RectF(x-17,249f,x+17,285f),10f,10f,ink);ink.style=Paint.Style.FILL;text(c,"$i",x,274f,21f,white) }
            }
            text(c,titles[index],427f,333f,28f,white)
            text(c,captions[index],427f,373f,17f,muted)
            c.restoreToCount(layer)
        } else if(t >= 9200) {
            text(c,"Make room for what matters.",427f,275f,32f,white)
        }
        for(i in 0..3) { ink.color=if(t>=1550+i*1850)mint else 0x508bbacf; c.drawRoundRect(RectF(367+i*32f,441f,389+i*32f,444f),2f,2f,ink) }
        text(c,"ORANGE  /  DOCKSTATION",427f,468f,11f,muted)
        c.restoreToCount(save)
    }
}
