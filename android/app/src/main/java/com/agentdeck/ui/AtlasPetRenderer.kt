package com.agentdeck.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.agentdeck.DeckState
import com.agentdeck.R
import com.agentdeck.pet.SuhairAtlasContract
import com.agentdeck.pet.SuhairBehavior
import com.agentdeck.pet.SuhairIntent
import com.agentdeck.pet.SuhairRegion
import com.agentdeck.pet.SuhairScene
import kotlin.math.sin

/**
 * Deterministic renderer for Suhair's approved Codex-compatible v2 atlas.
 *
 * This is a custom, OpenAI-assisted project asset created for Suhair's project. It is not an
 * official OpenAI mascot, product character, endorsement, or certification. The renderer is an
 * ordinary Android WebP/JSON consumer and is not locked to Codex or OpenAI.
 */
class AtlasPetRenderer(context: Context) {
    private val atlas: Bitmap = context.assets.open("pets/suhair/spritesheet.webp").use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: error("Unable to decode Suhair spritesheet")
    }.also {
        require(it.width == SuhairAtlasContract.WIDTH && it.height == SuhairAtlasContract.HEIGHT) {
            "Unexpected Suhair atlas ${it.width}x${it.height}"
        }
    }

    /** Decoded once. The renderer never downloads or generates an image during playback. */
    private val sceneBitmaps: Map<SuhairScene, Bitmap> = mapOf(
        SuhairScene.PARK to R.drawable.suhair_scene_park,
        SuhairScene.OFFICE to R.drawable.suhair_scene_office,
        SuhairScene.STUDIO to R.drawable.suhair_scene_studio,
        SuhairScene.LAB to R.drawable.suhair_scene_lab,
        SuhairScene.ARCHIVE to R.drawable.suhair_scene_archive,
        SuhairScene.COMMAND to R.drawable.suhair_scene_command_light
    ).mapValues { (_, resource) ->
        BitmapFactory.decodeResource(context.resources, resource, BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.RGB_565
        }) ?: error("Unable to decode Suhair scene $resource")
    }

    /** Studio prop is a separate alpha layer so the room and animation atlas stay reusable. */
    private val studioDeskLaptop: Bitmap = BitmapFactory.decodeResource(
        context.resources,
        R.drawable.suhair_studio_desk_laptop,
        BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    ) ?: error("Unable to decode Suhair studio desk overlay")

    private val commandDeskLaptop: Bitmap = BitmapFactory.decodeResource(
        context.resources, R.drawable.suhair_command_desk_laptop_light_v2,
        BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    ) ?: error("Unable to decode Suhair command desk overlay")

    private val sprite = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private var smoothedTargetX = 0f
    private var smoothedTargetY = 0f
    private var direction: Int? = null
    private var candidateDirection: Int? = null
    private var candidateSince = 0L
    private var shaderKey = ""
    private var skyShader: Shader? = null

    fun draw(
        c: Canvas,
        w: Int,
        h: Int,
        st: DeckState,
        now: Long,
        zone: (RectF, String) -> Unit,
        showControls: Boolean = true
    ) {
        drawSceneBackground(c, w, h, st, now)
        if (!st.suhairPet.sceneTransitionActive && st.suhairPet.scene == SuhairScene.COMMAND) {
            drawCommandRoomScreen(c, st)
            if (st.pcNotificationBody.isNotBlank())
                zone(RectF(72f, 90f, 468f, 304f), "notification_open")
        }

        val state = st.suhairPet
        val characterX = characterCenterX(w, state, now)
        // Shared atlas baseline: lifted above the foreground flower strip so every row stands on
        // the path/floor rather than appearing embedded in the decorative foreground.
        val characterBottom = h - sceneBaselineOffset(state, now)
        val characterHeight = (h * .55f).coerceIn(224f, 266f)
        val characterWidth = characterHeight * SuhairAtlasContract.CELL_WIDTH / SuhairAtlasContract.CELL_HEIGHT

        // A small grounded shadow makes every atlas row share the same visual floor.
        fill.color = Color.argb(72, 2, 12, 30)
        c.drawOval(RectF(characterX - characterWidth * .27f, characterBottom - 11f,
            characterX + characterWidth * .27f, characterBottom + 5f), fill)

        val cell = selectCell(st, now)
        val src = Rect(
            cell.second * SuhairAtlasContract.CELL_WIDTH,
            cell.first * SuhairAtlasContract.CELL_HEIGHT,
            (cell.second + 1) * SuhairAtlasContract.CELL_WIDTH,
            (cell.first + 1) * SuhairAtlasContract.CELL_HEIGHT
        )
        val dst = RectF(characterX - characterWidth / 2f, characterBottom - characterHeight,
            characterX + characterWidth / 2f, characterBottom)
        c.drawBitmap(atlas, src, dst, sprite)

        // Suhair works behind the desk: the viewer sees the laptop lid while its screen faces him.
        // Draw after the atlas so the prop naturally occludes his lower body and aligns with the
        // working-row hands. Keep room transitions clean by showing it only once STUDIO settles.
        if (!state.sceneTransitionActive && state.scene == SuhairScene.STUDIO) {
            c.drawBitmap(
                studioDeskLaptop,
                null,
                RectF(w - 303f, h - 229f, w - 32f, h - 15f),
                sprite
            )
        }
        if (!state.sceneTransitionActive && state.scene == SuhairScene.COMMAND &&
            state.intent in setOf(SuhairIntent.WORK, SuhairIntent.REVIEW, SuhairIntent.WAIT)) {
            c.drawBitmap(commandDeskLaptop, null,
                RectF(w - 369f, h - 275f, w + 15f, h - 5f), sprite)
        }

        if (state.message.isNotBlank() && state.scene != SuhairScene.COMMAND)
            drawSpeechBubble(c, w, h, characterX, state.source,
            state.message, state.progress, state.intent)

        drawStatus(c, w, h, st, now)
        if (showControls) drawControls(c, w, st, zone)

        // Very light scanlines preserve the source style without obscuring small text.
        fill.color = Color.argb(13, 0, 0, 0)
        var y = 0f
        while (y < h) {
            c.drawRect(0f, y, w.toFloat(), y + 1f, fill)
            y += 5f
        }
    }

    fun drawPreview(c: Canvas, r: RectF, now: Long) {
        c.save()
        c.clipPath(Path().apply { addRoundRect(r, 9f, 9f, Path.Direction.CW) })
        fill.shader = LinearGradient(r.left, r.top, r.left, r.bottom,
            Color.rgb(28, 91, 143), Color.rgb(242, 161, 106), Shader.TileMode.CLAMP)
        c.drawRect(r, fill)
        fill.shader = null
        val frame = SuhairAtlasContract.frameFor(SuhairAtlasContract.IDLE, now)
        val src = Rect(frame * SuhairAtlasContract.CELL_WIDTH, 0,
            (frame + 1) * SuhairAtlasContract.CELL_WIDTH, SuhairAtlasContract.CELL_HEIGHT)
        val height = r.height() * .92f
        val width = height * SuhairAtlasContract.CELL_WIDTH / SuhairAtlasContract.CELL_HEIGHT
        val dst = RectF(r.centerX() - width / 2f, r.bottom - height, r.centerX() + width / 2f, r.bottom)
        c.drawBitmap(atlas, src, dst, sprite)
        c.restore()
    }

    private fun selectCell(st: DeckState, now: Long): Pair<Int, Int> {
        val state = st.suhairPet
        val looking = state.intent == SuhairIntent.IDLE && st.petTrackingEnabled && st.petFacePresent
        if (looking) {
            smoothedTargetX += (st.petTargetX - smoothedTargetX) * .18f
            smoothedTargetY += (st.petTargetY - smoothedTargetY) * .18f
            val next = SuhairAtlasContract.directionForTarget(smoothedTargetX, smoothedTargetY)
            if (next != candidateDirection) {
                candidateDirection = next
                candidateSince = now
            } else if (now - candidateSince >= 100L) {
                direction = stepToward(direction, next)
            }
            return direction?.let(SuhairAtlasContract::gazeCell) ?: (0 to 6)
        }

        smoothedTargetX *= .82f
        smoothedTargetY *= .82f
        direction = null
        candidateDirection = null
        return SuhairAtlasContract.cellForState(
            state.intent,
            state.scene,
            now - state.startedAt
        )
    }

    /** Adjacent stepping prevents abrupt 180° direction jumps when camera inference is noisy. */
    private fun stepToward(current: Int?, target: Int?): Int? {
        if (target == null) return null
        if (current == null || current == target) return target
        val clockwise = (target - current + 16) % 16
        return if (clockwise <= 8) (current + 1) % 16 else (current + 15) % 16
    }

    private fun characterCenterX(w: Int, state: com.agentdeck.pet.SuhairPetRuntime, now: Long): Float {
        fun scenePosition(region: SuhairRegion, scene: SuhairScene): Float {
            val workstationNudge = if (scene == SuhairScene.STUDIO && region == SuhairRegion.RIGHT)
                -18f else 0f
            return regionX(w, region) + workstationNudge
        }
        val startScene = if (state.sceneTransitionActive) state.originScene else state.scene
        val endScene = if (state.sceneTransitionActive) state.targetScene else state.scene
        val start = scenePosition(state.originRegion, startScene)
        val end = scenePosition(state.targetRegion, endScene)
        val t = SuhairBehavior.movementProgress(state, now)
        val eased = t * t * (3f - 2f * t)
        return start + (end - start) * eased
    }

    private fun regionX(w: Int, region: SuhairRegion): Float = when (region) {
        SuhairRegion.LEFT -> w * .21f
        SuhairRegion.CENTER -> w * .50f
        SuhairRegion.RIGHT -> w * .79f
    }

    /** Each fixed room has one measured stage line; transitions ease between the two baselines. */
    private fun sceneBaselineOffset(state: com.agentdeck.pet.SuhairPetRuntime, now: Long): Float {
        fun offset(scene: SuhairScene) = if (scene == SuhairScene.PARK) 115f else 50f
        if (!state.sceneTransitionActive) return offset(state.scene)
        val t = SuhairBehavior.movementProgress(state, now)
        val eased = t * t * (3f - 2f * t)
        return offset(state.originScene) + (offset(state.targetScene) - offset(state.originScene)) * eased
    }

    /**
     * Slides two already-decoded rooms in the opposite direction to the running row. This creates
     * the travelling-film illusion without synthesizing frames or allocating bitmaps per frame.
     */
    private fun drawSceneBackground(c: Canvas, w: Int, h: Int, st: DeckState, now: Long) {
        val state = st.suhairPet
        if (!state.sceneTransitionActive) {
            drawSceneBitmap(c, sceneBitmaps[state.scene], 0f, w, h, now)
            return
        }

        val t = SuhairBehavior.movementProgress(state, now)
        val eased = t * t * (3f - 2f * t)
        val movingRight = state.intent == SuhairIntent.MOVE_RIGHT
        val oldX = if (movingRight) -w * eased else w * eased
        val newX = if (movingRight) w * (1f - eased) else -w * (1f - eased)
        drawSceneBitmap(c, sceneBitmaps[state.originScene], oldX, w, h, now)
        drawSceneBitmap(c, sceneBitmaps[state.targetScene], newX, w, h, now)
    }

    private fun drawSceneBitmap(c: Canvas, bitmap: Bitmap?, x: Float, w: Int, h: Int, now: Long) {
        if (bitmap == null) {
            c.save()
            c.translate(x, 0f)
            drawProceduralBackground(c, w, h, now)
            c.restore()
            return
        }
        val destination = RectF(x, 0f, x + w, h.toFloat())
        c.drawBitmap(bitmap, null, destination, sprite)
        // A restrained navy wash keeps the white speech bubble and avatar readable in every room.
        fill.color = Color.argb(20, 4, 20, 40)
        c.drawRect(destination, fill)
    }

    /** Draws live project/notification data onto the blank display that is part of the room art. */
    private fun drawCommandRoomScreen(c: Canvas, st: DeckState) {
        val state = st.suhairPet
        // Bounds match the blank display in the light command-room artwork.
        val screen = RectF(72f, 90f, 468f, 304f)
        fill.color = Color.argb(206, 0, 8, 4)
        c.drawRoundRect(screen, 5f, 5f, fill)
        line.color = Color.argb(210, 69, 255, 128)
        line.strokeWidth = 2f
        c.drawRoundRect(screen, 5f, 5f, line)

        val source = state.source.ifBlank { st.nowName }.ifBlank { st.nowKind }.ifBlank { "MANZANILLA" }
        val message = st.pcNotificationBody.takeIf {
            state.intent == SuhairIntent.SHOW_NOTIFICATION && it.isNotBlank()
        }.orEmpty().ifBlank { state.message }.ifBlank { st.nowDetail }.ifBlank { st.nowTask }
            .ifBlank { "Your AI companion is ready." }
        val stateLabel = when (state.intent) {
            SuhairIntent.WORK -> "WORKING"
            SuhairIntent.WAIT -> "WAITING"
            SuhairIntent.REVIEW -> "READY FOR REVIEW"
            SuhairIntent.FAIL -> "NEEDS ATTENTION"
            SuhairIntent.SHOW_NOTIFICATION -> "NEW NOTIFICATION"
            else -> "CURRENT ACTIVITY"
        }

        label.typeface = Typeface.MONOSPACE
        body.typeface = Typeface.MONOSPACE
        label.color = Color.rgb(91, 255, 139)
        label.textSize = 12f
        c.drawText(stateLabel, screen.left + 22f, screen.top + 31f, label)
        label.color = Color.rgb(161, 255, 185)
        label.textSize = 24f
        c.drawText(source.uppercase().take(24), screen.left + 22f, screen.top + 66f, label)
        body.color = Color.rgb(105, 255, 145)
        body.textSize = 16f
        wrap(message, body, screen.width() - 44f, 4).forEachIndexed { i, text ->
            c.drawText(text, screen.left + 22f, screen.top + 101f + i * 22f, body)
        }
        if (state.progress >= 0f) {
            val track = RectF(screen.left + 22f, screen.bottom - 27f, screen.right - 22f, screen.bottom - 17f)
            fill.color = Color.rgb(34, 56, 75)
            c.drawRoundRect(track, 5f, 5f, fill)
            fill.color = Color.rgb(78, 194, 143)
            c.drawRoundRect(RectF(track.left, track.top,
                track.left + track.width() * state.progress, track.bottom), 5f, 5f, fill)
        }
    }

    /** Offline, deterministic, allocation-light scene. No network or per-frame generation. */
    private fun drawProceduralBackground(c: Canvas, w: Int, h: Int, now: Long) {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val period = when (hour) {
            in 6..10 -> "morning"
            in 11..17 -> "day"
            in 18..20 -> "sunset"
            else -> "night"
        }
        val key = "$w:$h:$period"
        if (shaderKey != key) {
            shaderKey = key
            val colors = when (period) {
                "morning" -> intArrayOf(Color.rgb(55, 123, 189), Color.rgb(245, 169, 115), Color.rgb(253, 223, 157))
                "day" -> intArrayOf(Color.rgb(28, 100, 171), Color.rgb(85, 170, 211), Color.rgb(236, 219, 157))
                "sunset" -> intArrayOf(Color.rgb(15, 48, 104), Color.rgb(211, 83, 104), Color.rgb(249, 171, 87))
                else -> intArrayOf(Color.rgb(3, 14, 42), Color.rgb(8, 33, 78), Color.rgb(35, 45, 82))
            }
            skyShader = LinearGradient(0f, 0f, 0f, h.toFloat(), colors, null, Shader.TileMode.CLAMP)
        }
        fill.shader = skyShader
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill)
        fill.shader = null

        if (period == "night") {
            fill.color = Color.argb(185, 255, 244, 182)
            repeat(22) { i ->
                val x = ((i * 137 + 41) % w).toFloat()
                val y = ((i * 71 + 17) % (h * .56f).toInt().coerceAtLeast(1)).toFloat()
                val s = if (i % 6 == 0) 3f else 1.5f
                c.drawRect(x, y, x + s, y + s, fill)
            }
        } else {
            fill.color = Color.argb(205, 255, 231, 156)
            c.drawCircle(w * .83f, h * .19f, 25f, fill)
        }

        // Distant clouds are independent procedural layers with slow deterministic drift.
        repeat(4) { i ->
            val travel = ((now / (90L + i * 17L) + i * 211L) % (w + 260)).toFloat() - 130f
            val y = 67f + i * 39f
            val scale = .65f + i * .08f
            drawCloud(c, travel, y, scale, if (period == "night")
                Color.argb(62, 188, 207, 230) else Color.argb(118, 255, 241, 219))
        }

        val far = Path().apply {
            moveTo(0f, h * .63f)
            lineTo(w * .14f, h * .42f); lineTo(w * .29f, h * .62f)
            lineTo(w * .48f, h * .35f); lineTo(w * .65f, h * .61f)
            lineTo(w * .82f, h * .43f); lineTo(w.toFloat(), h * .64f)
            lineTo(w.toFloat(), h.toFloat()); lineTo(0f, h.toFloat()); close()
        }
        fill.color = if (period == "night") Color.rgb(8, 31, 60) else Color.rgb(37, 82, 78)
        c.drawPath(far, fill)

        val near = Path().apply {
            moveTo(0f, h * .77f)
            lineTo(w * .18f, h * .61f); lineTo(w * .36f, h * .76f)
            lineTo(w * .61f, h * .58f); lineTo(w * .79f, h * .78f)
            lineTo(w.toFloat(), h * .65f); lineTo(w.toFloat(), h.toFloat())
            lineTo(0f, h.toFloat()); close()
        }
        fill.color = if (period == "night") Color.rgb(5, 22, 45) else Color.rgb(20, 59, 57)
        c.drawPath(near, fill)

        // Ground strip and small status lights give the companion a stable stage.
        fill.color = Color.rgb(3, 17, 32)
        c.drawRect(0f, h - 35f, w.toFloat(), h.toFloat(), fill)
        repeat(12) { i ->
            fill.color = when (i % 3) {
                0 -> Color.rgb(238, 184, 70)
                1 -> Color.rgb(73, 183, 143)
                else -> Color.rgb(82, 157, 211)
            }
            val pulse = (sin(now / 900.0 + i) * 2.0).toFloat()
            c.drawCircle(18f + i * (w - 36f) / 11f, h - 17f, 2.5f + pulse.coerceAtLeast(0f), fill)
        }
    }

    private fun drawCloud(c: Canvas, x: Float, y: Float, scale: Float, color: Int) {
        fill.color = color
        c.drawOval(RectF(x, y, x + 126f * scale, y + 28f * scale), fill)
        c.drawCircle(x + 34f * scale, y, 24f * scale, fill)
        c.drawCircle(x + 72f * scale, y - 8f * scale, 30f * scale, fill)
        c.drawCircle(x + 103f * scale, y + 2f * scale, 20f * scale, fill)
    }

    private fun drawSpeechBubble(
        c: Canvas,
        w: Int,
        h: Int,
        characterX: Float,
        source: String,
        message: String,
        progress: Float,
        intent: SuhairIntent
    ) {
        val bubbleW = (w * .43f).coerceIn(300f, 390f)
        val leftSide = characterX > w * .52f
        val left = if (leftSide) 22f else w - bubbleW - 22f
        val r = RectF(left, 75f, left + bubbleW, (h * .43f).coerceAtLeast(190f))
        fill.color = Color.argb(232, 250, 247, 233)
        c.drawRoundRect(r, 16f, 16f, fill)
        line.color = when (intent) {
            SuhairIntent.FAIL, SuhairIntent.WARN -> Color.rgb(210, 83, 78)
            SuhairIntent.CELEBRATE -> Color.rgb(73, 167, 103)
            else -> Color.rgb(63, 110, 153)
        }
        line.strokeWidth = 3f
        c.drawRoundRect(r, 16f, 16f, line)
        val tipX = if (leftSide) r.right - 38f else r.left + 38f
        val tip = Path().apply {
            moveTo(tipX - 12f, r.bottom - 1f)
            lineTo(characterX.coerceIn(r.left + 8f, r.right - 8f), r.bottom + 23f)
            lineTo(tipX + 16f, r.bottom - 1f)
            close()
        }
        c.drawPath(tip, fill)

        label.color = Color.rgb(27, 48, 67)
        label.textSize = 13f
        val heading = (source.ifBlank { intentLabel(intent) }).uppercase()
        c.drawText(heading.take(30), r.left + 17f, r.top + 25f, label)

        body.color = Color.rgb(33, 44, 52)
        body.textSize = 15f
        val lines = wrap(message, body, r.width() - 34f, 3)
        lines.forEachIndexed { i, text -> c.drawText(text, r.left + 17f, r.top + 51f + i * 20f, body) }

        if (progress >= 0f) {
            val track = RectF(r.left + 17f, r.bottom - 17f, r.right - 17f, r.bottom - 10f)
            fill.color = Color.rgb(210, 217, 213)
            c.drawRoundRect(track, 4f, 4f, fill)
            fill.color = Color.rgb(44, 150, 105)
            c.drawRoundRect(RectF(track.left, track.top, track.left + track.width() * progress, track.bottom), 4f, 4f, fill)
        }
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val lines = ArrayList<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth || current.isEmpty()) current = candidate
            else {
                lines += current
                current = word
                if (lines.size == maxLines) break
            }
        }
        if (lines.size < maxLines && current.isNotEmpty()) lines += current
        if (lines.size == maxLines && words.joinToString(" ").length > lines.joinToString(" ").length)
            lines[lines.lastIndex] = lines.last().take(34).trimEnd() + "…"
        return lines
    }

    private fun drawStatus(c: Canvas, w: Int, h: Int, st: DeckState, now: Long) {
        val state = st.suhairPet
        val text = when {
            st.petTrackingEnabled && st.petFacePresent -> "SUHAIR • FACE TRACKING ON"
            state.intent == SuhairIntent.WORK && state.progress >= 0f ->
                "SUHAIR • WORKING ${(state.progress * 100).toInt()}%"
            state.intent == SuhairIntent.WORK -> "SUHAIR • WORKING"
            state.intent == SuhairIntent.WAIT -> "SUHAIR • WAITING"
            state.intent == SuhairIntent.REVIEW -> "SUHAIR • READY FOR REVIEW"
            else -> "SUHAIR • COMPANION ONLINE"
        }
        label.textSize = 12f
        label.color = Color.rgb(229, 239, 230)
        c.drawText(text, w / 2f - label.measureText(text) / 2f, h - 13f, label)
        @Suppress("UNUSED_VARIABLE") val keepClockDeterministic = now
    }

    private fun drawControls(c: Canvas, w: Int, st: DeckState, zone: (RectF, String) -> Unit) {
        val back = RectF(15f, 18f, 112f, 61f)
        val calibrate = RectF(w - 225f, 18f, w - 117f, 61f)
        val camera = RectF(w - 108f, 18f, w - 15f, 61f)
        chip(c, back, "‹ HOME", false)
        chip(c, calibrate, "CALIBRATE", false)
        chip(c, camera, if (st.petTrackingEnabled) "CAM ON" else "CAM OFF", st.petTrackingEnabled)
        zone(back, "pet_back")
        zone(calibrate, "pet_calibrate")
        zone(camera, "pet_tracking")
    }

    private fun chip(c: Canvas, r: RectF, text: String, active: Boolean) {
        fill.color = if (active) Color.argb(224, 30, 105, 78) else Color.argb(218, 7, 25, 49)
        c.drawRoundRect(r, 10f, 10f, fill)
        line.color = if (active) Color.rgb(111, 226, 170) else Color.rgb(100, 146, 184)
        line.strokeWidth = 2f
        c.drawRoundRect(r, 10f, 10f, line)
        label.color = Color.WHITE
        label.textSize = 11f
        c.drawText(text, r.centerX() - label.measureText(text) / 2f, r.centerY() + 4f, label)
    }

    private fun intentLabel(intent: SuhairIntent): String = when (intent) {
        SuhairIntent.GREET -> "HELLO"
        SuhairIntent.CELEBRATE -> "TASK COMPLETE"
        SuhairIntent.EXPLAIN -> "EXPLANATION"
        SuhairIntent.WARN -> "ATTENTION"
        SuhairIntent.WAIT -> "WAITING"
        SuhairIntent.WORK -> "WORKING"
        SuhairIntent.REVIEW -> "READY FOR REVIEW"
        SuhairIntent.FAIL -> "TASK ERROR"
        SuhairIntent.SHOW_NOTIFICATION -> "NOTIFICATION"
        else -> "MANZANILLA"
    }
}
