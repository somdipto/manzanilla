package com.agentdeck.hotel

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LanguageIdTest {
    private fun winner(text: String, a: String, b: String) = LanguageId.classify(text, a, b)

    @Test fun scriptPairsAreDecisive() {
        assertEquals("ar", winner("مساء الخير، هل لديكم غرفة؟", "en", "ar"))
        assertEquals("en", winner("Good evening, do you have a room?", "en", "ar"))
        assertEquals("ja", winner("今晩は、空き部屋はありますか", "ja", "es"))
        assertEquals("zh", winner("晚上好，请问还有空房吗", "zh", "en"))
        assertEquals("ko", winner("안녕하세요, 빈 방이 있나요", "ko", "en"))
        assertEquals("hi", winner("नमस्ते, क्या आपके पास कोई कमरा खाली है", "hi", "en"))
        assertEquals("ru", winner("Добрый вечер, у вас есть свободный номер?", "ru", "en"))
        assertEquals("uk", winner("Добрий вечір, у вас є вільний номер?", "ru", "uk"))
    }

    @Test fun latinPairsUseLettersAndStopwords() {
        assertEquals("es", winner("Hola, buenas noches. ¿Tiene una habitación libre para esta noche?", "es", "en"))
        assertEquals("en", winner("Hello, good evening. Do you have a room free for tonight?", "es", "en"))
        assertEquals("fr", winner("Bonsoir, avez-vous une chambre pour ce soir s'il vous plaît", "fr", "de"))
        assertEquals("de", winner("Guten Abend, haben Sie ein Zimmer für heute Nacht bitte", "fr", "de"))
        assertEquals("pt", winner("Boa noite, você tem um quarto para esta noite?", "pt", "it"))
        assertEquals("it", winner("Buonasera, avete una camera per questa notte?", "pt", "it"))
        assertEquals("tr", winner("İyi akşamlar, bu gece için boş odanız var mı?", "tr", "en"))
        assertEquals("vi", winner("Chào buổi tối, bạn có phòng trống đêm nay không?", "vi", "en"))
    }

    @Test fun sameScriptCyrillicUsesDistinctiveLetters() {
        assertEquals("uk", winner("Дякую, це дуже добре", "ru", "uk"))
        assertEquals("ru", winner("Спасибо, это очень хорошо", "ru", "uk"))
    }

    @Test fun similarityMatchesNearIdenticalEchoes() {
        val a = "Hello, good evening. Do you have a room free for tonight?"
        val b = "Hello, good evening, do you have a room free for tonight"
        assertTrue(LanguageId.similarity(a, b) >= 0.75)
        assertTrue(LanguageId.similarity(a, "The pool opens at eight in the morning.") < 0.75)
    }
}

class InterpreterCaptionsTest {
    private fun raw(side: String, id: String, text: String, final: Boolean) =
        UnpairedCaptions.caption(id, side, "", "", if (side == "assistant") "text" else "original", "replace", text, final)

    @Test fun guestTurnPairsWithTranslationInOneBubble() {
        val c = InterpreterCaptions(guest = "es", staff = "en")
        val e1 = c.accept(raw("human", "u1", "Hola, ¿tiene una habitación libre para esta noche?", true))
        assertEquals(1, e1.size)
        assertEquals("guest", e1[0].getString("side"))
        assertEquals("es", e1[0].getString("from"))
        assertEquals("en", e1[0].getString("to"))
        val e2 = c.accept(raw("assistant", "a1", "Hello, do you have a room free for tonight?", true))
        val paired = e2.last()
        assertEquals("u1", paired.getString("utterance_id"))
        assertEquals("text", paired.getString("field"))
        assertEquals("guest", paired.getString("side"))
    }

    @Test fun staffTurnTakesReceptionSide() {
        val c = InterpreterCaptions(guest = "es", staff = "en")
        val e1 = c.accept(raw("human", "u1", "Yes, we have a double room available for you tonight.", true))
        assertEquals("staff", e1[0].getString("side"))
        assertEquals("en", e1[0].getString("from"))
        val e2 = c.accept(raw("assistant", "a1", "Sí, tenemos una habitación doble disponible para esta noche.", true))
        assertEquals("u1", e2.last().getString("utterance_id"))
        assertEquals("staff", e2.last().getString("side"))
    }

    @Test fun ownOutputEchoNeverBecomesABubble() {
        val c = InterpreterCaptions(guest = "es", staff = "en")
        c.accept(raw("human", "u1", "Hola, ¿tiene una habitación libre para esta noche?", true))
        c.accept(raw("assistant", "a1", "Hello, do you have a room free for tonight?", true))
        // The microphone hears the app's own voice back; ASR returns it as new human input.
        assertTrue(c.accept(raw("human", "u2", "Hello, do you have a room free for tonight", true)).isEmpty())
        assertTrue(c.accept(raw("human", "u2", "Hello, do you have a room free for tonight", false)).isEmpty())
    }

    @Test fun agentSpeechWithoutHumanTurnIsHonestAssistantBubble() {
        val c = InterpreterCaptions(guest = "es", staff = "en")
        val events = c.accept(raw("assistant", "a9", "How can I help you today?", true))
        assertEquals(1, events.size)
        assertEquals("assistant", events[0].getString("side"))
        assertEquals("a9", events[0].getString("utterance_id"))
    }

    @Test fun contradictedLowMarginSideFlips() {
        val c = InterpreterCaptions(guest = "es", staff = "en")
        // Very short ambiguous input: margin is low, classified as guest/es by tie-break.
        val e1 = c.accept(raw("human", "u1", "No no no", true))
        val side = e1[0].getString("side")
        // The agent's reply is plainly Spanish - the language we thought the speaker used.
        // A Spanish reply to a "Spanish" utterance means the speaker was actually staff (English): flip.
        val e2 = c.accept(raw("assistant", "a1", "Sí, por supuesto, déjeme comprobarlo ahora mismo para usted.", true))
        if (side == "guest") {
            val correction = e2.firstOrNull { it.getString("field") == "original" }
            assertNotNull(correction)
            assertEquals("staff", correction!!.getString("side"))
        }
        assertEquals("u1", e2.last().getString("utterance_id"))
    }

    @Test fun unpairedHumanTurnKeepsOriginalOnly() {
        val c = InterpreterCaptions(guest = "es", staff = "en")
        c.accept(raw("human", "u1", "Hola, ¿tiene wifi la habitación?", true))
        val events = c.accept(raw("human", "u2", "¿Y el desayuno a qué hora empieza?", true))
        assertEquals("u2", events[0].getString("utterance_id"))
        assertEquals("original", events[0].getString("field"))
    }

    @Test fun manualSpeakerPinOverridesLanguageEvidence() {
        val c = InterpreterCaptions(guest = "es", staff = "en")
        c.forceSpeaker("staff")
        val e1 = c.accept(raw("human", "u1", "Hola, ¿tiene una habitación libre para esta noche?", true))
        assertEquals("staff", e1[0].getString("side"))
        assertEquals("es", e1[0].getString("from"))
        // A pinned side is deliberate: the agent cross-check must not flip it back.
        val e2 = c.accept(raw("assistant", "a1", "Hola, ¿tiene una habitación libre para esta noche?", true))
        assertEquals("staff", e2.last().getString("side"))
    }

    @Test fun clearingPinReturnsToLanguageDetection() {
        val c = InterpreterCaptions(guest = "es", staff = "en")
        c.forceSpeaker("staff")
        c.forceSpeaker(null)
        val e1 = c.accept(raw("human", "u1", "Hola, ¿tiene una habitación libre para esta noche?", true))
        assertEquals("guest", e1[0].getString("side"))
    }

    @Test fun ambiguousTurnAlternatesFromPreviousSpeaker() {
        val c = InterpreterCaptions(guest = "es", staff = "en")
        val e1 = c.accept(raw("human", "u1", "Hola, ¿tiene una habitación libre para esta noche?", true))
        assertEquals("guest", e1[0].getString("side"))
        // No letters, no language evidence: the next speaker is usually the other person.
        val e2 = c.accept(raw("human", "u2", "205", true))
        assertEquals("staff", e2[0].getString("side"))
        assertEquals("en", e2[0].getString("from"))
    }
}
