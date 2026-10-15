package com.agentdeck.hotel

/** Decides who just spoke from the selected pair. Not limited to English and Spanish. */
object PairSide {
    private val scripts = mapOf(
        "ja" to Regex("[\\u3040-\\u30ff]"),
        "ko" to Regex("[\\uac00-\\ud7af]"),
        "zh" to Regex("[\\u4e00-\\u9fff]"),
        "ar" to Regex("[\\u0600-\\u06ff]"),
        "fa" to Regex("[\\u0600-\\u06ff]"),
        "ur" to Regex("[\\u0600-\\u06ff]"),
        "he" to Regex("[\\u0590-\\u05ff]"),
        "el" to Regex("[\\u0370-\\u03ff]"),
        "ru" to Regex("[\\u0400-\\u04ff]"),
        "uk" to Regex("[\\u0400-\\u04ff]"),
        "bg" to Regex("[\\u0400-\\u04ff]"),
        "sr" to Regex("[\\u0400-\\u04ff]"),
        "th" to Regex("[\\u0e00-\\u0e7f]"),
        "hi" to Regex("[\\u0900-\\u097f]"),
        "mr" to Regex("[\\u0900-\\u097f]"),
        "ne" to Regex("[\\u0900-\\u097f]"),
        "bn" to Regex("[\\u0980-\\u09ff]"),
        "ta" to Regex("[\\u0b80-\\u0bff]"),
        "te" to Regex("[\\u0c00-\\u0c7f]"),
        "kn" to Regex("[\\u0c80-\\u0cff]"),
        "gu" to Regex("[\\u0a80-\\u0aff]"),
        "pa" to Regex("[\\u0a00-\\u0a7f]"),
        "hy" to Regex("[\\u0530-\\u058f]"),
        "ka" to Regex("[\\u10a0-\\u10ff]")
    )
    private val marks = mapOf(
        "es" to "ñ¿¡",
        "fr" to "œ«»",
        "de" to "ß",
        "pt" to "ãõ",
        "pl" to "ąćęłńśźż",
        "cs" to "ěřů",
        "ro" to "ășț",
        "tr" to "ğış",
        "hu" to "őű",
        "vi" to "ơưđ",
        "ca" to "·"
    )
    private val words = mapOf(
        "en" to listOf("the", "is", "are", "where", "what", "please", "room", "you", "we", "can"),
        "es" to listOf("el", "la", "los", "dónde", "donde", "está", "esta", "por", "favor", "habitación", "una", "que"),
        "fr" to listOf("le", "les", "où", "est", "une", "pour", "chambre", "bonjour", "vous", "nous"),
        "de" to listOf("der", "die", "das", "ist", "wo", "zimmer", "bitte", "nicht", "ein", "und"),
        "it" to listOf("il", "dove", "per", "camera", "grazie", "una", "che", "non"),
        "pt" to listOf("onde", "está", "para", "quarto", "obrigado", "uma", "não", "você"),
        "nl" to listOf("het", "een", "waar", "kamer", "alstublieft", "niet", "wij"),
        "ca" to listOf("on", "és", "habitació", "gràcies", "per", "una"),
        "pl" to listOf("gdzie", "jest", "pokój", "proszę", "nie", "dla"),
        "sv" to listOf("var", "är", "rummet", "tack", "inte"),
        "da" to listOf("hvor", "værelset", "tak", "ikke"),
        "no" to listOf("hvor", "rommet", "takk", "ikke"),
        "fi" to listOf("missä", "huone", "kiitos", "ei"),
        "tr" to listOf("nerede", "oda", "lütfen", "için", "bir"),
        "id" to listOf("mana", "kamar", "tolong", "tidak", "saya"),
        "ms" to listOf("mana", "bilik", "tolong", "tidak"),
        "vi" to listOf("đâu", "phòng", "xin", "không"),
        "ro" to listOf("unde", "este", "cameră", "rog", "nu"),
        "hu" to listOf("hol", "van", "szoba", "kérem", "nem"),
        "cs" to listOf("kde", "je", "pokoj", "prosím", "není"),
        "sk" to listOf("kde", "izba", "prosím", "nie"),
        "hr" to listOf("gdje", "soba", "molim", "nije"),
        "af" to listOf("waar", "kamer", "asseblief", "nie"),
        "tl" to listOf("saan", "kuwarto", "hindi", "po")
    )

    fun side(text: String, guest: String, staff: String, fallback: String, detected: String? = null): String {
        val code = detected?.lowercase()?.substringBefore('-')?.trim().orEmpty()
        if (code == guest) return "guest"
        if (code == staff) return "staff"
        val guestScript = scriptHit(text, guest)
        val staffScript = scriptHit(text, staff)
        if (guestScript && !staffScript) return "guest"
        if (staffScript && !guestScript) return "staff"
        val g = score(text, guest)
        val s = score(text, staff)
        return when {
            g > s && g > 0 -> "guest"
            s > g && s > 0 -> "staff"
            else -> if (fallback == "staff") "staff" else "guest"
        }
    }

    fun other(side: String) = if (side == "guest") "staff" else "guest"

    private fun scriptHit(text: String, code: String) = scripts[code]?.containsMatchIn(text) == true

    private fun score(text: String, code: String): Int {
        var n = 0
        marks[code].orEmpty().forEach { if (text.contains(it)) n += 3 }
        val folded = text.lowercase()
        words[code].orEmpty().forEach { word ->
            if (Regex("(?<![\\p{L}])${Regex.escape(word)}(?![\\p{L}])").containsMatchIn(folded)) n += 2
        }
        return n
    }
}
