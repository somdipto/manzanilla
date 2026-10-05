package com.agentdeck.ui

import android.content.Context
import android.graphics.*
import com.agentdeck.DeckState
import com.agentdeck.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Interactive 16-bit comic-city companion.
 *
 * The background is generated artwork, while the hero is assembled from
 * independent vector parts (mask, torso, front/side arms and two leg poses).
 * That keeps the suit crisp and lets every pose respond to camera tracking.
 */
class WebPetRenderer(context: Context) {
    private val city = BitmapFactory.decodeResource(context.resources, R.drawable.webby_comic_city)
    private val fill = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
    private val line = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
    }
    private val sprite = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        isDither = false
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    private var activeAnchor = 1
    private var jumpFrom = 1
    private var jumpTo = 1
    private var jumpStarted = 0L
    private var jumpDuration = 1450L
    private var lastJumpToken = 0L
    private var lastAutoJump = 0L
    private var trackedHeroX = Float.NaN
    private var trackedFootY = Float.NaN

    fun draw(c: Canvas, w: Int, h: Int, st: DeckState, now: Long,
             zone: (RectF, String) -> Unit, showControls: Boolean = true) {
        drawCity(c, w, h, now)

        val anchors = arrayOf(
            PointF(w * .145f, h * .795f),
            PointF(w * .500f, h * .795f),
            PointF(w * .855f, h * .795f)
        )
        if (lastAutoJump == 0L) lastAutoJump = now

        if (st.petJumpStartedAt > lastJumpToken) {
            lastJumpToken = st.petJumpStartedAt
            beginJump(now, st.petJumpIndex.coerceIn(0, 2), 1500L)
        } else if (jumpStarted == 0L && !st.petFacePresent &&
            st.petInteractionStage != "choosing" && now - lastAutoJump > 5400L) {
            beginJump(now, (activeAnchor + 1) % 3, 1300L)
        }

        val jumping = jumpStarted > 0L
        val jumpP = if (jumping)
            ((now - jumpStarted).toFloat() / jumpDuration).coerceIn(0f, 1f) else 1f
        val eased = jumpP * jumpP * (3f - 2f * jumpP)
        val from = anchors[jumpFrom]
        val to = anchors[jumpTo]
        val groundWalk = if (!jumping) sin(now / 760.0).toFloat() * w * .035f else 0f
        var heroX = if (jumping) lerp(from.x, to.x, eased) else anchors[activeAnchor].x + groundWalk
        var footY = if (jumping) lerp(from.y, to.y, eased) else anchors[activeAnchor].y
        if (jumping) footY -= sin(jumpP * PI).toFloat() * h * .235f
        if (jumping && jumpP >= 1f) {
            activeAnchor = jumpTo
            jumpStarted = 0L
            lastAutoJump = now
            heroX = anchors[activeAnchor].x
            footY = anchors[activeAnchor].y
        }

        // When a face is present, Webby mirrors its position in the camera
        // instead of merely turning his mask. Wide mapping makes small, natural
        // head movements visible; interpolation removes low-light camera jitter.
        if (!jumping && st.petTrackingEnabled && st.petFacePresent) {
            val desiredX = (w * .50f - st.petTargetX * w * .40f)
                .coerceIn(w * .10f, w * .90f)
            val desiredY = (h * .78f + st.petTargetY * h * .48f)
                .coerceIn(h * .31f, h * .82f)
            if (trackedHeroX.isNaN()) trackedHeroX = desiredX
            if (trackedFootY.isNaN()) trackedFootY = desiredY
            trackedHeroX += (desiredX - trackedHeroX) * .24f
            trackedFootY += (desiredY - trackedFootY) * .24f
            heroX = trackedHeroX
            footY = trackedFootY
        } else if (!st.petFacePresent) {
            trackedHeroX = Float.NaN
            trackedFootY = Float.NaN
        }

        val notification = st.pcNotificationApp.isNotBlank() && now < st.pcNotificationUntil
        val lookX = when {
            notification -> if (heroX < w * .5f) 1f else -1f
            st.petTrackingEnabled && st.petFacePresent -> st.petTargetX.coerceIn(-1f, 1f)
            else -> sin(now / 2600.0).toFloat() * .35f
        }
        val waving = st.petExpression == "waving" || now < st.petWebBurstUntil
        val climbing = !jumping && st.petFacePresent && footY < h * .69f
        drawHero(c, heroX, footY, now, jumping || climbing, waving, lookX, st)

        val message = when {
            notification -> st.pcNotificationTitle.ifBlank {
                "HEY! ${st.pcNotificationApp.uppercase()} HAS A NOTIFICATION"
            }
            now < st.petSpeechUntil && st.petSpeech.isNotBlank() -> st.petSpeech
            st.petInteractionStage == "choosing" -> "1 CHROME  2 SPAIN WORK  3 STREAM DECK"
            st.petExpression == "happy" -> "HELLO! WAVE IF YOU NEED ME"
            st.petLookingAtDevice -> "ATTENTION LOCKED"
            st.petFacePresent -> "I CAN SEE YOU - COMMANDS OFF"
            else -> "YOUR FRIENDLY NEIGHBORHOOD CONSOLE"
        }
        drawComicBubble(c, w, h, heroX, message, notification)

        if (showControls) {
            val back = RectF(12f, 28f, 102f, 68f)
            val camera = RectF(w - 242f, 28f, w - 140f, 68f)
            val calibrate = RectF(w - 132f, 28f, w - 12f, 68f)
            chip(c, back, "HOME", false)
            chip(c, camera, if (st.petTrackingEnabled) "CAM ON" else "CAM OFF", st.petTrackingEnabled)
            chip(c, calibrate, "CALIBRATE", false)
            zone(back, "pet_back")
            zone(camera, "pet_tracking")
            zone(calibrate, "pet_calibrate")
        }

        val legend = if (st.petInteractionStage == "choosing")
            "LOWER HAND, THEN SHOW ONCE:  1 CHROME   2 SPAIN WORK   3 STREAM DECK"
        else "LOOK AT WEBBY TO ARM 1 / 2 / 3  •  WAVE FOR A TRICK"
        text.textSize = if (w < 800) 10f else 12f
        text.letterSpacing = .055f
        text.color = Color.rgb(232, 238, 224)
        c.drawText(legend, w / 2f - text.measureText(legend) / 2f, h - 14f, text)
        if (st.petGestureCount in 1..3) {
            val badge = "SAW ${st.petGestureCount}"
            text.textSize = 11f
            text.color = Color.rgb(255, 222, 87)
            c.drawText(badge, w - text.measureText(badge) - 13f, h - 34f, text)
        }

        // Soft CRT scanlines, deliberately lighter than the old saver.
        fill.color = Color.argb(22, 0, 0, 0)
        var sy = 0f
        while (sy < h) {
            c.drawRect(0f, sy, w.toFloat(), sy + 1f, fill)
            sy += 4f
        }
    }

    private fun beginJump(now: Long, target: Int, duration: Long) {
        jumpFrom = activeAnchor
        jumpTo = if (target == activeAnchor) (activeAnchor + 1) % 3 else target
        jumpStarted = now
        jumpDuration = duration
    }

    private fun drawCity(c: Canvas, w: Int, h: Int, now: Long) {
        val cameraX = (sin(now / 9200.0) * 4.0).toFloat()
        val cameraY = (sin(now / 11300.0 + .8) * 2.0).toFloat()
        val full = RectF(-8f + cameraX, -5f + cameraY, w + 8f + cameraX, h + 5f + cameraY)
        c.drawBitmap(city, null, full, sprite)

        // Independent skyline drift gives a small amount of true depth without
        // turning the old-game background into a modern 3-D camera move.
        val farSrc = Rect(0, 0, city.width, (city.height * .35f).toInt())
        val farShift = (sin(now / 15000.0) * 2.5).toFloat()
        c.drawBitmap(city, farSrc, RectF(farShift - 2f, 0f, w + farShift + 2f, h * .35f), sprite)

        val pulse = ((sin(now / 850.0) + 1.0) * .5).toFloat()
        val windowColor = Color.argb((18 + pulse * 22).toInt(), 255, 223, 92)
        fill.color = windowColor
        val windows = arrayOf(
            RectF(w * .075f, h * .405f, w * .205f, h * .590f),
            RectF(w * .440f, h * .425f, w * .560f, h * .610f),
            RectF(w * .805f, h * .405f, w * .935f, h * .590f)
        )
        windows.forEach { c.drawRect(it, fill) }
    }

    private fun drawHero(c: Canvas, x: Float, footY: Float, now: Long, jumping: Boolean,
                         waving: Boolean, lookX: Float, st: DeckState) {
        val u = (min(c.width / 854f, c.height / 480f) * .82f).coerceAtLeast(.62f)
        val facing = if (lookX < -.18f) -1f else 1f
        val runPhase = sin(now / 105.0).toFloat()
        c.save()
        c.translate(x, footY)
        c.scale(facing * u, u)

        drawLegs(c, jumping, runPhase)
        drawTorso(c)
        drawArms(c, jumping, waving, runPhase)
        drawMask(c, abs(lookX), lookX)

        if (now < st.petWebBurstUntil) {
            line.color = Color.rgb(233, 240, 232)
            line.strokeWidth = 3f
            c.drawLine(43f, -86f, 154f, -142f, line)
            for (i in 1..4) {
                val wx = 43f + i * 24f
                val wy = -86f - i * 12f
                c.drawCircle(wx, wy, 3f + i * 1.5f, line)
            }
        }
        c.restore()
    }

    /** Separate crouch/stride/jump leg component. */
    private fun drawLegs(c: Canvas, jumping: Boolean, phase: Float) {
        line.strokeWidth = 15f
        line.color = Color.rgb(22, 65, 137)
        if (jumping) {
            c.drawLine(-10f, -50f, -31f, -31f, line)
            c.drawLine(-31f, -31f, -14f, -8f, line)
            c.drawLine(10f, -50f, 28f, -26f, line)
            c.drawLine(28f, -26f, 48f, -14f, line)
        } else {
            val stride = phase * 12f
            c.drawLine(-9f, -51f, -14f + stride, -23f, line)
            c.drawLine(-14f + stride, -23f, -19f + stride, -3f, line)
            c.drawLine(9f, -51f, 15f - stride, -23f, line)
            c.drawLine(15f - stride, -23f, 20f - stride, -3f, line)
        }
        line.color = Color.rgb(197, 28, 45)
        line.strokeWidth = 12f
        if (jumping) {
            c.drawLine(-14f, -8f, -2f, -5f, line)
            c.drawLine(48f, -14f, 61f, -13f, line)
        } else {
            val stride = phase * 12f
            c.drawLine(-19f + stride, -4f, -7f + stride, -3f, line)
            c.drawLine(20f - stride, -4f, 32f - stride, -3f, line)
        }
    }

    /** Separate front torso component with classic red shoulders and blue waist. */
    private fun drawTorso(c: Canvas) {
        fill.color = Color.rgb(20, 63, 134)
        val body = Path().apply {
            moveTo(-25f, -100f); lineTo(25f, -100f); lineTo(19f, -48f)
            lineTo(-19f, -48f); close()
        }
        c.drawPath(body, fill)
        fill.color = Color.rgb(198, 27, 45)
        val chest = Path().apply {
            moveTo(-26f, -101f); lineTo(26f, -101f); lineTo(18f, -73f)
            lineTo(8f, -62f); lineTo(-8f, -62f); lineTo(-18f, -73f); close()
        }
        c.drawPath(chest, fill)
        line.color = Color.rgb(10, 17, 36)
        line.strokeWidth = 3f
        c.drawPath(body, line)

        // Compact spider mark remains readable at the real device size.
        fill.color = Color.rgb(8, 13, 26)
        c.drawOval(RectF(-4f, -88f, 4f, -71f), fill)
        line.color = Color.rgb(8, 13, 26)
        line.strokeWidth = 2.5f
        for (side in intArrayOf(-1, 1)) {
            c.drawLine(side * 3f, -84f, side * 10f, -93f, line)
            c.drawLine(side * 4f, -81f, side * 13f, -84f, line)
            c.drawLine(side * 4f, -77f, side * 12f, -70f, line)
        }
    }

    /** Separate front/side arm component; jump and wave use distinct silhouettes. */
    private fun drawArms(c: Canvas, jumping: Boolean, waving: Boolean, phase: Float) {
        line.strokeWidth = 12f
        line.color = Color.rgb(196, 27, 45)
        when {
            jumping -> {
                c.drawLine(-21f, -95f, -46f, -112f, line)
                c.drawLine(-46f, -112f, -64f, -96f, line)
                c.drawLine(21f, -95f, 44f, -119f, line)
                c.drawLine(44f, -119f, 62f, -130f, line)
            }
            waving -> {
                c.drawLine(-21f, -94f, -43f, -72f, line)
                c.drawLine(-43f, -72f, -48f, -51f, line)
                c.drawLine(21f, -94f, 39f, -117f, line)
                c.drawLine(39f, -117f, 34f, -145f, line)
            }
            else -> {
                c.drawLine(-21f, -94f, -40f - phase * 6f, -72f + phase * 5f, line)
                c.drawLine(-40f - phase * 6f, -72f + phase * 5f, -34f, -53f, line)
                c.drawLine(21f, -94f, 40f + phase * 6f, -72f - phase * 5f, line)
                c.drawLine(40f + phase * 6f, -72f - phase * 5f, 34f, -53f, line)
            }
        }
        // Dark inner-arm pixels recreate the stronger 16-bit sprite shading.
        line.color = Color.rgb(103, 16, 36)
        line.strokeWidth = 3f
        c.drawLine(-24f, -96f, -39f, -78f, line)
        c.drawLine(24f, -96f, 39f, -78f, line)
    }

    /** Front/three-quarter mask component selected from the camera target. */
    private fun drawMask(c: Canvas, turn: Float, rawLookX: Float) {
        val side = turn.coerceIn(0f, 1f)
        val shift = rawLookX.coerceIn(-1f, 1f) * 4f
        fill.color = Color.rgb(202, 29, 47)
        c.drawOval(RectF(-23f + shift, -143f, 23f + shift, -96f), fill)
        line.color = Color.rgb(9, 16, 34)
        line.strokeWidth = 3f
        c.drawOval(RectF(-23f + shift, -143f, 23f + shift, -96f), line)

        // Web mesh follows the mask instead of floating over the whole body.
        line.color = Color.rgb(24, 25, 42)
        line.strokeWidth = 1.6f
        c.drawLine(shift, -141f, shift, -98f, line)
        c.drawLine(-20f + shift, -122f, 20f + shift, -122f, line)
        c.drawArc(RectF(-17f + shift, -138f, 17f + shift, -108f), 18f, 144f, false, line)
        c.drawArc(RectF(-19f + shift, -130f, 19f + shift, -99f), 198f, 144f, false, line)
        for (angle in intArrayOf(-64, -32, 32, 64)) {
            val ex = cos(Math.toRadians(angle.toDouble())).toFloat() * 22f + shift
            val ey = -121f + sin(Math.toRadians(angle.toDouble())).toFloat() * 21f
            c.drawLine(shift, -121f, ex, ey, line)
        }

        val eyeSqueeze = side * 4f
        val eyeOffset = shift * .55f
        val leftEye = Path().apply {
            moveTo(-17f + eyeOffset, -130f)
            lineTo(-5f + eyeOffset + eyeSqueeze, -126f)
            lineTo(-8f + eyeOffset + eyeSqueeze, -107f)
            lineTo(-18f + eyeOffset, -114f)
            close()
        }
        val rightEye = Path().apply {
            moveTo(17f + eyeOffset, -130f)
            lineTo(5f + eyeOffset - eyeSqueeze, -126f)
            lineTo(8f + eyeOffset - eyeSqueeze, -107f)
            lineTo(18f + eyeOffset, -114f)
            close()
        }
        line.color = Color.rgb(6, 10, 22)
        line.strokeWidth = 5f
        c.drawPath(leftEye, line); c.drawPath(rightEye, line)
        fill.color = Color.rgb(244, 241, 218)
        c.drawPath(leftEye, fill); c.drawPath(rightEye, fill)
    }

    private fun drawComicBubble(c: Canvas, w: Int, h: Int, heroX: Float,
                                message: String, urgent: Boolean) {
        val leftSide = heroX > w * .5f
        val bubbleW = (w * .39f).coerceIn(235f, 390f)
        val r = if (leftSide) RectF(25f, 80f, 25f + bubbleW, 169f)
            else RectF(w - bubbleW - 25f, 80f, w - 25f, 169f)
        fill.color = if (urgent) Color.rgb(255, 225, 75) else Color.rgb(246, 242, 216)
        c.drawRoundRect(r, 10f, 10f, fill)
        line.color = Color.rgb(8, 14, 31)
        line.strokeWidth = 5f
        c.drawRoundRect(r, 10f, 10f, line)
        val tailX = if (leftSide) r.right - 30f else r.left + 30f
        val tail = Path().apply {
            moveTo(tailX - 12f, r.bottom - 2f)
            lineTo(heroX, h * .48f)
            lineTo(tailX + 16f, r.bottom - 2f)
            close()
        }
        c.drawPath(tail, fill); c.drawPath(tail, line)

        text.color = Color.rgb(14, 19, 34)
        text.textSize = if (message.length > 48) 15f else 18f
        text.letterSpacing = .025f
        val lines = mutableListOf<String>()
        var current = ""
        for (word in message.split(' ')) {
            val next = if (current.isEmpty()) word else "$current $word"
            if (text.measureText(next) > r.width() - 30f && current.isNotEmpty()) {
                lines += current; current = word
            } else current = next
        }
        if (current.isNotEmpty()) lines += current
        lines.take(3).forEachIndexed { i, s -> c.drawText(s, r.left + 15f, r.top + 26f + i * 22f, text) }
        fill.color = Color.rgb(198, 27, 45)
        c.drawRect(r.right - 22f, r.top + 9f, r.right - 10f, r.top + 21f, fill)
    }

    private fun chip(c: Canvas, r: RectF, label: String, active: Boolean) {
        fill.color = if (active) Color.rgb(187, 24, 44) else Color.rgb(5, 18, 43)
        c.drawRoundRect(r, 8f, 8f, fill)
        line.color = Color.rgb(235, 238, 218)
        line.strokeWidth = 2f
        c.drawRoundRect(r, 8f, 8f, line)
        text.textSize = 12f
        text.letterSpacing = .035f
        text.color = Color.WHITE
        c.drawText(label, r.centerX() - text.measureText(label) / 2f, r.centerY() + 4f, text)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
