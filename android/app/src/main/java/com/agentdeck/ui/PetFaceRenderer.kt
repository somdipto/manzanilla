package com.agentdeck.ui

import android.graphics.*
import com.agentdeck.DeckState
import kotlin.math.sin

/** Original screen-faced companion for Manzanilla. Everything is Canvas vector geometry. */
class PetFaceRenderer {
    private val fill = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
    private val line = Paint().apply {
        isAntiAlias = false; style = Paint.Style.STROKE; strokeWidth = 5f
        strokeCap = Paint.Cap.SQUARE; strokeJoin = Paint.Join.MITER
    }
    private val text = Paint().apply {
        isAntiAlias = true; typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private var gazeX = 0f
    private var gazeY = 0f
    private var lastFaceAt = 0L

    fun draw(c: Canvas, w: Int, h: Int, st: DeckState, now: Long,
             zone: (RectF, String) -> Unit, showControls: Boolean = true) {
        val targetX = if (st.petTrackingEnabled && st.petFacePresent) st.petTargetX else 0f
        val targetY = if (st.petTrackingEnabled && st.petFacePresent) st.petTargetY else 0f
        gazeX += (targetX - gazeX) * 0.16f
        gazeY += (targetY - gazeY) * 0.16f
        if (st.petFacePresent) lastFaceAt = now

        c.drawColor(Color.rgb(6, 15, 32))
        // Warm, low-key horizon glow gives the face depth without becoming scenery.
        fill.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.rgb(8, 27, 56), Color.rgb(9, 17, 34), Color.rgb(20, 19, 31)), null,
            Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill); fill.shader = null

        val breathe = (sin(now / 850.0) * 2.5).toFloat()
        val waveBob = if (st.petExpression == "waving") (sin(now / 125.0) * 8.0).toFloat() else 0f
        val eyeY = h * 0.42f + breathe
        val eyeW = w * 0.27f
        val eyeH = h * 0.31f
        val gap = w * 0.07f
        val left = RectF(w / 2f - gap / 2f - eyeW, eyeY - eyeH / 2f - waveBob,
            w / 2f - gap / 2f, eyeY + eyeH / 2f - waveBob)
        val right = RectF(w / 2f + gap / 2f, eyeY - eyeH / 2f + waveBob,
            w / 2f + gap / 2f + eyeW, eyeY + eyeH / 2f + waveBob)

        val expression = if (!st.petFacePresent && now - lastFaceAt > 15_000L && st.petExpression == "idle")
            "sleepy" else st.petExpression
        drawEye(c, left, gazeX, gazeY, expression, now, false)
        drawEye(c, right, gazeX, gazeY, expression, now, true)

        drawMouth(c, w / 2f, h * .70f + breathe * .35f, expression, now)

        val notificationVisible = st.pcNotificationApp.isNotEmpty() && now < st.pcNotificationUntil
        val status = when {
            expression == "waving" -> "HELLO!"
            !st.petTrackingEnabled -> "CAMERA PAUSED"
            st.petFacePresent -> "HELLO — I SEE YOU"
            st.petTrackingAvailable -> "LOOKING FOR YOU"
            else -> "CAMERA TRACKING UNAVAILABLE"
        }
        if (notificationVisible) {
            drawNotification(c, w, h, st.pcNotificationApp, st.pcNotificationTitle)
        } else {
            text.textSize = 15f; text.color = if (st.petFacePresent) Color.rgb(255, 205, 78) else Color.rgb(145, 171, 201)
            text.letterSpacing = .12f
            c.drawText(status, w / 2f - text.measureText(status) / 2f, h - 24f, text)
        }

        // Small, touch-friendly controls; physical 0 always returns home.
        if (showControls) {
            val back = RectF(16f, 28f, 104f, 68f)
            val mood = RectF(w - 226f, 28f, w - 116f, 68f)
            val tracking = RectF(w - 106f, 28f, w - 16f, 68f)
            chip(c, back, "← HOME", false)
            chip(c, mood, "MOOD", expression != "idle")
            chip(c, tracking, if (st.petTrackingEnabled) "CAM ON" else "CAM OFF", st.petTrackingEnabled)
            zone(back, "pet_back"); zone(mood, "pet_expression"); zone(tracking, "pet_tracking")
        }

        // Sparse CRT scanlines reinforce the pixel face without reducing readability.
        fill.color = Color.argb(20, 0, 0, 0)
        var y = 0f
        while (y < h) { c.drawRect(0f, y, w.toFloat(), y + 1f, fill); y += 4f }
    }

    private fun drawEye(c: Canvas, r: RectF, gx: Float, gy: Float, mood: String, now: Long, right: Boolean) {
        fill.color = Color.rgb(21, 37, 57); c.drawRoundRect(r, 19f, 19f, fill)
        line.color = Color.rgb(79, 110, 141); line.strokeWidth = 5f; c.drawRoundRect(r, 19f, 19f, line)
        val inner = RectF(r.left + 18f, r.top + 17f, r.right - 18f, r.bottom - 17f)
        fill.color = Color.rgb(255, 218, 105); c.drawRoundRect(inner, 12f, 12f, fill)

        // Eyebrows are separate vector layers, giving dog-like emotional cues.
        line.strokeWidth = 7f
        line.color = Color.rgb(94, 68, 31)
        val browTilt = when (mood) { "happy" -> if (right) 6f else -6f; "curious" -> if (right) -10f else 10f; "error" -> if (right) 12f else -12f; else -> 0f }
        c.save(); c.rotate(browTilt, inner.centerX(), inner.top + 13f)
        c.drawLine(inner.left + 12f, inner.top + 10f, inner.right - 12f, inner.top + 10f, line)
        c.restore()

        val naturalBlink = now % 5200L in 0L..125L
        val blink = naturalBlink || mood == "sleepy"
        val pupilX = inner.centerX() + gx * inner.width() * .22f
        val pupilY = inner.centerY() + gy * inner.height() * .18f
        if (blink) {
            fill.color = Color.rgb(33, 36, 43)
            c.drawRect(inner.left + 16f, inner.centerY() - 4f, inner.right - 16f, inner.centerY() + 4f, fill)
            return
        }
        val iris = RectF(pupilX - inner.width() * .21f, pupilY - inner.height() * .27f,
            pupilX + inner.width() * .21f, pupilY + inner.height() * .27f)
        fill.color = Color.rgb(198, 125, 28); c.drawRoundRect(iris, 7f, 7f, fill)
        val pupil = RectF(pupilX - inner.width() * .13f, pupilY - inner.height() * .19f,
            pupilX + inner.width() * .13f, pupilY + inner.height() * .19f)
        when (mood) {
            "happy", "waving" -> {
                fill.color = Color.rgb(31, 37, 46)
                val path = Path().apply {
                    moveTo(pupil.left, pupil.centerY()); lineTo(pupil.centerX(), pupil.bottom)
                    lineTo(pupil.right, pupil.centerY()); lineTo(pupil.right, pupil.bottom)
                    lineTo(pupil.left, pupil.bottom); close()
                }
                c.drawPath(path, fill)
            }
            "curious" -> {
                fill.color = Color.rgb(31, 37, 46)
                val skew = if (right) -7f else 7f
                c.save(); c.rotate(skew, pupil.centerX(), pupil.centerY()); c.drawRoundRect(pupil, 5f, 5f, fill); c.restore()
            }
            "thinking" -> {
                fill.color = Color.rgb(31, 37, 46)
                val shifted = RectF(pupil).apply { offset(if (right) -7f else 7f, -5f) }
                c.drawRoundRect(shifted, 5f, 5f, fill)
            }
            "error" -> {
                line.color = Color.rgb(166, 39, 47); line.strokeWidth = 8f
                c.drawLine(pupil.left, pupil.top, pupil.right, pupil.bottom, line)
                c.drawLine(pupil.right, pupil.top, pupil.left, pupil.bottom, line)
            }
            else -> { fill.color = Color.rgb(31, 37, 46); c.drawRoundRect(pupil, 5f, 5f, fill) }
        }
        fill.color = Color.rgb(255, 250, 213)
        c.drawRect(pupil.left + 5f, pupil.top + 5f, pupil.left + 12f, pupil.top + 12f, fill)
    }

    private fun drawMouth(c: Canvas, x: Float, y: Float, mood: String, now: Long) {
        line.strokeWidth = 8f
        line.color = Color.rgb(226, 188, 92)
        val pulse = (sin(now / 180.0) * 3.0).toFloat()
        when (mood) {
            "happy", "waving" -> {
                val p = Path().apply { moveTo(x - 42f, y - 7f); quadTo(x, y + 30f + pulse, x + 42f, y - 7f) }
                c.drawPath(p, line)
            }
            "curious" -> {
                fill.color = Color.rgb(226, 188, 92)
                c.drawRoundRect(RectF(x - 12f, y - 12f, x + 12f, y + 12f), 6f, 6f, fill)
            }
            "thinking" -> c.drawLine(x - 25f, y + 5f, x + 18f, y - 5f, line)
            "sleepy" -> c.drawLine(x - 22f, y, x + 22f, y, line)
            "error" -> {
                val p = Path().apply { moveTo(x - 30f, y + 9f); quadTo(x, y - 18f, x + 30f, y + 9f) }
                c.drawPath(p, line)
            }
            "listening" -> {
                line.strokeWidth = 5f
                c.drawCircle(x, y, 12f + pulse.coerceAtLeast(0f), line)
            }
            else -> c.drawLine(x - 30f, y, x + 30f, y, line)
        }
    }

    private fun chip(c: Canvas, r: RectF, label: String, active: Boolean) {
        fill.color = if (active) Color.rgb(71, 95, 67) else Color.rgb(18, 34, 53)
        c.drawRoundRect(r, 8f, 8f, fill)
        line.color = if (active) Color.rgb(143, 190, 102) else Color.rgb(70, 99, 127)
        line.strokeWidth = 2f; c.drawRoundRect(r, 8f, 8f, line)
        text.textSize = 12f; text.color = Color.rgb(236, 240, 225); text.letterSpacing = .05f
        c.drawText(label, r.centerX() - text.measureText(label) / 2f, r.centerY() + 4f, text)
    }

    private fun drawNotification(c: Canvas, w: Int, h: Int, app: String, title: String) {
        val r = RectF(w / 2f - 190f, h - 100f, w / 2f + 190f, h - 20f)
        fill.color = Color.rgb(17, 34, 55); c.drawRoundRect(r, 12f, 12f, fill)
        line.color = Color.rgb(255, 205, 78); line.strokeWidth = 3f; c.drawRoundRect(r, 12f, 12f, line)
        fill.color = Color.rgb(255, 205, 78); c.drawCircle(r.left + 27f, r.centerY(), 7f, fill)
        text.letterSpacing = .08f; text.color = Color.rgb(255, 218, 105); text.textSize = 16f
        c.drawText(app.uppercase(), r.left + 48f, r.top + 29f, text)
        text.letterSpacing = .02f; text.color = Color.rgb(220, 232, 241); text.textSize = 14f
        c.drawText(title.take(38).uppercase(), r.left + 48f, r.bottom - 18f, text)
    }
}
