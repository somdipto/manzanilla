package com.agentdeck.ui

import android.graphics.Bitmap
import android.graphics.Color

/** Hand-placed pixel sprites — Manzanilla v4, retro-platformer flavor. */
object Sprites {

    private val palette = mapOf(
        'K' to Color.rgb(16, 20, 40),      // outline navy-black
        'R' to Color.rgb(216, 40, 0),      // red
        'r' to Color.rgb(248, 56, 0),      // bright red
        'O' to Color.rgb(255, 138, 0),     // orange body
        'o' to Color.rgb(255, 176, 60),    // orange light
        'P' to Color.rgb(248, 120, 168),   // pink
        'p' to Color.rgb(255, 200, 220),   // light pink
        'Y' to Color.rgb(248, 184, 0),     // gold
        'y' to Color.rgb(255, 220, 100),   // light gold
        'G' to Color.rgb(0, 168, 0),       // green
        'g' to Color.rgb(88, 216, 84),     // light green
        'B' to Color.rgb(136, 88, 24),     // brown
        'b' to Color.rgb(200, 120, 50),    // brick light
        'W' to Color.rgb(252, 252, 252),   // white
        'C' to Color.rgb(60, 188, 252),    // cyan
        'c' to Color.rgb(160, 224, 255),   // light cyan
        'N' to Color.rgb(14, 42, 92),      // deep navy
        'D' to Color.rgb(110, 70, 20)      // brick dark mortar
    )

    private fun make(rows: List<String>): Bitmap {
        val h = rows.size; val w = rows[0].length
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            bmp.setPixel(x, y, palette[rows[y][x]] ?: Color.TRANSPARENT)
        }
        return bmp
    }

    /** the Manzanilla mascot — a cheeky orange with a bite taken out */
    val orange = make(listOf(
        "......GG........",
        ".....Ggg.G......",
        "......KKG.......",
        "....KKOOKK......",
        "..KKOOOOOOKK....",
        ".KOooOOOOOOOK...",
        ".KOoOOOOOOOOK...",
        "KOOOOOOOOOOOOK..",
        "KOOKKOOOKKOOOK..",
        "KOOOOOOOOOOOKW..",
        "KOOOOOOOOOOKWW..",
        "KOOKOOOOKOOOK...",
        ".KOOKKKKOOOK....",
        ".KOOOOOOOOK.....",
        "..KKOOOOKK......",
        "....KKKK........"
    ))

    /** legacy apple mark (kept for wallpaper + saver) */
    val apple = make(listOf(
        "......GG........",
        ".....Ggg.GG.....",
        "......KK.G......",
        "....KKrrKKK.....",
        "..KKrrrrrrKK....",
        ".KrrWrrrrrrrK...",
        ".KrWrrrrrrrrK...",
        "KrrrrrrrrrrrrK..",
        "KrrrrrrrrrrrrK..",
        "KrrrrrrrrrrrrK..",
        "KrrrrrrrrrKPPK..",
        ".KrrrrrrrKPWPK..",
        ".KrrrrrrrKPYPK..",
        "..KrrKKrrKKPK...",
        "...KK..KK..K....",
        "................"
    ))

    /** Clor — the flower */
    val flower = make(listOf(
        "..pp..pp..",
        ".pPPppPPp.",
        ".PPYYYYPP.",
        "pPYYWYYYPp",
        "pPYYYYYYPp",
        ".PPYYYYPP.",
        ".pPPppPPp.",
        "..pp.Gpp..",
        "....GG....",
        "..g.GG.g..",
        "...gGGg...",
        "....GG...."
    ))

    /** Codex — the knowledge book */
    val book = make(listOf(
        ".KKKKKKKK.",
        "KOooooooK.",
        "KObOOOObK.",
        "KOOYYOOOK.",
        "KOYyyYOOK.",
        "KOYyyYOOK.",
        "KOOYYOOOK.",
        "KObOOOObK.",
        "KOooooooK.",
        ".KKKKKKKK.",
        "KWWWWWWWWK",
        ".KKKKKKKK."
    ))

    /** Wispr — yellow listener with headphones */
    val wisprbox = make(listOf(
        "P..KKKK..P",
        "PKYyyyyKKP",
        "PKYyyyyYKP",
        "PKWKyyKWKP",
        ".KYyyyyYK.",
        ".KYKyyKYK.",
        ".KYyKKyYK.",
        ".KYyyyyYK.",
        "..KYyyYK..",
        "...KKKK..."
    ))

    /** owl (legacy wispr, kept for themes) */
    val owl = make(listOf(
        "P..KKKK..P",
        "PKKbbbbKKP",
        "PKbWKKWbKP",
        "PKbKbbKbKP",
        ".KbbYYbbK.",
        ".KbbbbbbK.",
        ".KbWbbWbK.",
        ".KbbbbbbK.",
        "..KbbbbK..",
        "...KKKK...",
        "..KK..KK..",
        ".........."
    ))

    val mic = make(listOf(
        "..KKKK..",
        ".KWccWK.",
        ".KWccWK.",
        ".KWccWK.",
        ".KWWWWK.",
        "..KKKK..",
        "....K...",
        "....K...",
        "..KKKKK."
    ))

    val key = make(listOf(
        ".KKKKKKKK.",
        "KWWWWWWWWK",
        "KWKKKKKKWK",
        "KWKCCCCKWK",
        "KWKCyyCKWK",
        "KWKCCCCKWK",
        "KWKKKKKKWK",
        "KWWWWWWWWK",
        ".KKKKKKKK."
    ))

    val cam = make(listOf(
        "..KKK.....",
        ".KKKKKKKK.",
        "KWWWWWWWWK",
        "KWKKKWWWWK",
        "KWKCKWWWWK",
        "KWKKKWWWWK",
        "KWWWWWWWWK",
        ".KKKKKKKK."
    ))

    // ---- scenery for the CLASSIC frame -------------------------------------

    val cloud = make(listOf(
        "...WWWW.....",
        "..WWWWWW....",
        ".WWWWWWWWWW.",
        "WWWWWWWWWWWW",
        ".WWWWWWWWWW."
    ))

    val pipe = make(listOf(
        "KGGggGGGGK",
        "KGgggGGGGK",
        "KKKKKKKKKK",
        ".KGgGGGGK.",
        ".KGgGGGGK.",
        ".KGgGGGGK.",
        ".KGgGGGGK.",
        ".KGgGGGGK."
    ))

    val brick = make(listOf(
        "bbbbDbbbb",
        "bbbbDbbbb",
        "DDDDDDDDD",
        "bbDbbbbDb",
        "bbDbbbbDb",
        "DDDDDDDDD"
    ))

    val qblock = make(listOf(
        "KKKKKKKK",
        "KYyyyyYK",
        "KyKKKKyK",
        "KyyKKyyK",
        "KyyKKyyK",
        "KyyyyyyK",
        "KyyKKyyK",
        "KKKKKKKK"
    ))

    val coin = make(listOf(
        "..KKKK..",
        ".KYyyYK.",
        "KYyKKyYK",
        "KYyKKyYK",
        "KYyKKyYK",
        "KYyKKyYK",
        ".KYyyYK.",
        "..KKKK.."
    ))

    /** the official Manzanilla chamomile — cream petals, gold heart, D-pad core */
    val chamomile = make(listOf(
        "....WWWW....",
        "..WWWWWWWW..",
        ".WWWpYYpWWW.",
        ".WWpYKYYpWW.",
        ".WWYKKKYYWW.",
        ".WWpYKYYpWW.",
        ".WWWpYYpWWW.",
        "..WWWWWWWW..",
        "....WWWW....",
        ".....GG.....",
        "..gg.GG.....",
        "...ggGG.....",
        ".....GG.gg..",
        ".....GGgg...",
        ".....GG....."
    ))

    val star = make(listOf(
        "....KK....",
        "...KyyK...",
        "KKKKyyKKKK",
        "KyyyyyyyyK",
        ".KyyyyyyK.",
        "..KyyyyK..",
        ".KyyKKyyK.",
        ".KK....KK."
    ))
}
