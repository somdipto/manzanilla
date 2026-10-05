package com.agentdeck.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Resolution-independent Spain Glass icon family.
 *
 * The shapes deliberately mirror SVG construction: rounded strokes, paths and
 * circles only. They stay crisp on the 854x480 panel and expose their parts to
 * animation instead of baking motion into a bitmap sprite.
 */
object GlassIcons {
    private data class Palette(val red: Int, val yellow: Int, val green: Int)
    private val onLight = Palette(
        Color.rgb(170, 21, 27), Color.rgb(241, 191, 0), Color.rgb(0, 168, 89)
    )
    private val onDark = Palette(
        Color.rgb(255, 104, 116), Color.rgb(255, 211, 74), Color.rgb(84, 231, 164)
    )

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    fun draw(c: Canvas, id: String, r: RectF, timeMs: Long, darkSurface: Boolean = false) {
        val palette = if (darkSurface) onDark else onLight
        val red = palette.red
        val yellow = palette.yellow
        val green = palette.green
        val cx = r.centerX()
        val cy = r.centerY()
        val u = minOf(r.width(), r.height()) / 100f
        val phase = timeMs / 1000.0

        when (id) {
            "chatgpt" -> {
                val p = stroke(red, 7f * u)
                val bubble = Path().apply {
                    moveTo(cx + 34f * u, cy - 3f * u)
                    cubicTo(cx + 34f * u, cy - 25f * u, cx + 18f * u, cy - 37f * u, cx - 5f * u, cy - 37f * u)
                    cubicTo(cx - 30f * u, cy - 37f * u, cx - 40f * u, cy - 20f * u, cx - 39f * u, cy + 1f * u)
                    cubicTo(cx - 38f * u, cy + 22f * u, cx - 20f * u, cy + 35f * u, cx + 3f * u, cy + 35f * u)
                    lineTo(cx + 25f * u, cy + 31f * u)
                    lineTo(cx + 20f * u, cy + 43f * u)
                    lineTo(cx + 4f * u, cy + 35f * u)
                }
                c.drawPath(bubble, p)
                for (i in 0..2) {
                    val pulse = 1f + 0.16f * sin(phase * 2.4 + i * 0.8).toFloat()
                    c.drawCircle(cx + (-14f + i * 14f) * u, cy, 5.2f * u * pulse, fill(green))
                }
                c.drawCircle(cx + 28f * u, cy - 7f * u, 6.5f * u, fill(yellow))
            }

            "codex" -> {
                val p = stroke(red, 8f * u)
                val left = Path().apply {
                    moveTo(cx - 13f * u, cy - 27f * u)
                    lineTo(cx - 39f * u, cy)
                    lineTo(cx - 13f * u, cy + 27f * u)
                }
                val right = Path().apply {
                    moveTo(cx + 13f * u, cy - 27f * u)
                    lineTo(cx + 39f * u, cy)
                    lineTo(cx + 13f * u, cy + 27f * u)
                }
                c.drawPath(left, p)
                c.drawPath(right, p)
                c.drawLine(cx + 5f * u, cy - 35f * u, cx - 7f * u, cy + 35f * u, p)
                c.drawCircle(cx + 36f * u, cy + 32f * u,
                    (5.5f + sin(phase * 2.0).toFloat()) * u, fill(yellow))
            }

            "claude" -> {
                c.save()
                c.rotate((sin(phase * 0.65) * 7.0).toFloat(), cx, cy)
                for (i in 0 until 10) {
                    val a = i * PI * 2 / 10
                    val inner = 11f * u
                    val outer = (34f + 3f * sin(phase * 1.5 + i * 0.55).toFloat()) * u
                    val p = stroke(if (i % 3 == 1) yellow else red, 8f * u)
                    c.drawLine(
                        cx + cos(a).toFloat() * inner, cy + sin(a).toFloat() * inner,
                        cx + cos(a).toFloat() * outer, cy + sin(a).toFloat() * outer, p
                    )
                }
                c.restore()
            }

            "wispr" -> {
                val heights = floatArrayOf(15f, 30f, 48f, 67f, 48f, 30f, 15f)
                for (i in heights.indices) {
                    val moving = 0.78f + 0.22f * abs(sin(phase * 3.1 + i * 0.72)).toFloat()
                    val h = heights[i] * moving * u
                    val x = cx + (i - 3) * 12f * u
                    c.drawLine(x, cy - h / 2f, x, cy + h / 2f,
                        stroke(if (i == 2 || i == 4) yellow else red, 7f * u))
                }
                c.drawCircle(cx - 47f * u, cy, 5f * u, fill(green))
                c.drawCircle(cx + 47f * u, cy, 5f * u, fill(green))
            }

            "stream" -> {
                for (row in 0..2) for (col in 0..2) {
                    val index = row * 3 + col
                    val pulse = 1f + 0.13f * sin(phase * 2.1 + index * 0.42).toFloat()
                    val color = when (index) {
                        2 -> yellow
                        8 -> green
                        else -> red
                    }
                    c.drawCircle(cx + (col - 1) * 23f * u, cy + (row - 1) * 23f * u,
                        7.5f * u * pulse, fill(color))
                }
            }

            "tasks" -> {
                val ring = RectF(cx - 34f * u, cy - 34f * u, cx + 34f * u, cy + 34f * u)
                c.drawArc(ring, 112f, 190f, false, stroke(red, 7f * u))
                c.drawArc(ring, 306f, 120f, false, stroke(yellow, 7f * u))
                val p = stroke(green, 8f * u)
                val check = Path().apply {
                    moveTo(cx - 24f * u, cy + 1f * u)
                    lineTo(cx - 7f * u, cy + 18f * u)
                    lineTo(cx + 28f * u, cy - 23f * u)
                }
                c.drawPath(check, p)
            }

            "files" -> {
                val p = stroke(red, 7f * u)
                val folder = Path().apply {
                    moveTo(cx - 37f * u, cy - 27f * u)
                    lineTo(cx - 8f * u, cy - 27f * u)
                    lineTo(cx + 2f * u, cy - 17f * u)
                    lineTo(cx + 36f * u, cy - 17f * u)
                    lineTo(cx + 36f * u, cy + 29f * u)
                    lineTo(cx - 37f * u, cy + 29f * u)
                    close()
                }
                c.drawPath(folder, p)
                c.drawLine(cx - 34f * u, cy - 17f * u, cx + 34f * u, cy - 17f * u,
                    stroke(yellow, 6f * u))
                val pulse = 1f + 0.12f * sin(phase * 2.0).toFloat()
                c.drawCircle(cx + 29f * u, cy + 18f * u, 9f * u * pulse, fill(green))
            }

            "more" -> {
                val colors = intArrayOf(red, yellow, green)
                for (i in 0..2) {
                    val bob = sin(phase * 1.7 + i * 0.8).toFloat() * 2.5f * u
                    c.drawCircle(cx + (i - 1) * 25f * u, cy + bob, 8f * u, fill(colors[i]))
                }
            }

            "games" -> {
                // A compact, animated controller silhouette.  The face buttons
                // use the Manzanilla brand colours so it remains readable at
                // the device's physical icon size.
                val body = RectF(cx - 40f * u, cy - 23f * u, cx + 40f * u, cy + 25f * u)
                c.drawRoundRect(body, 18f * u, 18f * u, stroke(red, 7f * u))
                val cross = stroke(green, 7f * u)
                c.drawLine(cx - 24f * u, cy - 10f * u, cx - 24f * u, cy + 12f * u, cross)
                c.drawLine(cx - 35f * u, cy + 1f * u, cx - 13f * u, cy + 1f * u, cross)
                val bounce = sin(phase * 2.2).toFloat() * 1.8f * u
                c.drawCircle(cx + 21f * u, cy - 5f * u + bounce, 6f * u, fill(yellow))
                c.drawCircle(cx + 32f * u, cy + 7f * u - bounce, 6f * u, fill(red))
                c.drawLine(cx - 24f * u, body.top + 1f, cx - 34f * u, body.top - 13f * u,
                    stroke(red, 5f * u))
                c.drawLine(cx + 24f * u, body.top + 1f, cx + 34f * u, body.top - 13f * u,
                    stroke(red, 5f * u))
            }
        }
    }
}
