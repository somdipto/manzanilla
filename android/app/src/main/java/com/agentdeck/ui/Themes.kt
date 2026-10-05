package com.agentdeck.ui

import android.graphics.Color

/**
 * Manzanilla v4 themes. First three match the official mockups:
 * CLASSIC (retro blue), MIDNIGHT, CHAMOMILE. Six bonus flavors after.
 * `sky` enables the platformer frame (gradient sky, clouds, pipes, bricks).
 */
object Themes {

    data class Th(
        val name: String, val tag: String,
        val bg: Int, val bg2: Int,          // sky gradient top/bottom
        val panel: Int, val panelLine: Int, // card fill + border
        val fg: Int, val accent: Int, val pink: Int,
        val cyan: Int, val yellow: Int, val green: Int, val gray: Int,
        val term: Int, val sky: Boolean
    ) {
        /**
         * Classify the actual card surface, not just the wallpaper. Transparent
         * panels are composited over the theme background before measuring.
         */
        val darkIconSurface: Boolean
            get() {
                val a = Color.alpha(panel) / 255f
                fun mixed(channel: (Int) -> Int): Float =
                    channel(panel) * a + channel(bg) * (1f - a)
                val luminance = 0.2126f * mixed(Color::red) +
                    0.7152f * mixed(Color::green) + 0.0722f * mixed(Color::blue)
                return luminance < 142f
            }
    }

    private fun c(r: Int, g: Int, b: Int) = Color.rgb(r, g, b)

    val all = listOf(
        Th("CLASSIC", "retro blue", c(32, 100, 220), c(64, 150, 245),
            c(10, 28, 66), c(120, 190, 255),
            c(252, 252, 252), c(248, 56, 0), c(248, 120, 168),
            c(90, 200, 255), c(248, 184, 0), c(88, 216, 84), c(150, 175, 215),
            c(180, 240, 190), true),
        Th("MIDNIGHT", "deep space", c(10, 12, 24), c(18, 22, 44),
            c(20, 26, 52), c(90, 100, 160),
            c(240, 240, 255), c(255, 90, 90), c(240, 120, 200),
            c(80, 200, 240), c(255, 200, 80), c(120, 220, 120), c(110, 116, 150),
            c(190, 190, 240), false),
        Th("CHAMOMILE", "light & fresh", c(255, 247, 230), c(255, 251, 240),
            c(250, 240, 215), c(15, 30, 58),
            c(15, 30, 58), c(200, 60, 30), c(220, 100, 140),
            c(40, 110, 170), c(200, 140, 20), c(90, 130, 70), c(150, 140, 120),
            c(50, 70, 50), false),
        Th("GAME KID", "pea soup", c(15, 56, 15), c(24, 72, 24),
            c(24, 72, 24), c(139, 172, 15),
            c(155, 188, 15), c(139, 172, 15), c(155, 188, 15),
            c(139, 172, 15), c(155, 188, 15), c(139, 172, 15), c(48, 98, 48),
            c(155, 188, 15), false),
        Th("VIRTUAL BOY", "headache red", c(8, 0, 0), c(24, 0, 0),
            c(24, 0, 0), c(180, 30, 30),
            c(255, 64, 64), c(255, 0, 0), c(200, 48, 48),
            c(255, 96, 96), c(255, 128, 128), c(255, 64, 64), c(120, 24, 24),
            c(255, 64, 64), false),
        Th("VAPOR DREAM", "aesthetic", c(20, 8, 36), c(50, 16, 80),
            c(38, 16, 62), c(255, 60, 172),
            c(255, 240, 255), c(255, 60, 172), c(255, 130, 220),
            c(0, 255, 240), c(255, 230, 100), c(130, 255, 180), c(140, 110, 160),
            c(200, 255, 240), false),
        Th("ARCTIC LCD", "ice terminal", c(10, 20, 28), c(14, 30, 42),
            c(18, 34, 46), c(70, 180, 255),
            c(220, 240, 255), c(70, 180, 255), c(150, 200, 255),
            c(0, 220, 255), c(255, 220, 120), c(120, 255, 200), c(90, 120, 140),
            c(180, 230, 255), false),
        Th("SUNSET DRIVE", "outrun", c(24, 10, 30), c(90, 30, 60),
            c(42, 20, 50), c(255, 110, 60),
            c(255, 240, 220), c(255, 110, 60), c(255, 80, 140),
            c(90, 200, 255), c(255, 200, 60), c(140, 230, 120), c(140, 110, 120),
            c(255, 200, 160), false),
        Th("PAPER MONO", "e-ink zen", c(238, 236, 228), c(230, 228, 218),
            c(222, 220, 210), c(30, 30, 30),
            c(30, 30, 30), c(30, 30, 30), c(90, 90, 90),
            c(60, 60, 60), c(30, 30, 30), c(30, 30, 30), c(150, 150, 145),
            c(30, 30, 30), false),
        // Fluid handheld-console interaction layer. It keeps Manzanilla's
        // existing actions and physical-key mapping, but presents them as
        // animated glossy objects with a calmer, spatial navigation rhythm.
        Th("ORBIT", "fluid companion", c(10, 21, 46), c(18, 82, 118),
            Color.argb(192, 18, 44, 75), Color.argb(188, 184, 230, 255),
            c(245, 251, 255), c(255, 124, 55), c(237, 102, 178),
            c(77, 220, 255), c(255, 201, 72), c(91, 230, 169), c(138, 168, 192),
            c(201, 242, 255), false),
        // Spain Academy-inspired material: warm paper, clear liquid glass,
        // circular geometry and restrained red/yellow/green brand accents.
        Th("LIQUID GLASS", "Spain Academy", c(253, 252, 246), c(242, 248, 242),
            Color.argb(40, 255, 255, 255), Color.argb(150, 255, 255, 255),
            c(48, 48, 48), c(170, 21, 27), c(216, 42, 42),
            c(0, 133, 66), c(241, 191, 0), c(0, 168, 89), c(108, 112, 108),
            c(34, 78, 58), false),
        Th("WEB SLINGER", "comic night", c(5, 13, 34), c(13, 38, 82),
            Color.argb(226, 8, 22, 53), c(225, 31, 54),
            c(246, 244, 232), c(225, 31, 54), c(243, 82, 105),
            c(72, 139, 236), c(246, 244, 232), c(69, 186, 226), c(119, 140, 176),
            c(172, 208, 255), false),
        // ORBIT's visual/audio material applied to the familiar Manzanilla
        // tile launcher. Appended to preserve every existing saved theme index.
        Th("DOCKSTATION", "orbit · classic deck", c(10, 21, 46), c(18, 82, 118),
            c(8, 30, 61), c(125, 209, 250),
            c(245, 251, 255), c(255, 124, 55), c(237, 102, 178),
            c(77, 220, 255), c(255, 201, 72), c(91, 230, 169), c(138, 168, 192),
            c(201, 242, 255), false),
        // Suhair's 2026 concept: bright ambient wallpaper, slow luminous
        // colour spheres and optically refractive capsule instruments.
        Th("AURA", "frosted companion", c(239, 244, 248), c(250, 248, 247),
            Color.argb(34, 255, 255, 255), Color.argb(180, 255, 255, 255),
            c(24, 31, 42), c(255, 91, 113), c(229, 102, 210),
            c(63, 194, 236), c(255, 188, 73), c(44, 194, 129), c(105, 116, 132),
            c(31, 54, 67), false)
    )

    var idx = 0
    val auraIndex get() = all.indexOfFirst { it.name == "AURA" }.coerceAtLeast(0)
    val dockstationIndex get() = all.indexOfFirst { it.name == "DOCKSTATION" }.coerceAtLeast(0)
    val current get() = all[idx.coerceIn(0, all.size - 1)]
}
