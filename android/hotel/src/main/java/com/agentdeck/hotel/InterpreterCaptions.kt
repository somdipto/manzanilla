package com.agentdeck.hotel

import org.json.JSONObject

/**
 * Two people share one microphone, so every human utterance arrives under the single local
 * participant identity and the provider wire protocol carries no language or translation-pair
 * markers (https://docs.fish.audio/agents/deploy/protocol.md#transcription-and-agent-state).
 * This class restores what the wire cannot say: which of the two session languages was spoken
 * (the speaker's side) and which agent turn translates which human turn (strict turn order -
 * the agent only replies to completed human turns, one reply per turn).
 */

/** Language identification restricted to the session's two languages: Unicode script first,
 * then distinctive letters and stopwords for same-script pairs. Offline and deterministic. */
internal object LanguageId {
    private fun family(code: String): String = when (code) {
        "ru", "uk", "bg", "sr", "kk" -> "cyrl"
        "el" -> "grek"
        "ar", "fa", "ur" -> "arab"
        "he" -> "hebr"
        "hi", "mr", "ne" -> "deva"
        "bn" -> "beng"
        "ta" -> "taml"
        "te" -> "telu"
        "kn" -> "knda"
        "gu" -> "gujr"
        "pa" -> "guru"
        "th" -> "thai"
        "ka" -> "geor"
        "hy" -> "armn"
        "zh" -> "hant"
        "ja" -> "jpan"
        "ko" -> "kore"
        else -> "latn"
    }

    private fun scriptOf(cp: Int): String = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.LATIN -> "latn"
        Character.UnicodeScript.CYRILLIC -> "cyrl"
        Character.UnicodeScript.GREEK -> "grek"
        Character.UnicodeScript.ARABIC -> "arab"
        Character.UnicodeScript.HEBREW -> "hebr"
        Character.UnicodeScript.DEVANAGARI -> "deva"
        Character.UnicodeScript.BENGALI -> "beng"
        Character.UnicodeScript.TAMIL -> "taml"
        Character.UnicodeScript.TELUGU -> "telu"
        Character.UnicodeScript.KANNADA -> "knda"
        Character.UnicodeScript.GUJARATI -> "gujr"
        Character.UnicodeScript.GURMUKHI -> "guru"
        Character.UnicodeScript.THAI -> "thai"
        Character.UnicodeScript.GEORGIAN -> "geor"
        Character.UnicodeScript.ARMENIAN -> "armn"
        Character.UnicodeScript.HAN -> "hant"
        Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> "jpan"
        Character.UnicodeScript.HANGUL -> "kore"
        else -> ""
    }

    private val letters = mapOf(
        "es" to "ñ¿¡", "ca" to "àèéíïóòúüç", "fr" to "àâæçëîïôùûœ", "de" to "äöüß",
        "it" to "àèéìíîòóùú", "pt" to "ãõâêôàç", "pl" to "ąćęłńóśźż", "ro" to "ăâîșțşţ",
        "sv" to "åäö", "cs" to "ěščřžýůňťď", "da" to "æøå", "fi" to "äö", "hu" to "őű",
        "hr" to "čćšžđ", "sk" to "äčďľňôŕšťž", "no" to "æøå", "vi" to "ăâđêôơưấầẩẫậắằẳẵặếềểễệốồổỗộớờởỡợứừửữự",
        "tr" to "çğıİş", "et" to "õäöü", "lv" to "āčēģīķļņšūž", "lt" to "ąčęėįšųūž",
        "ru" to "ыэё", "uk" to "єіїґ", "bg" to "ъ", "sr" to "јљњћџ", "kk" to "әіңғүұқөһ",
        "fa" to "پچژگ", "ur" to "ٹڈڑںہھے"
    )

    private val stopwords = mapOf(
        "en" to listOf("the", "is", "are", "you", "please", "thank", "have", "would", "night", "room", "can", "we", "do", "my"),
        "es" to listOf("el", "la", "de", "que", "por", "una", "gracias", "favor", "noche", "habitación", "tiene", "para", "los", "las"),
        "ca" to listOf("i", "el", "la", "de", "que", "per", "una", "gràcies", "si", "us", "plau", "habitació", "nit"),
        "fr" to listOf("le", "la", "de", "je", "vous", "une", "merci", "chambre", "nuit", "pour", "est", "avoir", "s'il"),
        "de" to listOf("der", "die", "das", "ich", "sie", "ein", "danke", "zimmer", "nacht", "für", "ist", "haben", "bitte"),
        "it" to listOf("il", "la", "di", "che", "io", "una", "grazie", "camera", "notte", "per", "non", "sono", "prego"),
        "pt" to listOf("o", "a", "de", "que", "eu", "uma", "obrigado", "quarto", "noite", "para", "não", "você", "por"),
        "nl" to listOf("de", "het", "een", "ik", "u", "dank", "kamer", "nacht", "voor", "niet", "zijn", "alstublieft"),
        "pl" to listOf("i", "w", "nie", "to", "się", "na", "jest", "dziękuję", "pokój", "noc", "proszę", "czy"),
        "ro" to listOf("și", "de", "nu", "o", "este", "mulțumesc", "cameră", "noapte", "pentru", "vă", "rog"),
        "sv" to listOf("och", "att", "det", "inte", "är", "tack", "rum", "natt", "för", "jag", "en", "ett"),
        "cs" to listOf("a", "je", "se", "na", "to", "děkuji", "pokoj", "noc", "pro", "jsem", "prosím"),
        "da" to listOf("og", "at", "det", "ikke", "er", "tak", "værelse", "nat", "for", "jeg"),
        "fi" to listOf("ja", "on", "ei", "se", "kiitos", "huone", "yö", "minä", "ettei", "ole"),
        "hu" to listOf("és", "a", "az", "nem", "van", "köszönöm", "szoba", "éjszaka", "egy", "hogy"),
        "hr" to listOf("i", "je", "se", "na", "to", "hvala", "soba", "noć", "za", "nisam", "molim"),
        "sk" to listOf("a", "je", "sa", "na", "to", "ďakujem", "izba", "noc", "pre", "som", "prosím"),
        "no" to listOf("og", "at", "det", "ikke", "er", "takk", "rom", "natt", "for", "jeg"),
        "id" to listOf("dan", "yang", "tidak", "ini", "terima", "kasih", "kamar", "malam", "untuk", "saya", "ada"),
        "ms" to listOf("dan", "yang", "tidak", "ini", "terima", "kasih", "bilik", "malam", "untuk", "saya"),
        "tl" to listOf("at", "ang", "ng", "hindi", "salamat", "kwarto", "gabi", "para", "po", "ako"),
        "tr" to listOf("ve", "bir", "bu", "için", "teşekkür", "oda", "gece", "değil", "var", "mı"),
        "et" to listOf("ja", "on", "ei", "see", "aitäh", "tuba", "öö", "mina", "ole"),
        "lv" to listOf("un", "ir", "nav", "tas", "paldies", "istaba", "nakts", "es", "par"),
        "lt" to listOf("ir", "yra", "ne", "tai", "ačiū", "kambarys", "naktis", "aš"),
        "af" to listOf("en", "die", "is", "nie", "dankie", "kamer", "nag", "vir", "ek", "asseblief"),
        "vi" to listOf("và", "là", "có", "không", "cảm", "ơn", "phòng", "đêm", "cho", "tôi"),
        "ru" to listOf("и", "в", "не", "на", "что", "это", "спасибо", "номер", "ночь", "для", "я"),
        "uk" to listOf("і", "в", "не", "на", "що", "це", "дякую", "номер", "ніч", "для", "я"),
        "bg" to listOf("и", "в", "не", "на", "че", "това", "благодаря", "ста", "нощ", "за"),
        "sr" to listOf("и", "у", "не", "на", "да", "то", "хвала", "соба", "ноћ", "за"),
        "kk" to listOf("және", "бұл", "емес", "рахмет", "бөлме", "түн", "үшін", "мен"),
        "hi" to listOf("है", "और", "नहीं", "मैं", "धन्यवाद", "कमरा", "रात", "के", "मुझे", "कृपया"),
        "mr" to listOf("आहे", "आणि", "नाही", "मी", "धन्यवाद", "खोली", "रात्र", "मला", "कृपया"),
        "ne" to listOf("छ", "र", "छैन", "म", "धन्यवाद", "कोठा", "रात", "लागि", "कृपया"),
        "ar" to listOf("في", "من", "لا", "شكرا", "غرفة", "ليلة", "هذا", "أنا", "هل"),
        "fa" to listOf("در", "از", "نه", "متشکرم", "اتاق", "شب", "این", "من", "آیا"),
        "ur" to listOf("میں", "سے", "نہیں", "شکریہ", "کمرہ", "رات", "یہ", "کیا", "ہے")
    )

    private fun scriptBase(fam: String, scripts: Map<String, Int>): Int = when (fam) {
        "jpan" -> (scripts["jpan"] ?: 0) * 4
        "kore" -> (scripts["kore"] ?: 0) * 4
        "hant" -> if ((scripts["jpan"] ?: 0) == 0 && (scripts["kore"] ?: 0) == 0) (scripts["hant"] ?: 0) * 4 else 0
        "latn" -> scripts["latn"] ?: 0
        else -> (scripts[fam] ?: 0) * 4
    }

    /** Differentiating evidence for [code]: distinctive letters and stopwords, no script base. */
    private fun diffScore(text: String, code: String): Int {
        var s = 0
        val lower = " " + text.lowercase() + " "
        letters[code]?.let { chars -> s += lower.count { it in chars } * 3 }
        stopwords[code]?.let { words -> for (w in words) if (lower.contains(" $w ") || lower.contains(" $w,") || lower.contains(" $w.") || lower.contains(" $w?")) s += 4 }
        return s
    }

    /** Winning language of [text] between [a] and [b]; [marginOut] receives a [0,1] confidence
     * based on differentiating evidence only (script base is shared evidence for same-script pairs). */
    fun classify(text: String, a: String, b: String, marginOut: DoubleArray? = null): String {
        val scripts = HashMap<String, Int>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val sc = scriptOf(cp)
            if (sc.isNotEmpty()) scripts[sc] = (scripts[sc] ?: 0) + 1
            i += Character.charCount(cp)
        }
        val fa = family(a); val fb = family(b)
        val baseA = scriptBase(fa, scripts); val baseB = scriptBase(fb, scripts)
        val da = diffScore(text, a); val db = diffScore(text, b)
        marginOut?.let {
            it[0] = if (fa != fb) {
                val total = baseA + baseB
                if (total == 0) 0.0 else Math.abs(baseA - baseB).toDouble() / total
            } else Math.abs(da - db).toDouble() / (da + db + 1)
        }
        return if (baseA + da >= baseB + db) a else b
    }

    fun tokens(text: String): Set<String> = text.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.toSet()

    fun similarity(a: String, b: String): Double {
        val ta = tokens(a); val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        return ta.intersect(tb).size.toDouble() / ta.union(tb).size
    }
}

/** Pairs each human turn with the agent translation that follows it, assigns the speaker's
 * side from the detected language, and suppresses echoes of the app's own spoken output. */
internal class InterpreterCaptions(private val guest: String, private val staff: String) {
    private class Turn(val id: String) {
        var side = "guest"
        var from = ""
        var to = ""
        var original = ""
        var margin = 0.0
        var paired = false
    }
    @Volatile private var forced: String? = null
    private var lastSide: String? = null
    private val turns = LinkedHashMap<String, Turn>()
    private val dropped = HashSet<String>()
    private val pending = ArrayDeque<Turn>()
    private var agentKey: String? = null
    private var agentTurn: Turn? = null
    private val recentAgent = ArrayDeque<String>()

    private fun other(lang: String) = if (lang == guest) staff else guest

    /** Manual speaker fallback: one shared microphone carries no per-voice signal, so when the
     * receptionist knows who is about to speak (or language evidence keeps failing), they can pin
     * the side. "guest"/"staff" pin it; anything else returns to language detection. */
    fun forceSpeaker(side: String?) { forced = side }

    /** Consumes one raw caption event from the transport; returns the events to show. */
    @Synchronized fun accept(caption: JSONObject): List<JSONObject> {
        val id = caption.optString("utterance_id")
        val side = caption.optString("side")
        val text = caption.optString("text")
        val final = caption.optBoolean("final")
        if (id.isEmpty()) return emptyList()
        return when (side) {
            "assistant" -> agent(id, text, final)
            "human" -> human(id, text, final)
            else -> emptyList()
        }
    }

    private fun human(id: String, text: String, final: Boolean): List<JSONObject> {
        if (id in dropped) return emptyList()
        var turn = turns[id]
        if (turn == null) {
            // Echo of our own spoken translation: near-identical to a very recent agent output.
            if (text.length >= 24 && recentAgent.any { LanguageId.similarity(text, it) >= 0.75 }) {
                dropped.add(id)
                return emptyList()
            }
            turn = Turn(id)
            turns[id] = turn
        }
        val margin = DoubleArray(1)
        val classified = LanguageId.classify(text, guest, staff, margin)
        val pinned = forced
        // The pin says WHO is speaking; the language still comes from the text. Only when language
        // evidence is near zero does the pin (or, unpinned, the previous speaker's alternation -
        // conversation turns alternate) decide the language too.
        val heard = when {
            pinned != null && margin[0] >= 0.15 -> classified
            pinned == "staff" -> staff
            pinned == "guest" -> guest
            margin[0] < 0.15 && lastSide != null -> if (lastSide == "staff") guest else staff
            else -> classified
        }
        if (pinned != null) margin[0] = 1.0
        if (!turn.paired || final) {
            turn.side = if (pinned != null) pinned else if (heard == staff) "staff" else "guest"
            turn.from = heard
            turn.to = other(heard)
            turn.margin = margin[0]
        }
        if (final) lastSide = turn.side
        turn.original = text
        val events = mutableListOf(UnpairedCaptions.caption(id, turn.side, turn.from, turn.to, "original", "replace", text, final))
        if (final && !turn.paired && !pending.contains(turn)) pending.addLast(turn)
        return events
    }

    private fun agent(id: String, text: String, final: Boolean): List<JSONObject> {
        if (agentKey != id) {
            agentKey = id
            agentTurn = if (pending.isNotEmpty()) pending.removeFirst().also { it.paired = true } else null
        }
        val turn = agentTurn
        if (turn == null) {
            // The agent spoke with no human turn to translate: surface it honestly, never as a fake bubble pair.
            return listOf(UnpairedCaptions.caption(id, "assistant", "", "", "text", "replace", text, final))
        }
        val events = ArrayList<JSONObject>(2)
        if (final) {
            val outMargin = DoubleArray(1)
            val spoken = LanguageId.classify(text, guest, staff, outMargin)
            // Consistency check: the agent must speak the language the speaker does NOT.
            // If it plainly spoke the language we thought was the input, the input side was wrong: flip it.
            if (spoken == turn.from && outMargin[0] >= 0.4 && turn.margin < 0.4) {
                turn.side = if (turn.side == "staff") "guest" else "staff"
                turn.from = other(turn.from)
                turn.to = other(turn.to)
                events.add(UnpairedCaptions.caption(turn.id, turn.side, turn.from, turn.to, "original", "replace", turn.original, true))
            }
            recentAgent.addLast(text)
            while (recentAgent.size > 5) recentAgent.removeFirst()
        }
        events.add(UnpairedCaptions.caption(turn.id, turn.side, turn.from, turn.to, "text", "replace", text, final))
        return events
    }
}
