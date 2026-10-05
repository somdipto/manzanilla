package com.agentdeck.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A fully code-drawn pixel narrative:
 * intention -> courier -> tower -> Orange device -> CRT -> Manzanilla OS.
 * No generated still frames are used; every frame is drawn at 20 FPS.
 */
object StoryBootScene {

    const val TOTAL = 21_800L
    private const val CITY_END = 2_700L
    private const val TOWER_END = 5_600L
    private const val OFFICE_END = 8_700L
    const val PORTAL_END = 11_300L
    private const val IDENT_LENGTH = 2_700L
    private const val FEATURE_LENGTH = 2_200L

    private val NIGHT = Color.rgb(5, 13, 38)
    private val SKY_MID = Color.rgb(20, 34, 78)
    private val INDIGO = Color.rgb(45, 47, 105)
    private val MAGENTA = Color.rgb(178, 68, 118)
    private val SEA = Color.rgb(8, 35, 72)
    private val SEA_LIGHT = Color.rgb(25, 84, 126)
    private val CREAM = Color.rgb(255, 246, 218)
    private val GOLD = Color.rgb(248, 197, 68)
    private val AMBER = Color.rgb(238, 132, 52)
    private val ORANGE = Color.rgb(234, 105, 35)
    private val CORAL = Color.rgb(239, 98, 112)
    private val GREEN = Color.rgb(108, 162, 83)
    private val CYAN = Color.rgb(93, 209, 229)
    private val STONE = Color.rgb(43, 48, 65)
    private val STONE_L = Color.rgb(69, 71, 85)
    private val WOOD = Color.rgb(73, 44, 39)
    private val WOOD_L = Color.rgb(119, 72, 49)
    private val INK = Color.rgb(10, 14, 27)

    private val p = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private var font: Typeface? = null
    fun setFont(value: Typeface) { font = value }

    private fun textPaint(color: Int, size: Float) = Paint().apply {
        this.color = color
        textSize = size
        isAntiAlias = false
        font?.let { typeface = it }
    }

    private fun seg(t: Long, start: Long, end: Long) =
        ((t - start).toFloat() / (end - start)).coerceIn(0f, 1f)
    private fun ease(v: Float): Float = v.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    private fun out(v: Float): Float = v.coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) * (1f - it) }
    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
    private fun hash(i: Int) = ((i * 2654435761L) and 0xffff).toFloat() / 0xffff

    fun render(c: Canvas, w: Int, h: Int, t: Long): Boolean {
        when {
            t < CITY_END -> drawCoastalRun(c, w, h, t)
            t < TOWER_END -> drawTowerArrival(c, w, h, t)
            t < OFFICE_END -> drawOffice(c, w, h, t, false)
            t < PORTAL_END -> drawOffice(c, w, h, t, true)
            else -> drawLogo(c, w, h, t)
        }
        drawScanlines(c, w, h, t)
        return t >= TOTAL
    }

    // ---------------------------------------------------------------------
    // 1. The intention enters the city
    // ---------------------------------------------------------------------

    private fun drawCoastalRun(c: Canvas, w: Int, h: Int, t: Long) {
        val progress = ease(seg(t, 0L, CITY_END))
        drawNightSky(c, w, h, t)
        drawWater(c, w, h, t)

        // Sparse skyline: only a few readable silhouettes and one destination.
        val parallax = progress * 64f
        val buildings = listOf(
            floatArrayOf(40f, 235f, 72f, 100f),
            floatArrayOf(132f, 214f, 78f, 121f),
            floatArrayOf(244f, 248f, 58f, 87f),
            floatArrayOf(344f, 202f, 82f, 133f),
            floatArrayOf(468f, 236f, 66f, 99f)
        )
        for ((i, b) in buildings.withIndex())
            drawBuilding(c, b[0] - parallax * (0.22f + i * 0.025f), b[1], b[2], b[3], i, t)

        // The Art Deco tower stays visually isolated on the right.
        drawTower(c, w * 0.79f - progress * 42f, h * 0.70f, 126f + progress * 20f,
            212f + progress * 22f, t, 0f)

        drawBoardwalk(c, w, h, progress)
        val runX = lerp(-36f, w * 0.57f, out(seg(t, 150L, 2450L)))
        val runY = h * 0.79f + sin(t / 115.0).toFloat() * 2f
        drawCourier(c, runX, runY, 4.4f, t, true)
        drawSignalTrail(c, runX - 22f, runY - 42f, t, 12)

        // A single star points toward the tower: destination, not decoration.
        val sx = w * 0.74f
        val sy = h * 0.18f
        p.color = GOLD; p.alpha = (150 + 90 * abs(sin(t / 320.0))).toInt()
        c.drawRect(sx - 2, sy - 8, sx + 2, sy + 8, p)
        c.drawRect(sx - 8, sy - 2, sx + 8, sy + 2, p)
        p.alpha = 255
    }

    private fun drawNightSky(c: Canvas, w: Int, h: Int, t: Long) {
        val bands = 18
        for (i in 0 until bands) {
            val f = i / (bands - 1f)
            p.color = blend(if (f < 0.62f) NIGHT else SKY_MID,
                if (f < 0.62f) SKY_MID else MAGENTA,
                if (f < 0.62f) f / 0.62f else (f - 0.62f) / 0.38f)
            c.drawRect(0f, i * h * 0.68f / bands, w.toFloat(), (i + 1.2f) * h * 0.68f / bands, p)
        }
        for (i in 0 until 22) {
            val twinkle = (90 + 100 * abs(sin(t / 520.0 + i))).toInt()
            p.color = if (i % 4 == 0) CYAN else CREAM; p.alpha = twinkle
            val x = hash(i * 11) * w
            val y = hash(i * 17 + 4) * h * 0.46f
            val size = if (i % 7 == 0) 3f else 2f
            c.drawRect(x, y, x + size, y + size, p)
        }
        p.alpha = 255
    }

    private fun drawWater(c: Canvas, w: Int, h: Int, t: Long) {
        val top = h * 0.63f
        p.color = SEA; c.drawRect(0f, top, w.toFloat(), h.toFloat(), p)
        for (row in 0 until 12) {
            val y = top + row * 8f
            val offset = ((t / (22L + row * 3L)) % 34L).toFloat()
            for (i in 0 until w / 46 + 2) {
                val x = i * 46f - offset
                p.color = if ((i + row) % 5 == 0) blend(SEA_LIGHT, GOLD, 0.28f) else SEA_LIGHT
                p.alpha = 70 + row * 7
                c.drawRect(x, y, x + 16f + row, y + 2f, p)
            }
        }
        p.alpha = 255
    }

    private fun drawBoardwalk(c: Canvas, w: Int, h: Int, progress: Float) {
        val y = h * 0.77f
        p.color = STONE; c.drawRect(0f, y, w.toFloat(), h.toFloat(), p)
        p.color = STONE_L; c.drawRect(0f, y, w.toFloat(), y + 6f, p)
        p.color = Color.rgb(32, 36, 50)
        var x = -(progress * 48f) % 48f
        while (x < w) { c.drawRect(x, y + 8, x + 3, h.toFloat(), p); x += 48f }
    }

    // ---------------------------------------------------------------------
    // 2. The destination accepts the message
    // ---------------------------------------------------------------------

    private fun drawTowerArrival(c: Canvas, w: Int, h: Int, t: Long) {
        val local = t - CITY_END
        val approach = ease(seg(local, 0L, 2100L))
        drawNightSky(c, w, h, t)
        p.color = NIGHT; c.drawRect(0f, h * 0.68f, w.toFloat(), h.toFloat(), p)

        val tw = lerp(176f, 430f, approach)
        val th = lerp(292f, 620f, approach)
        val baseY = lerp(h * 0.93f, h * 1.18f, approach)
        drawTower(c, w / 2f, baseY, tw, th, t, seg(local, 900L, 2300L))

        val courierFade = (1f - seg(local, 1150L, 1750L)).coerceIn(0f, 1f)
        if (courierFade > 0f) {
            p.alpha = (courierFade * 255).toInt()
            val x = lerp(w * 0.12f, w * 0.46f, out(seg(local, 0L, 1500L)))
            drawCourier(c, x, h * 0.84f, 4.8f, t, true)
            p.alpha = 255
        }

        // Last beat: the warm top-floor window expands into the office.
        val enter = ease(seg(local, 2380L, TOWER_END - CITY_END))
        if (enter > 0f) {
            val cx = w / 2f
            val cy = h * 0.25f
            val ww = lerp(24f, w * 1.3f, enter)
            val hh = lerp(36f, h * 1.3f, enter)
            p.color = blend(AMBER, WOOD, enter * 0.75f)
            c.drawRect(cx - ww / 2, cy - hh / 2, cx + ww / 2, cy + hh / 2, p)
        }
    }

    private fun drawTower(c: Canvas, cx: Float, baseY: Float, width: Float, height: Float,
                          t: Long, elevator: Float) {
        val tiers = 7
        for (tier in 0 until tiers) {
            val f0 = tier / tiers.toFloat()
            val f1 = (tier + 1) / tiers.toFloat()
            val tierW = width * (1f - f0 * 0.63f)
            val nextW = width * (1f - f1 * 0.63f)
            val yBottom = baseY - height * f0
            val yTop = baseY - height * f1
            p.color = if (tier % 2 == 0) STONE else STONE_L
            c.drawRect(cx - tierW / 2, yTop, cx + tierW / 2, yBottom, p)
            p.color = Color.rgb(91, 74, 70)
            c.drawRect(cx - nextW / 2, yTop, cx + nextW / 2, yTop + 4f, p)
        }
        // Crown and antenna
        p.color = STONE_L
        c.drawRect(cx - width * 0.10f, baseY - height - 22f, cx + width * 0.10f, baseY - height, p)
        p.color = GOLD
        c.drawRect(cx - 2f, baseY - height - 40f, cx + 2f, baseY - height - 22f, p)

        val cols = 5
        val rows = 8
        for (r in 0 until rows) for (col in 0 until cols) {
            val x = cx - width * 0.30f + col * width * 0.15f
            val y = baseY - 35f - r * height * 0.095f
            val elevatorRow = (elevator * rows).toInt().coerceIn(0, rows - 1)
            val lit = r == elevatorRow && elevator > 0f || (r + col) % 5 == 0
            p.color = if (lit) GOLD else Color.rgb(20, 27, 43)
            p.alpha = if (lit) 230 else 190
            c.drawRect(x - 5f, y - 8f, x + 5f, y + 8f, p)
        }
        p.alpha = 255
        // Door
        p.color = INK; c.drawRect(cx - width * 0.11f, baseY - 48f, cx + width * 0.11f, baseY, p)
        p.color = AMBER; c.drawRect(cx - 2f, baseY - 44f, cx + 2f, baseY - 5f, p)
    }

    // ---------------------------------------------------------------------
    // 3. The Orange device passes the message to the CRT
    // ---------------------------------------------------------------------

    private fun drawOffice(c: Canvas, w: Int, h: Int, t: Long, portalAct: Boolean) {
        val local = if (!portalAct) t - TOWER_END else t - OFFICE_END
        val officeIn = out(seg(t, TOWER_END, TOWER_END + 450L))
        drawOfficeRoom(c, w, h, t, officeIn)

        val courierX = if (!portalAct)
            lerp(86f, w * 0.43f, out(seg(local, 250L, 2250L))) else w * 0.43f
        drawCourier(c, courierX, h * 0.72f, 5.2f, t, !portalAct && local < 2200L)

        val deskY = h * 0.67f
        drawOrangeDevice(c, w * 0.59f, deskY - 62f, 1f, t, portalAct)
        val crt = RectF(w * 0.71f, deskY - 142f, w * 0.91f, deskY - 12f)
        drawCrt(c, crt, t, portalAct)

        if (!portalAct) {
            // The courier releases the chamomile intent into the device.
            val place = ease(seg(local, 2080L, 2950L))
            if (place > 0f) {
                val sx = courierX + 25f
                val sy = h * 0.72f - 48f
                val dx = w * 0.59f + 34f
                val dy = deskY - 42f
                drawSignalSeed(c, lerp(sx, dx, place), lerp(sy, dy, place), 1.2f)
            }
        } else {
            drawConnection(c, w, h, crt, local)
            val zoom = ease(seg(local, 1750L, PORTAL_END - OFFICE_END))
            if (zoom > 0f) {
                c.save()
                val scale = lerp(1f, 6.6f, zoom)
                c.scale(scale, scale, crt.centerX(), crt.centerY())
                // redraw only the CRT at the enlarged scale; it becomes the frame.
                drawCrt(c, crt, t, true)
                c.restore()
                p.color = NIGHT; p.alpha = (zoom * 80).toInt()
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.alpha = 255
            }
        }
    }

    private fun drawOfficeRoom(c: Canvas, w: Int, h: Int, t: Long, amount: Float) {
        p.color = blend(AMBER, INK, amount); c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        // One window, one chair, one desk: intentionally sparse.
        val window = RectF(50f, 42f, w * 0.39f, h * 0.56f)
        p.color = NIGHT; c.drawRect(window, p)
        p.color = STONE_L
        c.drawRect(window.left, window.top, window.right, window.top + 8f, p)
        c.drawRect(window.left, window.bottom - 8f, window.right, window.bottom, p)
        c.drawRect(window.centerX() - 4f, window.top, window.centerX() + 4f, window.bottom, p)
        // distant coast lights
        for (i in 0 until 9) {
            p.color = if (i % 3 == 0) GOLD else CYAN; p.alpha = 130
            val x = window.left + 15f + i * 28f
            val y = window.bottom - 35f - (i % 3) * 9f
            c.drawRect(x, y, x + 4f, y + 3f, p)
        }
        p.alpha = 255
        p.color = WOOD
        c.drawRect(0f, h * 0.68f, w.toFloat(), h.toFloat(), p)
        p.color = WOOD_L
        var fy = h * 0.70f
        while (fy < h) { c.drawRect(0f, fy, w.toFloat(), fy + 3f, p); fy += 28f }

        val deskY = h * 0.67f
        p.color = Color.rgb(46, 28, 29)
        c.drawRect(w * 0.48f, deskY - 8f, w * 0.95f, deskY + 22f, p)
        p.color = WOOD_L; c.drawRect(w * 0.48f, deskY - 8f, w * 0.95f, deskY - 2f, p)
        c.drawRect(w * 0.52f, deskY + 22f, w * 0.56f, h.toFloat(), p)
        c.drawRect(w * 0.88f, deskY + 22f, w * 0.92f, h.toFloat(), p)
        // brass lamp
        p.color = GOLD
        c.drawRect(w * 0.50f, deskY - 74f, w * 0.505f, deskY - 8f, p)
        c.drawRect(w * 0.475f, deskY - 78f, w * 0.53f, deskY - 70f, p)
        p.color = AMBER; p.alpha = (55 + 20 * abs(sin(t / 600.0))).toInt()
        c.drawRect(w * 0.455f, deskY - 68f, w * 0.55f, deskY - 8f, p); p.alpha = 255
    }

    private fun drawOrangeDevice(c: Canvas, x: Float, y: Float, scale: Float,
                                 t: Long, active: Boolean) {
        val s = scale
        p.color = Color.rgb(224, 86, 25)
        c.drawRect(x, y, x + 88f * s, y + 56f * s, p)
        p.color = INK
        c.drawRect(x + 7f * s, y + 6f * s, x + 58f * s, y + 38f * s, p)
        p.color = if (active) CYAN else Color.rgb(28, 48, 58)
        c.drawRect(x + 11f * s, y + 10f * s, x + 54f * s, y + 34f * s, p)
        // phone/key controller pad
        for (r in 0 until 3) for (col in 0 until 2) {
            p.color = if (active && (r + col + t / 160L) % 3L == 0L) GOLD else CREAM
            val kx = x + (66f + col * 10f) * s
            val ky = y + (11f + r * 12f) * s
            c.drawRect(kx, ky, kx + 6f * s, ky + 6f * s, p)
        }
        p.color = STONE
        c.drawRect(x + 4f, y + 56f, x + 84f, y + 61f, p)
    }

    private fun drawCrt(c: Canvas, r: RectF, t: Long, active: Boolean) {
        p.color = STONE_L; c.drawRect(r, p)
        p.color = STONE
        c.drawRect(r.left + 8, r.top + 8, r.right - 8, r.bottom - 22, p)
        val screen = RectF(r.left + 14, r.top + 14, r.right - 14, r.bottom - 29)
        p.color = NIGHT; c.drawRect(screen, p)
        if (active) {
            val glow = abs(sin(t / 270.0)).toFloat()
            p.color = blend(CYAN, CREAM, glow * 0.35f); p.alpha = 190
            c.drawRect(screen.left + 6, screen.centerY() - 2,
                screen.right - 6, screen.centerY() + 2, p)
            p.alpha = 255
        }
        p.color = GOLD
        c.drawRect(r.right - 20, r.bottom - 15, r.right - 14, r.bottom - 9, p)
    }

    private fun drawConnection(c: Canvas, w: Int, h: Int, crt: RectF, local: Long) {
        val startX = w * 0.59f + 44f
        val startY = h * 0.67f - 18f
        val endX = crt.left + 18f
        val endY = crt.centerY()
        p.color = Color.rgb(36, 42, 56)
        c.drawRect(startX, startY, endX, startY + 4f, p)
        c.drawRect(endX - 4f, startY, endX, endY, p)
        val signal = ease(seg(local, 250L, 1450L))
        if (signal > 0f) {
            val path = signal * 2f
            val x: Float
            val y: Float
            if (path < 1f) { x = lerp(startX, endX, path); y = startY }
            else { x = endX; y = lerp(startY, endY, path - 1f) }
            drawSignalSeed(c, x, y, 1.25f)
            drawSignalTrail(c, x - 8f, y, local, 7)
        }
    }

    // ---------------------------------------------------------------------
    // 4. The screen becomes the logo
    // ---------------------------------------------------------------------

    private fun drawLogo(c: Canvas, w: Int, h: Int, t: Long) {
        val local = t - PORTAL_END
        if (local >= IDENT_LENGTH) {
            drawFeatureSequence(c, w, h, local - IDENT_LENGTH, t)
            return
        }
        p.color = Color.BLACK; c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)

        // CRT cathode-ray opening: line first, then the phosphor image opens.
        val beam = out(seg(local, 0L, 260L))
        val open = out(seg(local, 170L, 520L))
        p.color = CREAM; p.alpha = (220 * (1f - open * 0.7f)).toInt()
        c.drawRect(w / 2f - w * 0.46f * beam, h / 2f - 2f,
            w / 2f + w * 0.46f * beam, h / 2f + 2f, p)
        p.color = CYAN; p.alpha = (65 * beam).toInt()
        c.drawRect(w / 2f - w * 0.48f * beam, h / 2f - 10f,
            w / 2f + w * 0.48f * beam, h / 2f + 10f, p)
        p.alpha = 255

        val imageTop = h / 2f - h / 2f * open
        val imageBottom = h / 2f + h / 2f * open
        c.save()
        c.clipRect(0f, imageTop, w.toFloat(), imageBottom)
        p.color = NIGHT; c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        for (i in 0 until 18) {
            p.color = listOf(CYAN, GOLD, CORAL, GREEN)[i % 4]
            p.alpha = (80 + 100 * abs(sin(t / 380.0 + i))).toInt()
            val x = hash(i * 7 + 3) * w
            val y = hash(i * 13 + 9) * h
            c.drawRect(x, y, x + 3f, y + 3f, p)
        }
        p.alpha = 255

        val build = ease(seg(local, 0L, 1250L))
        val slide = ease(seg(local, 850L, 1550L))
        val logoX = lerp(w / 2f, w * 0.245f, slide)
        val logoY = lerp(h * 0.40f, h * 0.46f, slide)

        // Layered block glow and two chromatic VHS ghosts trail the slide.
        val glow = build * (0.72f + 0.28f * abs(sin(t / 190.0)).toFloat())
        for (i in 5 downTo 1) {
            p.color = if (i % 2 == 0) CYAN else MAGENTA
            p.alpha = (glow * (8 + (6 - i) * 3)).toInt()
            val rw = 42f + i * 12f
            val rh = 48f + i * 10f
            c.drawRect(logoX - rw, logoY - rh, logoX + rw, logoY + rh, p)
        }
        p.alpha = 255
        if (build > 0.18f && slide > 0f) {
            drawLogoGhost(c, logoX + (1f - slide) * 13f + 4f, logoY, 5.8f,
                CYAN, (slide * 45).toInt())
            drawLogoGhost(c, logoX + (1f - slide) * 20f - 5f, logoY + 2f, 5.8f,
                MAGENTA, (slide * 32).toInt())
        }
        drawChamomile(c, logoX, logoY, 5.8f, build, t)

        val titleP = ease(seg(local, 1080L, 2130L))
        if (titleP > 0f) drawTitle(c, w, h, titleP)
        val ready = ease(seg(local, 2020L, 2460L))
        if (ready > 0f) {
            val sub = "COMMUNICATE  /  CONTROL  /  CREATE"
            val tp = textPaint(blend(NIGHT, GREEN, ready), 10f)
            c.drawText(sub, w * 0.405f, h * 0.64f, tp)
        }
        c.restore()
    }

    /** Three readable, continuously morphing product moments after the VHS ident. */
    private fun drawFeatureSequence(c: Canvas, w: Int, h: Int, local: Long, t: Long) {
        p.color = NIGHT; c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        for (i in 0 until 24) {
            p.color = listOf(CYAN, GOLD, CORAL, GREEN)[i % 4]
            p.alpha = 70 + (90 * abs(sin(t / 420.0 + i))).toInt()
            val x = hash(i * 11 + 4) * w
            val y = hash(i * 17 + 8) * h
            c.drawRect(x, y, x + 3f, y + 3f, p)
        }
        p.alpha = 255

        val featureCount = 3
        val featureSpan = FEATURE_LENGTH * featureCount
        if (local >= featureSpan) {
            val settle = ease(seg(local, featureSpan, featureSpan + 950L))
            drawChamomile(c, w * 0.36f, h * 0.46f, 4.4f, settle, t)
            val tp = textPaint(blend(NIGHT, CREAM, settle), 25f)
            val title = "MANZANILLA OS"
            c.drawText(title, w * 0.46f, h * 0.47f, tp)
            val rp = textPaint(blend(NIGHT, GREEN, settle), 9f)
            c.drawText("READY TO COMMUNICATE", w * 0.46f, h * 0.56f, rp)
            return
        }

        val index = (local / FEATURE_LENGTH).toInt().coerceIn(0, featureCount - 1)
        val within = local % FEATURE_LENGTH
        val enter = ease(seg(within, 0L, 300L))
        val leave = 1f - ease(seg(within, FEATURE_LENGTH - 300L, FEATURE_LENGTH))
        val visibility = minOf(enter, leave)
        val morph = ease(seg(within, 0L, FEATURE_LENGTH))
        val cx = w / 2f
        val cy = h * 0.43f
        val accent = listOf(GOLD, MAGENTA, CYAN)[index]

        // Motion-graphic orbit passes its energy into the next icon.
        for (i in 0 until 18) {
            val a = i * Math.PI * 2 / 18 + morph * 1.6
            val radius = lerp(112f, 70f, morph)
            val x = cx + cos(a).toFloat() * radius
            val y = cy + sin(a).toFloat() * radius * 0.56f
            p.color = listOf(CYAN, GOLD, CORAL, GREEN)[(i + index) % 4]
            p.alpha = (visibility * (80 + (i % 4) * 30)).toInt()
            c.drawRect(x - 2f, y - 2f, x + 2f, y + 2f, p)
        }
        p.alpha = 255

        when (index) {
            0 -> drawCommunicatorIcon(c, cx, cy, visibility, morph)
            1 -> drawVoiceIcon(c, cx, cy, visibility, morph)
            else -> drawControllerIcon(c, cx, cy, visibility, morph)
        }

        val titles = arrayOf("AI COMMUNICATOR", "VOICE CONTROL", "PC CONTROLLER")
        val details = arrayOf(
            "CHATGPT  /  CODEX  /  CLAUDE",
            "TALK TO YOUR AI",
            "APPS  /  SHORTCUTS  /  WORKFLOWS"
        )
        val titleP = textPaint(blend(NIGHT, accent, visibility), 29f)
        val title = titles[index]
        c.drawText(title, cx - titleP.measureText(title) / 2f, h * 0.71f, titleP)
        val detailP = textPaint(blend(NIGHT, CREAM, visibility), 11f)
        val detail = details[index]
        c.drawText(detail, cx - detailP.measureText(detail) / 2f, h * 0.79f, detailP)
        val countP = textPaint(blend(NIGHT, GREEN, visibility), 8f)
        val count = "0${index + 1}  /  03"
        c.drawText(count, cx - countP.measureText(count) / 2f, h * 0.87f, countP)
    }

    private fun drawCommunicatorIcon(c: Canvas, cx: Float, cy: Float,
                                     alpha: Float, morph: Float) {
        p.color = blend(NIGHT, GOLD, alpha)
        val spread = lerp(18f, 42f, morph)
        for (i in -1..1) {
            val x = cx + i * spread
            c.drawRect(x - 22f, cy - 22f, x + 22f, cy + 18f, p)
            c.drawRect(x - 15f, cy + 18f, x - 5f, cy + 27f, p)
            p.color = blend(NIGHT, INK, alpha)
            for (d in -1..1) c.drawRect(x + d * 8f - 2f, cy - 4f, x + d * 8f + 2f, cy, p)
            p.color = blend(NIGHT, GOLD, alpha)
        }
    }

    private fun drawVoiceIcon(c: Canvas, cx: Float, cy: Float,
                              alpha: Float, morph: Float) {
        p.color = blend(NIGHT, MAGENTA, alpha)
        c.drawRect(cx - 21f, cy - 45f, cx + 21f, cy + 20f, p)
        p.color = blend(NIGHT, CREAM, alpha)
        c.drawRect(cx - 7f, cy - 32f, cx + 7f, cy + 8f, p)
        c.drawRect(cx - 34f, cy + 13f, cx - 27f, cy + 30f, p)
        c.drawRect(cx + 27f, cy + 13f, cx + 34f, cy + 30f, p)
        c.drawRect(cx - 29f, cy + 28f, cx + 29f, cy + 35f, p)
        c.drawRect(cx - 4f, cy + 35f, cx + 4f, cy + 52f, p)
        for (i in 0 until 7) {
            val x = cx - 105f + i * 35f
            val amp = (12f + 20f * abs(sin(morph * 7.0 + i))).toFloat()
            p.color = blend(NIGHT, if (i < 3) CORAL else CYAN, alpha)
            c.drawRect(x - 3f, cy - amp, x + 3f, cy + amp, p)
        }
    }

    private fun drawControllerIcon(c: Canvas, cx: Float, cy: Float,
                                   alpha: Float, morph: Float) {
        val size = lerp(20f, 28f, morph)
        for (row in 0 until 2) for (col in 0 until 3) {
            val x = cx + (col - 1) * (size + 12f)
            val y = cy + (row - 0.5f) * (size + 12f)
            p.color = blend(NIGHT, listOf(CYAN, GOLD, CORAL, GREEN, MAGENTA, CREAM)[row * 3 + col], alpha)
            c.drawRect(x - size / 2f, y - size / 2f, x + size / 2f, y + size / 2f, p)
            p.color = blend(NIGHT, INK, alpha)
            c.drawRect(x - 3f, y - 3f, x + 3f, y + 3f, p)
        }
    }

    private fun drawTitle(c: Canvas, w: Int, h: Int, amount: Float) {
        val title = "MANZANILLA OS"
        val tp = textPaint(CREAM, 29f)
        val x = w * 0.405f
        val y = h * 0.48f
        val slices = 7
        for (i in 0 until slices) {
            val sp = out(((amount - i * 0.07f) / 0.55f).coerceIn(0f, 1f))
            if (sp <= 0f) continue
            val from = w * (0.52f + i * 0.025f)
            c.save()
            c.clipRect(x + tp.measureText(title) * i / slices, y - 40,
                x + tp.measureText(title) * (i + 1) / slices, y + 10)
            c.drawText(title, x + from * (1f - sp), y, tp)
            c.restore()
        }
        // Analogue scan bars lock the wordmark to the flower.
        val line = out(((amount - 0.48f) / 0.38f).coerceIn(0f, 1f))
        if (line > 0f) {
            p.color = GOLD; p.alpha = (180 * line).toInt()
            c.drawRect(x, y - 54f, x + tp.measureText(title) * line, y - 51f, p)
            p.color = GREEN
            c.drawRect(x, y + 18f, x + tp.measureText(title) * line, y + 21f, p)
            p.alpha = 255
        }
    }

    private fun drawLogoGhost(c: Canvas, cx: Float, cy: Float, s: Float,
                              color: Int, alpha: Int) {
        p.color = color; p.alpha = alpha.coerceIn(0, 255)
        c.drawRect(cx - 17f, cy - 17f, cx + 17f, cy + 17f, p)
        for (i in 0 until 8) {
            val a = i * Math.PI / 4 - Math.PI / 2
            val px = cx + cos(a).toFloat() * 34f * s / 3f
            val py = cy + sin(a).toFloat() * 34f * s / 3f
            c.drawRect(px - 13f, py - 17f, px + 13f, py + 17f, p)
        }
        p.alpha = 255
    }

    private fun drawChamomile(c: Canvas, cx: Float, cy: Float, s: Float,
                              amount: Float, t: Long) {
        val center = out((amount / 0.28f).coerceIn(0f, 1f))
        val petals = ((amount - 0.20f) / 0.55f).coerceIn(0f, 1f)
        val stem = out(((amount - 0.65f) / 0.35f).coerceIn(0f, 1f))
        // converging intention pixels
        if (amount < 0.5f) for (i in 0 until 14) {
            val a = hash(i) * 6.28f
            val d = (1f - amount * 2f).coerceAtLeast(0f) * 100f
            p.color = listOf(GOLD, CYAN, CORAL, GREEN)[i % 4]
            c.drawRect(cx + cos(a.toDouble()).toFloat() * d,
                cy + sin(a.toDouble()).toFloat() * d,
                cx + cos(a.toDouble()).toFloat() * d + s,
                cy + sin(a.toDouble()).toFloat() * d + s, p)
        }
        if (center > 0f) {
            p.color = AMBER
            c.drawRect(cx - 15f * s * center / 3f, cy - 15f * s * center / 3f,
                cx + 15f * s * center / 3f, cy + 15f * s * center / 3f, p)
            p.color = GOLD
            c.drawRect(cx - 11f * s * center / 3f, cy - 11f * s * center / 3f,
                cx + 11f * s * center / 3f, cy + 11f * s * center / 3f, p)
        }
        for (i in 0 until 8) {
            val pop = out(((petals - i / 10f) / 0.25f).coerceIn(0f, 1f))
            if (pop <= 0f) continue
            val a = i * Math.PI / 4 - Math.PI / 2
            val px = cx + cos(a).toFloat() * 34f * s / 3f
            val py = cy + sin(a).toFloat() * 34f * s / 3f
            val rw = 11f * s / 3f * pop
            val rh = 14f * s / 3f * pop
            p.color = CREAM; c.drawRect(px - rw, py - rh, px + rw, py + rh, p)
        }
        if (stem > 0f) {
            p.color = GREEN
            c.drawRect(cx - 3f, cy + 34f, cx + 3f, cy + 34f + 50f * stem, p)
            c.drawRect(cx - 35f * stem, cy + 60f, cx, cy + 70f, p)
            c.drawRect(cx, cy + 67f, cx + 35f * stem, cy + 77f, p)
        }
        // living control core
        if (amount > 0.72f) {
            val pulse = ((t / 140L) % 5L).toInt()
            val d = 7f
            val dirs = listOf(0f to -d, d to 0f, 0f to d, -d to 0f)
            for ((i, dir) in dirs.withIndex()) {
                p.color = if (i == pulse) CREAM else INK
                c.drawRect(cx + dir.first - 3f, cy + dir.second - 3f,
                    cx + dir.first + 3f, cy + dir.second + 3f, p)
            }
            p.color = INK; c.drawRect(cx - 3f, cy - 3f, cx + 3f, cy + 3f, p)
        }
    }

    // ---------------------------------------------------------------------
    // Shared story sprites
    // ---------------------------------------------------------------------

    private fun drawCourier(c: Canvas, x: Float, groundY: Float, s: Float,
                            t: Long, carrying: Boolean) {
        val run = ((t / 110L) % 4L).toInt()
        val bob = if (run % 2 == 0) 0f else -s
        val alpha = p.alpha
        // shadow
        p.color = Color.argb((alpha * 0.45f).toInt(), 0, 0, 0)
        c.drawRect(x - 3f * s, groundY - s, x + 4f * s, groundY + s, p)
        // legs
        p.color = INK; p.alpha = alpha
        val left = if (run < 2) -2.2f * s else -0.4f * s
        val right = if (run < 2) 0.5f * s else 2.2f * s
        c.drawRect(x + left, groundY - 8f * s + bob, x + left + 1.5f * s, groundY + bob, p)
        c.drawRect(x + right, groundY - 8f * s + bob, x + right + 1.5f * s, groundY + bob, p)
        // coat and head
        p.color = ORANGE
        c.drawRect(x - 3f * s, groundY - 17f * s + bob, x + 4f * s, groundY - 8f * s + bob, p)
        p.color = CREAM
        c.drawRect(x - 2f * s, groundY - 23f * s + bob, x + 3f * s, groundY - 18f * s + bob, p)
        p.color = NIGHT
        c.drawRect(x - 2f * s, groundY - 24f * s + bob, x + 3f * s, groundY - 22f * s + bob, p)
        // arm and carried intention
        p.color = ORANGE
        c.drawRect(x + 3f * s, groundY - 16f * s + bob,
            x + 7f * s, groundY - 14f * s + bob, p)
        if (carrying) drawSignalSeed(c, x + 8f * s, groundY - 16f * s + bob, 0.75f)
        p.alpha = alpha
    }

    private fun drawSignalSeed(c: Canvas, x: Float, y: Float, scale: Float) {
        val s = 4f * scale
        p.color = GOLD; p.alpha = 80
        c.drawRect(x - s * 2, y - s * 2, x + s * 2, y + s * 2, p)
        p.alpha = 255; p.color = CREAM
        c.drawRect(x - s, y - s * 2, x + s, y + s * 2, p)
        c.drawRect(x - s * 2, y - s, x + s * 2, y + s, p)
        p.color = GOLD; c.drawRect(x - s / 2, y - s / 2, x + s / 2, y + s / 2, p)
    }

    private fun drawSignalTrail(c: Canvas, x: Float, y: Float, t: Long, count: Int) {
        for (i in 0 until count) {
            val life = ((t / 45L + i * 17L) % 100L) / 100f
            p.color = listOf(GOLD, CYAN, CORAL, GREEN)[i % 4]
            p.alpha = ((1f - life) * 130).toInt()
            c.drawRect(x - i * 8f - life * 10f, y + sin(i + t / 220.0).toFloat() * 5f,
                x - i * 8f - life * 10f + 3f, y + sin(i + t / 220.0).toFloat() * 5f + 3f, p)
        }
        p.alpha = 255
    }

    private fun drawBuilding(c: Canvas, x: Float, baseY: Float, width: Float,
                             height: Float, seed: Int, t: Long) {
        p.color = if (seed % 2 == 0) Color.rgb(25, 30, 52) else Color.rgb(32, 33, 58)
        c.drawRect(x, baseY - height, x + width, baseY, p)
        for (row in 0 until (height / 22).toInt()) for (col in 0 until (width / 18).toInt()) {
            val lit = (row * 7 + col * 3 + seed) % 5 == 0
            p.color = if (lit) AMBER else Color.rgb(10, 20, 39)
            p.alpha = if (lit && (t / 700L + seed) % 4L == 0L) 130 else 210
            val wx = x + 8 + col * 18f
            val wy = baseY - height + 10 + row * 22f
            c.drawRect(wx, wy, wx + 6, wy + 9, p)
        }
        p.alpha = 255
    }

    private fun drawScanlines(c: Canvas, w: Int, h: Int, t: Long) {
        p.color = Color.BLACK; p.alpha = 15
        var y = 0f
        while (y < h) { c.drawRect(0f, y, w.toFloat(), y + 1f, p); y += 4f }
        // one very subtle CRT tracking line
        val track = ((t * 0.04f) % (h + 70f)) - 35f
        p.color = CREAM; p.alpha = 8
        c.drawRect(0f, track, w.toFloat(), track + 2f, p)
        p.alpha = 255
    }

    private fun blend(a: Int, b: Int, f0: Float): Int {
        val f = f0.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
        )
    }
}
