package com.agentdeck.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * MANZANILLA OS cinematic boot — one continuous scripted pixel film.
 *
 *   TRAVEL  →  DISCOVERY  →  APPROACH  →  ACTIVATION  →  ENTER THE CRT
 *           →  UNDERSTAND MANZANILLA  →  START USING THE OS
 *
 * Pure function of time: render(t) draws the exact frame for t ms.
 * Returns true when the sequence is finished (caller morphs into HOME).
 */
object BootScene {

    // ---- timeline (ms) ----
    private const val SEA_END = 1500L
    private const val APPROACH_END = 2800L
    private const val ARRIVE_END = 3400L
    private const val ORBIT_END = 5600L
    private const val FRONT_END = 5900L
    private const val ZOOM_END = 6700L
    private const val REVEAL_END = 7700L
    private const val FEAT_A_END = 8350L
    private const val FEAT_B_END = 9000L
    private const val FEAT_C_END = 9650L
    const val TOTAL = 10050L

    // ---- palette ----
    private val INDIGO = Color.rgb(38, 34, 98)
    private val DUSK = Color.rgb(96, 60, 130)
    private val ROSE = Color.rgb(212, 100, 142)
    private val AMBER = Color.rgb(246, 160, 78)
    private val GOLD = Color.rgb(250, 200, 90)
    private val SEA_DEEP = Color.rgb(16, 38, 84)
    private val SEA_MID = Color.rgb(24, 58, 116)
    private val CREAM = Color.rgb(255, 247, 230)
    private val PETAL = Color.rgb(252, 246, 232)
    private val PETAL_SH = Color.rgb(232, 220, 196)
    private val CENTER_Y = Color.rgb(245, 200, 66)
    private val CENTER_D = Color.rgb(222, 168, 40)
    private val NAVY = Color.rgb(15, 30, 58)
    private val STEM = Color.rgb(111, 143, 90)
    private val LEAF = Color.rgb(140, 172, 110)
    private val MTN_FAR = Color.rgb(58, 48, 108)
    private val MTN_NEAR = Color.rgb(44, 66, 92)
    private val GRASS = Color.rgb(74, 120, 74)
    private val GRASS_D = Color.rgb(56, 96, 58)
    private val WOOD = Color.rgb(120, 84, 48)
    private val CRT_BODY = Color.rgb(70, 62, 58)
    private val CRT_BODY_L = Color.rgb(96, 86, 80)
    private val SCREEN_BG = Color.rgb(10, 12, 24)

    private val p = Paint().apply { isAntiAlias = false }
    private val pt = Paint().apply { isAntiAlias = false }

    private fun ease(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
    private fun easeOut(x: Float) = x.coerceIn(0f, 1f).let { 1 - (1 - it) * (1 - it) * (1 - it) }
    private fun seg(t: Long, a: Long, b: Long) = ((t - a).toFloat() / (b - a)).coerceIn(0f, 1f)
    private fun hash(i: Int) = ((i * 2654435761L) and 0xFFFF).toFloat() / 0xFFFF
    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
    private fun lerpC(c1: Int, c2: Int, f0: Float): Int {
        val f = f0.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * f).toInt(),
            (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * f).toInt(),
            (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * f).toInt())
    }

    private var font: android.graphics.Typeface? = null
    fun setFont(f: android.graphics.Typeface) { font = f }
    private fun txt(c: Int, size: Float): Paint = Paint().apply {
        color = c; textSize = size; isAntiAlias = false
        font?.let { typeface = it }
    }

    /** draws the frame for time t; true = film over, morph to HOME */
    fun render(c: Canvas, w: Int, h: Int, t: Long): Boolean {
        when {
            t < ARRIVE_END -> {
                drawSky(c, w, h, t)
                drawWorld(c, w, h, t)
                if (t >= APPROACH_END)
                    drawPlateau(c, w, h, seg(t, APPROACH_END, ARRIVE_END), t)
            }
            t < ZOOM_END -> {
                drawSky(c, w, h, t)
                drawPlateau(c, w, h, 1f, t)
                drawCrtStage(c, w, h, t)
            }
            else -> drawInside(c, w, h, t)
        }
        return t >= TOTAL
    }

    // =========================================================================
    // ACT I — the sea, the flight, the mountains
    // =========================================================================

    private fun horizonY(w: Int, h: Int, t: Long): Float {
        val rise = ease(seg(t, SEA_END, APPROACH_END))
        return h * (0.52f + 0.08f * rise)
    }

    private fun drawSky(c: Canvas, w: Int, h: Int, t: Long) {
        val hy = if (t < ARRIVE_END) horizonY(w, h, t) else h * 0.60f
        val bands = 26
        for (i in 0 until bands) {
            val f = i / (bands - 1f)
            val col = when {
                f < 0.4f -> lerpC(INDIGO, DUSK, f / 0.4f)
                f < 0.75f -> lerpC(DUSK, ROSE, (f - 0.4f) / 0.35f)
                else -> lerpC(ROSE, AMBER, (f - 0.75f) / 0.25f)
            }
            p.color = col
            c.drawRect(0f, hy * f, w.toFloat(), hy * (f + 1.2f / bands), p)
        }
        // low sun with glow
        val sunX = w * 0.38f
        p.color = GOLD; p.alpha = 70
        c.drawCircle(sunX, hy - 6, 58f, p)
        p.alpha = 255
        p.color = GOLD
        c.drawRect(sunX - 34, hy - 34, sunX + 34, hy, p)
        p.color = lerpC(GOLD, CREAM, 0.5f)
        c.drawRect(sunX - 22, hy - 34, sunX + 22, hy - 18, p)
        // anime clouds, three parallax layers drifting toward the camera
        for (layer in 0..2) {
            val speed = 26f + layer * 22f
            val off = (t * speed / 1000f) % (w + 420f)
            val cy = hy * (0.20f + layer * 0.16f)
            val s = 1f + layer * 0.55f
            cloud(c, w - off, cy, s, layer)
            cloud(c, w - off + w * 0.62f + 180f, cy + 34f * s, s * 0.8f, layer)
        }
    }

    private fun cloud(c: Canvas, x: Float, y: Float, s: Float, layer: Int) {
        val body = lerpC(CREAM, ROSE, 0.12f + layer * 0.12f)
        val shade = lerpC(ROSE, DUSK, 0.30f)
        p.color = shade
        blob(c, x + 6 * s, y + 8 * s, s)
        p.color = body
        blob(c, x, y, s)
    }

    private fun blob(c: Canvas, x: Float, y: Float, s: Float) {
        c.drawRect(x, y, x + 84 * s, y + 20 * s, p)
        c.drawRect(x + 14 * s, y - 12 * s, x + 58 * s, y, p)
        c.drawRect(x + 30 * s, y - 22 * s, x + 50 * s, y - 12 * s, p)
        c.drawRect(x - 12 * s, y + 6 * s, x, y + 20 * s, p)
    }

    private fun drawWorld(c: Canvas, w: Int, h: Int, t: Long) {
        val hy = horizonY(w, h, t)
        val grow = ease(seg(t, 900L, APPROACH_END))          // mountains rise
        // far mountains
        mountain(c, w * 0.72f, hy, w * (0.16f + 0.62f * grow), h * (0.06f + 0.42f * grow), MTN_FAR)
        mountain(c, w * 0.94f, hy, w * (0.12f + 0.40f * grow), h * (0.05f + 0.30f * grow), lerpC(MTN_FAR, INDIGO, 0.4f))
        if (grow > 0.25f) // near ridge slides in
            mountain(c, w * 0.55f + (1 - grow) * w * 0.3f, hy, w * 0.34f * grow, h * 0.30f * grow, MTN_NEAR)
        // the sea — rows of moving pixel shimmer, faster near the camera
        p.color = SEA_DEEP
        c.drawRect(0f, hy, w.toFloat(), h.toFloat(), p)
        var row = 0
        var y = hy + 3
        while (y < h) {
            val depth = (y - hy) / (h - hy)
            val speed = 40f + depth * depth * 480f
            val off = (t * speed / 1000f + hash(row) * w)
            val step = (26 - depth * 14).toInt().coerceAtLeast(7)
            var i = 0
            while (i < w / step + 2) {
                val x = (i * step - off % step.toFloat() * 2 + hash(row * 31 + i) * step) % (w + 20)
                val shimmer = hash(row * 7 + i + (t / 160).toInt())
                p.color = when {
                    abs(x - w * 0.38f) < w * (0.05f + depth * 0.13f) && shimmer > 0.35f ->
                        lerpC(AMBER, GOLD, shimmer)          // sun road
                    shimmer > 0.72f -> lerpC(SEA_MID, CREAM, 0.35f)
                    else -> lerpC(SEA_DEEP, SEA_MID, shimmer)
                }
                val px = 2f + depth * 5f
                c.drawRect(x, y, x + px * 2, y + px, p)
                i++
            }
            row++
            y += 5 + depth * 16
        }
    }

    private fun mountain(c: Canvas, cx: Float, baseY: Float, halfW: Float, ht: Float, col: Int) {
        p.color = col
        var yy = 0f
        val steps = 26
        while (yy < ht) {
            val f = yy / ht
            val ww = halfW * (1 - f)
            c.drawRect(cx - ww, baseY - yy - ht / steps, cx + ww, baseY - yy, p)
            yy += ht / steps
        }
        p.color = lerpC(col, CREAM, 0.55f)   // snow cap
        c.drawRect(cx - halfW * 0.10f, baseY - ht, cx + halfW * 0.10f, baseY - ht * 0.86f, p)
    }

    // =========================================================================
    // ACT II — the plateau, the table, the CRT
    // =========================================================================

    /** vis 0→1 : plateau rises into frame (camera lands) */
    private fun drawPlateau(c: Canvas, w: Int, h: Int, vis: Float, t: Long) {
        val e = easeOut(vis)
        val gy = h - h * 0.34f * e              // ground line rises
        // ground
        p.color = GRASS
        c.drawRect(0f, gy, w.toFloat(), h.toFloat(), p)
        p.color = GRASS_D
        var x = 0f
        while (x < w) {
            c.drawRect(x, gy, x + 26, gy + 5, p); x += 52f
        }
        // swaying grass tufts + tiny chamomiles
        for (i in 0 until 14) {
            val gx = hash(i * 13) * w
            val sway = sin(t / 400.0 + i).toFloat() * 3f
            p.color = if (i % 2 == 0) GRASS_D else STEM
            c.drawRect(gx + sway, gy - 10, gx + sway + 3, gy, p)
            c.drawRect(gx + 6 - sway, gy - 7, gx + 9 - sway, gy, p)
            if (i % 5 == 0) {
                p.color = PETAL; c.drawRect(gx + sway - 3, gy - 16, gx + sway + 6, gy - 11, p)
                p.color = CENTER_Y; c.drawRect(gx + sway, gy - 14, gx + sway + 3, gy - 12, p)
            }
        }
    }

    /** table + CRT with fake-3D orbit, flower building inside */
    private fun drawCrtStage(c: Canvas, w: Int, h: Int, t: Long) {
        val gy = h - h * 0.34f
        // orbit angle: -55° → 0 across ORBIT, held at 0 after
        val orbitP = ease(seg(t, ARRIVE_END, ORBIT_END))
        val ang = (1 - orbitP) * -0.96f          // radians-ish factor
        val zoomP = ease(seg(t, FRONT_END, ZOOM_END))

        // scale: approach small→big; zoom continues to full-screen
        val baseScale = lerp(0.72f, 1f, ease(seg(t, ARRIVE_END, ARRIVE_END + 500)))
        val scale = lerp(baseScale, 3.6f, zoomP)
        val cx = w * 0.5f + sin(ang.toDouble()).toFloat() * w * 0.06f
        val cy = gy - 96f * scale * 0.5f - 14f + zoomP * h * 0.12f

        val bw = 190f * scale
        val bh = 150f * scale
        val skew = sin(ang.toDouble()).toFloat() * bw * 0.30f
        val envA = ((1 - seg(t, FRONT_END + 300, ZOOM_END)) * 255).toInt()

        if (envA > 0) {
            // table
            p.color = WOOD; p.alpha = envA
            c.drawRect(cx - bw * 0.62f, cy + bh * 0.5f, cx + bw * 0.62f, cy + bh * 0.5f + 12f * scale, p)
            c.drawRect(cx - bw * 0.48f, cy + bh * 0.5f + 12f * scale, cx - bw * 0.38f, gy + 8, p)
            c.drawRect(cx + bw * 0.38f, cy + bh * 0.5f + 12f * scale, cx + bw * 0.48f, gy + 8, p)
            p.alpha = 255
        }

        // CRT body with side panel (fake 3D)
        p.color = CRT_BODY; p.alpha = if (envA < 255) envA else 255
        if (skew < 0) c.drawRect(cx + bw / 2, cy - bh / 2 + abs(skew) * 0.2f, cx + bw / 2 + abs(skew), cy + bh / 2, p)
        else if (skew > 0) c.drawRect(cx - bw / 2 - skew, cy - bh / 2 + skew * 0.2f, cx - bw / 2, cy + bh / 2, p)
        p.color = CRT_BODY_L
        c.drawRect(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2, p)
        p.color = CRT_BODY
        c.drawRect(cx - bw / 2, cy + bh / 2 - 9f * scale, cx + bw / 2, cy + bh / 2, p)
        // feet + knob
        c.drawRect(cx - bw * 0.4f, cy + bh / 2, cx - bw * 0.3f, cy + bh / 2 + 5f * scale, p)
        c.drawRect(cx + bw * 0.3f, cy + bh / 2, cx + bw * 0.4f, cy + bh / 2 + 5f * scale, p)
        p.color = GOLD
        c.drawRect(cx + bw * 0.34f, cy + bh * 0.30f, cx + bw * 0.34f + 6f * scale, cy + bh * 0.30f + 6f * scale, p)
        p.alpha = 255

        // screen
        val sw = bw * 0.78f
        val sh = bh * 0.72f
        val sr = RectF(cx - sw / 2, cy - bh * 0.42f, cx + sw / 2, cy - bh * 0.42f + sh)
        val glow = ease(seg(t, ARRIVE_END, ORBIT_END - 600))
        p.color = GOLD; p.alpha = (30 * glow * (1 - zoomP)).toInt()
        c.drawRect(sr.left - 8, sr.top - 8, sr.right + 8, sr.bottom + 8, p)
        p.alpha = 255

        c.save()
        c.clipRect(sr)
        p.color = SCREEN_BG
        c.drawRect(sr, p)
        // flower assembling inside
        val buildP = seg(t, ARRIVE_END + 300, 4800L)
        val coreT = t
        val fs = (3.2f * scale * (1 + zoomP * 0.5f))
        drawFlower(c, sr.centerX(), sr.centerY() + sh * 0.06f, fs, buildP, coreT, glow)
        // scanlines + faint chroma inside CRT only
        p.color = Color.BLACK
        var sy = sr.top
        val gap = 3f + zoomP * 3f
        while (sy < sr.bottom) {
            p.alpha = (26 + 20 * zoomP).toInt()
            c.drawRect(sr.left, sy, sr.right, sy + 1f, p)
            sy += gap
        }
        p.alpha = 255
        c.restore()
        // glass reflection sweeping with orbit
        if (envA > 0) {
            p.color = CREAM; p.alpha = (34 * (1 - zoomP)).toInt()
            val rx = sr.left + sr.width() * (0.15f + 0.5f * orbitP)
            c.drawRect(rx, sr.top, rx + sr.width() * 0.08f, sr.bottom, p)
            p.alpha = 255
        }
    }

    // =========================================================================
    // the chamomile — built pixel by pixel
    // =========================================================================

    /**
     * buildP: 0..1 assembly (center → d-pad → petals → stem → bloom)
     * coreT: absolute time for the d-pad activation choreography
     */
    private fun drawFlower(c: Canvas, cx: Float, cy: Float, s: Float, buildP: Float, coreT: Long, glow: Float) {
        val centerP = (buildP / 0.20f).coerceIn(0f, 1f)
        val dpadP = ((buildP - 0.20f) / 0.12f).coerceIn(0f, 1f)
        val petalP = ((buildP - 0.32f) / 0.38f).coerceIn(0f, 1f)
        val stemP = ((buildP - 0.70f) / 0.20f).coerceIn(0f, 1f)
        val bloom = ((buildP - 0.90f) / 0.10f).coerceIn(0f, 1f)
        val bounce = if (bloom > 0) 1f + sin(bloom * Math.PI).toFloat() * 0.09f else 1f
        val S = s * bounce

        // converging pixels while the center forms
        if (centerP < 1f) {
            p.color = CENTER_Y
            for (i in 0 until 14) {
                val a = hash(i) * 6.28f
                val d = (1 - easeOut(centerP)) * 26f * s * (0.5f + hash(i + 40))
                c.drawRect(cx + cos(a.toDouble()).toFloat() * d, cy + sin(a.toDouble()).toFloat() * d,
                    cx + cos(a.toDouble()).toFloat() * d + s, cy + sin(a.toDouble()).toFloat() * d + s, p)
            }
        }
        // center disc
        if (centerP > 0.2f) {
            val r = 2.6f * S * easeOut(centerP)
            p.color = CENTER_D
            c.drawRect(cx - r, cy - r, cx + r, cy + r, p)
            p.color = CENTER_Y
            c.drawRect(cx - r + S * 0.6f, cy - r + S * 0.6f, cx + r - S * 0.6f, cy + r - S * 0.6f, p)
        }
        // petals — eight, popping in sequence
        for (i in 0 until 8) {
            val need = i / 8f
            if (petalP <= need) continue
            val pop = ((petalP - need) / 0.125f).coerceIn(0f, 1f)
            val overs = 1f + (1 - pop) * 0.5f
            val a = i * Math.PI / 4 - Math.PI / 2
            val px = cx + cos(a).toFloat() * 5.4f * S
            val py = cy + sin(a).toFloat() * 5.4f * S
            val pr = 2.1f * S * easeOut(pop) * overs
            p.color = PETAL_SH
            c.drawRect(px - pr, py - pr + S * 0.5f, px + pr, py + pr + S * 0.5f, p)
            p.color = PETAL
            c.drawRect(px - pr, py - pr, px + pr, py + pr, p)
        }
        // pulse ring through petals after core flash
        val pulseP = seg(coreT, 5350L, 5650L)
        if (pulseP > 0f && pulseP < 1f) {
            p.color = GOLD; p.alpha = ((1 - pulseP) * 160).toInt()
            val rr = 3f * S + pulseP * 8f * S
            c.drawRect(cx - rr, cy - rr, cx + rr, cy - rr + S * 0.5f, p)
            c.drawRect(cx - rr, cy + rr, cx + rr, cy + rr + S * 0.5f, p)
            c.drawRect(cx - rr, cy - rr, cx - rr + S * 0.5f, cy + rr, p)
            c.drawRect(cx + rr, cy - rr, cx + rr + S * 0.5f, cy + rr, p)
            p.alpha = 255
        }
        // d-pad core (control heart)
        if (dpadP > 0f) {
            val d = S * 1.05f
            val litSeq = listOf(
                seg(coreT, 4800L, 4950L), seg(coreT, 4950L, 5100L),
                seg(coreT, 5100L, 5250L), seg(coreT, 5250L, 5400L)
            )
            val flash = seg(coreT, 5400L, 5550L)
            val dirs = listOf(0f to -d, d to 0f, 0f to d, -d to 0f)  // U R D L
            for ((i, dir) in dirs.withIndex()) {
                val lit = litSeq[i] > 0f && litSeq[i] < 1f
                p.color = if (lit) CREAM else NAVY
                p.alpha = (dpadP * 255).toInt()
                c.drawRect(cx + dir.first - d / 2, cy + dir.second - d / 2,
                    cx + dir.first + d / 2, cy + dir.second + d / 2, p)
            }
            p.color = if (flash > 0f && flash < 1f) GOLD else NAVY
            c.drawRect(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2, p)
            p.alpha = 255
        }
        // stem + leaves grow downward
        if (stemP > 0f) {
            val len = 7.5f * S * easeOut(stemP)
            p.color = STEM
            c.drawRect(cx - S * 0.5f, cy + 3f * S, cx + S * 0.5f, cy + 3f * S + len, p)
            if (stemP > 0.5f) {
                p.color = LEAF
                val ly = cy + 3f * S + len * 0.55f
                c.drawRect(cx - 2.6f * S, ly, cx - S * 0.4f, ly + S, p)
                c.drawRect(cx + S * 0.4f, ly + S, cx + 2.6f * S, ly + 2 * S, p)
            }
        }
        // bloom particles radiate
        if (bloom > 0f && bloom < 1f) {
            for (i in 0 until 10) {
                val a = hash(i + 7) * 6.28f
                val d = easeOut(bloom) * 11f * S
                p.color = listOf(GOLD, PETAL, LEAF, ROSE)[i % 4]
                p.alpha = ((1 - bloom) * 255).toInt()
                c.drawRect(cx + cos(a.toDouble()).toFloat() * d, cy + sin(a.toDouble()).toFloat() * d,
                    cx + cos(a.toDouble()).toFloat() * d + s, cy + sin(a.toDouble()).toFloat() * d + s, p)
            }
            p.alpha = 255
        }
    }

    // =========================================================================
    // ACT III — inside the screen: reveal, features, return
    // =========================================================================

    private fun drawInside(c: Canvas, w: Int, h: Int, t: Long) {
        p.color = SCREEN_BG
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        // faint stars
        for (i in 0 until 26) {
            p.color = CREAM; p.alpha = (60 * (0.5f + 0.5f * sin(t / 500.0 + i)).toFloat()).toInt()
            c.drawRect(hash(i) * w, hash(i + 99) * h, hash(i) * w + 2, hash(i + 99) * h + 2, p)
        }
        p.alpha = 255

        val cx = w / 2f
        val settle = easeOut(seg(t, ZOOM_END, ZOOM_END + 400))
        val over = 1f + (1 - settle) * 0.12f

        when {
            t < REVEAL_END -> {
                // full-screen flower settles with overshoot; title assembles
                drawFlower(c, cx, h * 0.34f, 6.5f * over, 1f, 0L, 1f)
                buildTitle(c, w, h, seg(t, ZOOM_END + 250, REVEAL_END - 120))
            }
            t < FEAT_C_END -> drawFeatures(c, w, h, t)
            else -> {
                // return: everything folds back into the chamomile
                val rp = ease(seg(t, FEAT_C_END, TOTAL - 150))
                drawFlower(c, cx, lerp(h * 0.42f, h * 0.34f, rp), lerp(4.5f, 6.5f, rp), 1f, 0L, 1f)
                buildTitle(c, w, h, 1f)
            }
        }
        // gentle scanlines over everything inside
        p.color = Color.BLACK
        var sy = 0f
        while (sy < h) { p.alpha = 16; c.drawRect(0f, sy, w.toFloat(), sy + 1f, p); sy += 4f }
        p.alpha = 255
    }

    /** MANZANILLA OS assembles from sliding pixel slices, not typewriting */
    private fun buildTitle(c: Canvas, w: Int, h: Int, tp: Float) {
        if (tp <= 0f) return
        val paint = txt(CREAM, 40f)
        val text = "MANZANILLA OS"
        val tw = paint.measureText(text)
        val x0 = (w - tw) / 2f
        val y0 = h * 0.62f
        val slices = 8
        for (i in 0 until slices) {
            val sp = ((tp - i * 0.06f) / 0.5f).coerceIn(0f, 1f)
            if (sp <= 0f) continue
            val e = easeOut(sp)
            val from = if (i % 2 == 0) -w * 0.4f else w * 0.4f
            val dx = from * (1 - e)
            c.save()
            c.clipRect(x0 + tw * i / slices, y0 - 46, x0 + tw * (i + 1) / slices, y0 + 14)
            c.drawText(text, x0 + dx, y0, paint)
            // scan bar at the locking edge
            if (sp < 1f) {
                p.color = GOLD; p.alpha = 150
                c.drawRect(x0 + tw * i / slices + dx * 0.2f, y0 - 46,
                    x0 + tw * i / slices + dx * 0.2f + 5, y0 + 14, p)
                p.alpha = 255
            }
            c.restore()
        }
        if (tp > 0.85f) {
            val sub = txt(lerpC(SCREEN_BG, STEM, (tp - 0.85f) / 0.15f), 13f)
            val s2 = "CHAMOMILE  ·  CONTROL  ·  COMMUNICATE"
            c.drawText(s2, (w - sub.measureText(s2)) / 2f, h * 0.70f, sub)
        }
    }

    /** one continuous metamorphosis: AI icons → microphone → keypad */
    private fun drawFeatures(c: Canvas, w: Int, h: Int, t: Long) {
        val cx = w / 2f
        val cy = h * 0.40f
        // small flower stays anchored top as the source of everything
        drawFlower(c, cx, h * 0.16f, 3.0f, 1f, 0L, 1f)

        val sp = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
        fun sprite(b: android.graphics.Bitmap, x: Float, y: Float, s: Float, a: Int = 255) {
            sp.alpha = a
            c.drawBitmap(b, null, RectF(x, y, x + b.width * s, y + b.height * s), sp)
        }

        when {
            t < FEAT_A_END -> {                              // A · AI COMMUNICATOR
                val ap = seg(t, REVEAL_END, FEAT_A_END - 100)
                // signals stream out of the flower
                for (i in 0 until 12) {
                    val f = ((ap * 1.4f + hash(i)) % 1f)
                    p.color = listOf(GOLD, ROSE, LEAF)[i % 3]
                    p.alpha = ((1 - f) * 220).toInt()
                    val a = (i % 3 - 1) * 0.5f
                    c.drawRect(cx + a * f * w * 0.3f - 2, h * 0.20f + f * (cy - h * 0.16f) - 2,
                        cx + a * f * w * 0.3f + 2, h * 0.20f + f * (cy - h * 0.16f) + 2, p)
                }
                p.alpha = 255
                val pop = { need: Float -> easeOut(((ap - need) / 0.25f).coerceIn(0f, 1f)) }
                sprite(Sprites.flower, cx - w * 0.26f - 25, cy, 5f * pop(0.15f))
                sprite(Sprites.book, cx - 25f, cy, 5f * pop(0.3f))
                sprite(Sprites.orange, cx + w * 0.26f - 25, cy, 3.2f * pop(0.45f))
                caption(c, w, h, if (ap < 0.6f) "AI COMMUNICATOR" else "TALK TO YOUR AI")
            }
            t < FEAT_B_END -> {                              // B · VOICE
                val bp = seg(t, FEAT_A_END, FEAT_B_END - 100)
                val e = ease(bp * 2f)
                // icons collapse inward
                if (e < 1f) {
                    sprite(Sprites.flower, lerp(cx - w * 0.26f - 25, cx - 20f, e), cy, 5f * (1 - e), ((1 - e) * 255).toInt())
                    sprite(Sprites.book, cx - 25f, cy, 5f * (1 - e), ((1 - e) * 255).toInt())
                    sprite(Sprites.orange, lerp(cx + w * 0.26f - 25, cx - 20f, e), cy, 3.2f * (1 - e), ((1 - e) * 255).toInt())
                }
                val mp = easeOut(((bp - 0.25f) / 0.3f).coerceIn(0f, 1f))
                if (mp > 0) sprite(Sprites.mic, cx - 4 * 7f * mp, cy - 20, 7f * mp)
                // live waveform
                for (i in 0 until 14) {
                    val bh2 = (abs(sin(t / 90.0 + i * 1.2)) * 26 + 5).toFloat() * mp
                    p.color = if (i % 2 == 0) LEAF else GOLD
                    c.drawRect(cx - 98f + i * 14, cy + 58 - bh2, cx - 98f + i * 14 + 8, cy + 58, p)
                }
                caption(c, w, h, "VOICE CONTROL")
            }
            else -> {                                        // C · STREAM DECK
                val cp = seg(t, FEAT_B_END, FEAT_C_END - 80)
                // mic shatters into blocks that become a keypad
                val labels = listOf("WA", "FI", "CH", "CL", "CO", "NO")
                for (i in 0 until 6) {
                    val e = easeOut(((cp - i * 0.06f) / 0.4f).coerceIn(0f, 1f))
                    val scatterA = hash(i * 3) * 6.28f
                    val fx = cx + cos(scatterA.toDouble()).toFloat() * (1 - e) * w * 0.3f
                    val fy = cy + sin(scatterA.toDouble()).toFloat() * (1 - e) * h * 0.2f
                    val tx = cx - 105f + (i % 3) * 74f
                    val ty = cy - 26f + (i / 3) * 60f
                    val x = lerp(fx, tx, e); val y = lerp(fy, ty, e)
                    val pressed = i == 4 && cp > 0.7f && cp < 0.85f
                    val dy = if (pressed) 4f else 0f
                    p.color = if (pressed) GOLD else NAVY
                    c.drawRect(x, y + dy, x + 58f, y + 46f + dy, p)
                    p.color = if (pressed) NAVY else CREAM
                    c.drawRect(x + 3, y + 3 + dy, x + 55f, y + 40f + dy, p)
                    val lp = txt(NAVY, 15f)
                    c.drawText(labels[i], x + 16, y + 30 + dy, lp)
                }
                caption(c, w, h, if (cp < 0.6f) "CONTROL YOUR PC" else "STREAM DECK MODE")
            }
        }
    }

    private fun caption(c: Canvas, w: Int, h: Int, s: String) {
        val paint = txt(CREAM, 19f)
        c.drawText(s, (w - paint.measureText(s)) / 2f, h * 0.83f, paint)
    }
}
