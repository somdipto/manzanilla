package com.agentdeck.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The coded half of the Manzanilla boot experience.
 *
 * The supplied film ends on the branded CRT frame.  That exact decoded frame is
 * the first thing rendered here; the analogue image then settles into a clean,
 * pixel-native system demonstration before its pieces become the real home UI.
 */
object FeatureBootScene {
    const val TOTAL = 6_500L
    const val HOME_MORPH_START = 5_050L

    private val INK = Color.rgb(3, 10, 28)
    private val NAVY = Color.rgb(5, 22, 58)
    private val BLUE = Color.rgb(45, 166, 235)
    private val CYAN = Color.rgb(94, 221, 237)
    private val CREAM = Color.rgb(255, 246, 218)
    private val GOLD = Color.rgb(245, 200, 66)
    private val GREEN = Color.rgb(121, 194, 77)
    private val PINK = Color.rgb(244, 104, 158)
    private val CORAL = Color.rgb(241, 104, 91)
    private val PURPLE = Color.rgb(163, 104, 235)

    private val paint = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private val spritePaint = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private val typePaint = Paint().apply { isAntiAlias = false }
    private var font: Typeface? = null
    private var brandFlower: Bitmap? = null
    private var brandFlowerSrc: Rect? = null

    fun setFont(value: Typeface) { font = value; typePaint.typeface = value }
    fun setBrandFlower(value: Bitmap, source: Rect) {
        brandFlower = value
        brandFlowerSrc = Rect(source)
    }

    fun homeProgress(t: Long): Float =
        smooth(((t - HOME_MORPH_START).toFloat() / (TOTAL - HOME_MORPH_START)).coerceIn(0f, 1f))

    fun render(c: Canvas, w: Int, h: Int, t: Long, handoff: Bitmap?): Boolean {
        drawSignalLock(c, w, h, t, handoff)

        layer(c, w, h, window(t, 380L, 1_760L, 210L)) {
            drawAiCommunicator(c, w, h, t)
        }
        layer(c, w, h, window(t, 1_520L, 2_900L, 230L)) {
            drawVoice(c, w, h, t)
        }
        layer(c, w, h, window(t, 2_660L, 4_130L, 230L)) {
            drawTaskStatus(c, w, h, t)
        }
        layer(c, w, h, window(t, 3_880L, 5_420L, 230L)) {
            drawPcControl(c, w, h, t)
        }
        if (t >= HOME_MORPH_START) drawHomeSettle(c, w, h, t)

        // One continuous brand object: it first covers the flower already in
        // the movie's last frame, then becomes the feature marker and finally
        // lands in the exact header position used by the live OS.
        drawBrandContinuity(c, w, h, t)

        drawScanlines(c, w, h, t)
        return t >= TOTAL
    }

    private fun drawSignalLock(c: Canvas, w: Int, h: Int, t: Long, handoff: Bitmap?) {
        val full = RectF(0f, 0f, w.toFloat(), h.toFloat())
        if (t < 1_250L) {
            if (handoff != null && !handoff.isRecycled) c.drawBitmap(handoff, null, full, paint)
            else c.drawColor(Color.BLACK)
        } else c.drawColor(INK)

        // The last VHS frame remains exact at t=0, then the unstable black CRT
        // field resolves into Manzanilla's deep-blue system space.
        val stable = smooth(seg(t, 180L, 920L))
        paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), INK, NAVY, Shader.TileMode.CLAMP)
        paint.alpha = if (t >= 1_250L) 255 else (stable * 242f).toInt()
        c.drawRect(full, paint)
        paint.shader = null
        paint.alpha = 255

        if (t in 130L..1_080L) {
            val strength = (1f - stable) * 0.55f + 0.08f
            for (i in 0 until 10) {
                val y = ((i * 67 + t / 9) % h).toFloat()
                paint.color = listOf(CYAN, PINK, GOLD)[i % 3]
                paint.alpha = (strength * 145f).toInt()
                val x = ((i * 113 + t / 4) % w).toFloat()
                c.drawRect(x, y, (x + 26f + i * 3f).coerceAtMost(w.toFloat()), y + 2f, paint)
            }
            paint.alpha = 255
        }
    }

    private fun drawAiCommunicator(c: Canvas, w: Int, h: Int, t: Long) {
        val p = smooth(seg(t, 420L, 1_180L))
        heading(c, w, "AI COMMUNICATOR", "TALK TO YOUR AI", GOLD)

        val coreX = w * 0.28f
        val coreY = h * 0.52f
        for (ring in 0..2) {
            val phase = ((t / 430f + ring * 0.34f) % 1f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = listOf(GOLD, GREEN, CYAN)[ring]
            paint.alpha = ((1f - phase) * 170).toInt()
            val rr = 43f + phase * 54f
            c.drawCircle(coreX, coreY, rr, paint)
        }
        paint.style = Paint.Style.FILL
        paint.alpha = 255

        val names = listOf("CLAUDE" to GOLD, "CODEX" to CYAN, "COWORK" to PURPLE)
        val states = listOf("THINKING", "WORKING", "3 TASKS")
        for (i in names.indices) {
            val targetX = w * 0.58f
            val targetY = h * 0.29f + i * h * 0.18f
            val x = lerp(coreX + 20f, targetX, out((p - i * 0.12f).coerceIn(0f, 1f)))
            val y = lerp(coreY, targetY, out((p - i * 0.12f).coerceIn(0f, 1f)))
            drawSignalTrail(c, coreX + 36f, coreY, x, y, names[i].second, t + i * 140L)
            val r = RectF(x, y - 27f, w - 42f, y + 28f)
            card(c, r, names[i].second)
            badge(c, r.left + 12f, r.centerY(), names[i].first.take(1), names[i].second)
            text(c, names[i].first, r.left + 48f, r.top + 21f, CREAM, 12f)
            val blink = ((t / 280 + i) % 2L == 0L)
            paint.color = if (blink) GREEN else names[i].second
            c.drawRect(r.left + 49f, r.top + 33f, r.left + 55f, r.top + 39f, paint)
            text(c, states[i], r.left + 62f, r.top + 41f, Color.rgb(176, 203, 222), 8f)
        }
    }

    private fun drawVoice(c: Canvas, w: Int, h: Int, t: Long) {
        val p = smooth(seg(t, 1_570L, 2_260L))
        heading(c, w, "VOICE COMMUNICATION", "DEVICE MIC  ->  YOUR AI", PINK)
        val cx = w / 2f
        val cy = h * 0.54f

        // The three workspace signals visibly converge into one microphone.
        val colors = intArrayOf(GOLD, CYAN, PURPLE)
        for (i in colors.indices) {
            val a = (i - 1) * 125f
            val sx = cx + a * (1f - p)
            val sy = h * 0.35f + i * 54f * (1f - p)
            paint.color = colors[i]
            c.drawRect(sx - 5f, sy - 5f, sx + 5f, sy + 5f, paint)
            drawSignalTrail(c, sx, sy, cx, cy, colors[i], t + i * 170L)
        }

        paint.color = CREAM
        c.drawRoundRect(RectF(cx - 25f, cy - 57f, cx + 25f, cy + 18f), 19f, 19f, paint)
        paint.color = PINK
        c.drawRoundRect(RectF(cx - 15f, cy - 47f, cx + 15f, cy + 7f), 12f, 12f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        paint.color = CYAN
        c.drawArc(RectF(cx - 39f, cy - 22f, cx + 39f, cy + 42f), 0f, 180f, false, paint)
        c.drawLine(cx, cy + 42f, cx, cy + 59f, paint)
        c.drawLine(cx - 22f, cy + 59f, cx + 22f, cy + 59f, paint)
        paint.style = Paint.Style.FILL

        val baseY = cy + 5f
        for (i in -15..15) {
            val x = cx + i * 18f
            if (abs(x - cx) < 54f) continue
            val amp = (9f + 27f * abs(sin(t / 105.0 + i * 0.64))).toFloat()
            paint.color = colors[(abs(i) / 3) % colors.size]
            paint.alpha = 210
            c.drawRect(x - 3f, baseY - amp, x + 3f, baseY + amp, paint)
        }
        paint.alpha = 255
        textCentered(c, "LISTENING", w / 2f, h - 47f, CREAM, 10f)
    }

    private fun drawTaskStatus(c: Canvas, w: Int, h: Int, t: Long) {
        val p = smooth(seg(t, 2_710L, 3_280L))
        heading(c, w, "LIVE AI TASK STATUS", "GLANCE  /  KNOW  /  RESPOND", CYAN)
        val data = listOf(
            Triple("CODEX", "WORKING ON MANZANILLA LAUNCHER", "WORKING"),
            Triple("CLAUDE", "WAITING FOR YOUR RESPONSE", "WAITING"),
            Triple("COWORK", "3 PARALLEL TASKS RUNNING", "RUNNING")
        )
        val cols = intArrayOf(CYAN, GOLD, PURPLE)
        for (i in data.indices) {
            val y = 118f + i * 88f
            val x = lerp(w + 30f, 72f, out((p - i * 0.09f).coerceIn(0f, 1f)))
            val r = RectF(x, y, w - 72f, y + 67f)
            card(c, r, cols[i])
            badge(c, r.left + 21f, r.centerY(), data[i].first.take(1), cols[i])
            text(c, data[i].first, r.left + 49f, r.top + 22f, CREAM, 11f)
            text(c, data[i].second, r.left + 49f, r.top + 46f, Color.rgb(180, 207, 224), 8f)
            val statusPaint = colorForState(data[i].third)
            paint.color = statusPaint
            c.drawRect(r.right - 104f, r.top + 12f, r.right - 94f, r.top + 22f, paint)
            text(c, data[i].third, r.right - 84f, r.top + 22f, statusPaint, 8f)
            val dots = 4
            for (d in 0 until dots) {
                paint.color = if (((t / 180 + d + i) % dots) == 0L) CREAM else cols[i]
                c.drawRect(r.right - 84f + d * 15f, r.bottom - 18f,
                    r.right - 76f + d * 15f, r.bottom - 10f, paint)
            }
        }
    }

    private fun drawPcControl(c: Canvas, w: Int, h: Int, t: Long) {
        val p = smooth(seg(t, 3_930L, 4_440L))
        heading(c, w, "CONTROL YOUR PC", "STREAM DECK MODE  /  PHYSICAL CONTROL", GREEN)
        val keys = listOf(
            "1" to "WHATSAPP", "2" to "FILES", "3" to "CHROME",
            "4" to "CLAUDE", "5" to "CODEX", "6" to "NOTION"
        )
        val cols = intArrayOf(GREEN, GOLD, BLUE, CORAL, PURPLE, CREAM)
        val keyW = 214f
        val keyH = 94f
        val gap = 14f
        val startX = (w - (keyW * 3 + gap * 2)) / 2f
        for (i in keys.indices) {
            val row = i / 3
            val col = i % 3
            val targetX = startX + col * (keyW + gap)
            val targetY = 116f + row * (keyH + 14f)
            val fromY = 150f + i * 18f
            val x = lerp(w * 0.53f, targetX, out((p - i * 0.035f).coerceIn(0f, 1f)))
            var y = lerp(fromY, targetY, out((p - i * 0.035f).coerceIn(0f, 1f)))
            val pressed = (i == 2 && t in 4_650L..4_830L) || (i == 4 && t in 4_900L..5_080L)
            if (pressed) y += 7f
            val r = RectF(x, y, x + keyW, y + keyH)
            card(c, r, if (pressed) CREAM else cols[i])
            paint.color = Color.argb(185, Color.red(cols[i]), Color.green(cols[i]), Color.blue(cols[i]))
            c.drawRect(r.left + 7f, r.top + 7f, r.left + 42f, r.top + 42f, paint)
            textCentered(c, keys[i].first, r.left + 24f, r.top + 33f, INK, 12f)
            text(c, keys[i].second, r.left + 54f, r.top + 35f, CREAM, 10f)
            text(c, if (pressed) "FOCUSING APP" else "CUSTOM ACTION", r.left + 16f, r.bottom - 17f,
                if (pressed) GOLD else Color.rgb(151, 183, 205), 7f)
        }
        val labels = listOf("CALL = TALK", "MUTE = DICTATE", "# = CAPTURE", "* = MODE")
        var x = 80f
        for ((i, label) in labels.withIndex()) {
            text(c, label, x, h - 32f, cols[i], 8f)
            x += measure(label, 8f) + 34f
        }
    }

    private fun drawHomeSettle(c: Canvas, w: Int, h: Int, t: Long) {
        val p = homeProgress(t)
        // These pieces follow the same destinations as the live home screen,
        // making the final crossfade feel like reorganisation rather than a page load.
        val titleY = lerp(h * 0.48f, 38f, out(p))
        val titleX = lerp(w / 2f - 118f, 62f, out(p))
        text(c, "MANZANILLA OS", titleX, titleY, CREAM, lerp(19f, 19f, p))
        if (p < 0.78f) textCentered(c, "SYSTEM ONLINE", w / 2f, h * 0.70f, GREEN, 11f)
    }

    private fun drawBrandContinuity(c: Canvas, w: Int, h: Int, t: Long) {
        val movie = RectF(
            w * (216f / 1280f), h * (184f / 720f),
            w * (546f / 1280f), h * (543f / 720f)
        )
        val coreX = w * 0.28f
        val coreY = h * 0.52f
        val pulse = 1f + if (t in 450L..1_500L)
            0.035f * abs(sin(t / 150.0)).toFloat() else 0f
        val core = RectF(coreX - 45f * pulse, coreY - 50f * pulse,
            coreX + 45f * pulse, coreY + 50f * pulse)
        val header = RectF(16f, 8f, 51f, 49f)

        val intoCore = smooth(seg(t, 240L, 900L))
        val intoHeader = smooth(seg(t, 1_260L, 1_820L))
        val middle = lerpRect(movie, core, intoCore)
        val dest = lerpRect(middle, header, intoHeader)
        drawBrandFlower(c, dest, (smooth(seg(t, 0L, 120L)) * 255f).toInt())
    }

    private fun drawBrandFlower(c: Canvas, dest: RectF, alpha: Int) {
        val bitmap = brandFlower
        val source = brandFlowerSrc
        if (bitmap != null && source != null && !bitmap.isRecycled) {
            spritePaint.alpha = alpha.coerceIn(0, 255)
            c.drawBitmap(bitmap, source, dest, spritePaint)
            spritePaint.alpha = 255
        } else {
            val scale = minOf(dest.width() / Sprites.chamomile.width,
                dest.height() / Sprites.chamomile.height)
            sprite(c, Sprites.chamomile, dest.centerX() - Sprites.chamomile.width * scale / 2f,
                dest.centerY() - Sprites.chamomile.height * scale / 2f, scale)
        }
    }

    private fun lerpRect(a: RectF, b: RectF, p: Float) = RectF(
        lerp(a.left, b.left, p), lerp(a.top, b.top, p),
        lerp(a.right, b.right, p), lerp(a.bottom, b.bottom, p)
    )

    private fun drawSignalTrail(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float,
                                color: Int, t: Long) {
        for (i in 0..12) {
            val f = i / 12f
            val x = lerp(x1, x2, f)
            val y = lerp(y1, y2, f) + sin(f * PI).toFloat() * 14f
            val active = ((t / 55 + i) % 7L) < 3L
            paint.color = if (active) CREAM else color
            paint.alpha = if (active) 240 else 120
            val s = if (i % 4 == 0) 5f else 3f
            c.drawRect(x - s / 2f, y - s / 2f, x + s / 2f, y + s / 2f, paint)
        }
        paint.alpha = 255
    }

    private fun heading(c: Canvas, w: Int, title: String, subtitle: String, color: Int) {
        textCentered(c, title, w / 2f, 52f, CREAM, 18f)
        paint.color = color
        c.drawRect(w * 0.22f, 66f, w * 0.42f, 69f, paint)
        c.drawRect(w * 0.58f, 66f, w * 0.78f, 69f, paint)
        textCentered(c, subtitle, w / 2f, 73f, color, 8f)
    }

    private fun card(c: Canvas, r: RectF, accent: Int) {
        paint.color = Color.rgb(7, 22, 48)
        c.drawRect(r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = accent
        c.drawRect(r, paint)
        paint.style = Paint.Style.FILL
        paint.alpha = 90
        c.drawRect(r.left + 5f, r.bottom - 7f, r.right - 5f, r.bottom - 4f, paint)
        paint.alpha = 255
    }

    private fun badge(c: Canvas, x: Float, y: Float, label: String, color: Int) {
        paint.color = color
        c.drawRect(x - 15f, y - 15f, x + 15f, y + 15f, paint)
        textCentered(c, label, x, y + 6f, INK, 12f)
    }

    private fun sprite(c: Canvas, b: Bitmap, x: Float, y: Float, scale: Float) {
        spritePaint.alpha = 255
        c.drawBitmap(b, null, RectF(x, y, x + b.width * scale, y + b.height * scale), spritePaint)
    }

    private fun drawScanlines(c: Canvas, w: Int, h: Int, t: Long) {
        val stable = smooth(seg(t, 200L, 1_200L))
        paint.color = Color.BLACK
        paint.alpha = lerp(42f, 20f, stable).toInt()
        var y = 1f
        while (y < h) { c.drawRect(0f, y, w.toFloat(), y + 1f, paint); y += 4f }
        paint.alpha = 255
        if (t < 1_100L) {
            val yNoise = ((t * 3) % h).toFloat()
            paint.color = Color.WHITE
            paint.alpha = ((1f - stable) * 65).toInt()
            c.drawRect(0f, yNoise, w.toFloat(), yNoise + 2f, paint)
            paint.alpha = 255
        }
    }

    private inline fun layer(c: Canvas, w: Int, h: Int, alpha: Int, block: () -> Unit) {
        if (alpha <= 0) return
        val save = c.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), alpha)
        block()
        c.restoreToCount(save)
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, color: Int, size: Float) {
        typePaint.color = color
        typePaint.textSize = size
        typePaint.typeface = font ?: Typeface.MONOSPACE
        c.drawText(s, x, y, typePaint)
    }

    private fun textCentered(c: Canvas, s: String, x: Float, y: Float, color: Int, size: Float) {
        text(c, s, x - measure(s, size) / 2f, y, color, size)
    }

    private fun measure(s: String, size: Float): Float {
        typePaint.textSize = size
        typePaint.typeface = font ?: Typeface.MONOSPACE
        return typePaint.measureText(s)
    }

    private fun colorForState(state: String) = when (state) {
        "WAITING" -> GOLD
        "RUNNING" -> PURPLE
        else -> GREEN
    }

    private fun window(t: Long, start: Long, end: Long, fade: Long): Int {
        val a = smooth(seg(t, start, start + fade))
        val b = 1f - smooth(seg(t, end - fade, end))
        return (255f * minOf(a, b)).toInt().coerceIn(0, 255)
    }

    private fun seg(t: Long, start: Long, end: Long): Float =
        ((t - start).toFloat() / (end - start)).coerceIn(0f, 1f)
    private fun smooth(v: Float) = v * v * (3f - 2f * v)
    private fun out(v: Float): Float = 1f - (1f - v) * (1f - v) * (1f - v)
    private fun lerp(a: Float, b: Float, p: Float) = a + (b - a) * p
}
