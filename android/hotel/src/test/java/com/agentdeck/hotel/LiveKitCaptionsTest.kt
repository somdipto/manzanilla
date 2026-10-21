package com.agentdeck.hotel

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LiveKitCaptionsTest {
    private class Feed {
        val streams = LiveKitCaptions()
        val captions = InterpreterCaptions()

        fun begin(segment: String, original: Boolean = true, final: Boolean = false,
                  identity: String = if (original) "mic" else "agent", stream: String = segment): LiveKitCaptions.Stream? {
            val revision = streams.begin(identity, segment, stream, original, final) ?: return null
            return revision
        }

        fun read(stream: LiveKitCaptions.Stream, text: String, complete: Boolean): List<JSONObject> =
            streams.replace(stream, text, complete)?.let(captions::accept) ?: emptyList()
    }

    private fun assertCaption(events: List<JSONObject>, id: String, side: String, field: String,
                              text: String, final: Boolean) {
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(id, event.getString("utterance_id"))
        assertEquals(side, event.getString("side"))
        assertEquals(field, event.getString("field"))
        assertEquals(text, event.getString("text"))
        assertEquals(final, event.getBoolean("final"))
        assertEquals("unlinked", event.getString("association"))
        assertEquals("", event.getString("from"))
        assertEquals("", event.getString("to"))
        assertEquals(if (side == "guest" || side == "staff") "selected" else "unknown",
            event.getString("speaker_attribution"))
    }

    @Test fun newerRevisionWinsEvenWhenOlderReaderCompletesLater() {
        val feed = Feed()
        val old = feed.begin("u1")!!
        assertCaption(feed.read(old, "Room", false), "fish:3:mic:u1", "human", "original", "Room", false)
        val newer = feed.begin("u1", stream = "new-stream")!!
        assertCaption(feed.read(newer, "Room 205", true), "fish:3:mic:u1", "human", "original", "Room 205", false)
        assertTrue(feed.read(old, "Room two", true).isEmpty())
    }

    @Test fun finalAnnouncementFencesBothInFlightAndNewLateInterims() {
        val feed = Feed()
        val old = feed.begin("u1")!!
        val final = feed.begin("u1", final = true)!!
        assertTrue(feed.read(old, "Room", true).isEmpty())
        assertNull(feed.begin("u1"))
        assertCaption(feed.read(final, "Room 205", false), "fish:3:mic:u1", "human", "original", "Room 205", false)
        assertCaption(feed.read(final, "Room 205.", true), "fish:3:mic:u1", "human", "original", "Room 205.", true)
        assertNull(feed.begin("u1"))
        assertNull(feed.streams.replace(final, "late chunk", false))
        assertNull(feed.streams.replace(final, "duplicate completion", true))
    }

    @Test fun newerFinalWinsOverDelayedOlderFinalCompletion() {
        val feed = Feed()
        val older = feed.begin("u1", final = true, stream = "old")!!
        val newer = feed.begin("u1", final = true, stream = "new")!!
        assertCaption(feed.read(newer, "Room 205.", true), "fish:3:mic:u1", "human", "original", "Room 205.", true)
        assertTrue(feed.read(older, "Room two.", true).isEmpty())
    }

    @Test fun duplicateFinalStreamDoesNotResurfacePartialOrDuplicateFinal() {
        val feed = Feed()
        val final = feed.begin("u1", final = true)!!
        assertCaption(feed.read(final, "Room 205.", true), "fish:3:mic:u1", "human", "original", "Room 205.", true)
        val duplicate = feed.begin("u1", final = true, stream = "duplicate")!!
        assertNull(feed.streams.replace(duplicate, "Room", false))
        assertTrue(feed.read(duplicate, "Room 205.", true).isEmpty())
        val corrected = feed.begin("u1", final = true, stream = "corrected")!!
        assertNull(feed.streams.replace(corrected, "Room 2", false))
        assertCaption(feed.read(corrected, "Room 206.", true), "fish:3:mic:u1", "human", "original", "Room 206.", true)
    }

    @Test fun humanStreamsStayProviderReportedAcrossRevisions() {
        val feed = Feed()
        val first = feed.begin("u1")!!
        val second = feed.begin("u2", final = true)!!
        val firstFinal = feed.begin("u1", final = true)!!
        assertCaption(feed.read(second, "Hola.", true), "fish:3:mic:u2", "human", "original", "Hola.", true)
        assertCaption(feed.read(firstFinal, "Hello.", true), "fish:3:mic:u1", "human", "original", "Hello.", true)
        assertTrue(feed.read(first, "Hello", true).isEmpty())
        val third = feed.begin("u3", final = true)!!
        assertCaption(feed.read(third, "205", true), "fish:3:mic:u3", "human", "original", "205", true)
    }

    @Test fun humanStreamAnnouncedFinalStillReadsAsHuman() {
        val feed = Feed()
        val stream = feed.begin("u1", final = true)!!
        assertCaption(feed.read(stream, "205", true), "fish:3:mic:u1", "human", "original", "205", true)
    }

    @Test fun sameSegmentNameOnDifferentParticipantsNeverPairsOrSharesFinality() {
        val feed = Feed()
        val human = feed.begin("shared", final = true)!!
        assertCaption(feed.read(human, "Hola.", true), "fish:3:mic:shared", "human", "original", "Hola.", true)
        val assistant = feed.begin("shared", original = false)!!
        assertCaption(feed.read(assistant, "Welcome", false), "fish:5:agent:shared", "assistant", "text", "Welcome", false)
        val assistantFinal = feed.begin("shared", original = false, final = true)!!
        assertCaption(feed.read(assistantFinal, "Welcome.", true), "fish:5:agent:shared", "assistant", "text", "Welcome.", true)
    }

    @Test fun outputSegmentRevisionsCanCompleteOutOfOrderWithoutChangingHumanEvents() {
        val feed = Feed()
        val human = feed.begin("u1", final = true)!!
        assertCaption(feed.read(human, "Dos noches.", true), "fish:3:mic:u1", "human", "original", "Dos noches.", true)
        val first = feed.begin("a1", original = false, final = true)!!
        val second = feed.begin("a2", original = false, final = true)!!
        assertCaption(feed.read(second, "nights.", true), "fish:5:agent:a2", "assistant", "text", "nights.", true)
        assertCaption(feed.read(first, "Two", true), "fish:5:agent:a1", "assistant", "text", "Two", true)
    }

    @Test fun interruptedSegmentDoesNotBlockIndependentLaterOutput() {
        val feed = Feed()
        val interrupted = feed.begin("a1", original = false, final = true)!!
        assertCaption(feed.read(interrupted, "I would", false), "fish:5:agent:a1", "assistant", "text", "I would", false)
        val next = feed.begin("a2", original = false, final = true)!!
        assertCaption(feed.read(next, "Two rooms.", true), "fish:5:agent:a2", "assistant", "text", "Two rooms.", true)
    }

    @Test fun missingSegmentIdUsesIndependentStreamIdentityWithoutGuessingPairs() {
        val feed = Feed()
        val first = feed.begin("", final = true, stream = "stream-one")!!
        val second = feed.begin("", final = true, stream = "stream-two")!!
        assertCaption(feed.read(first, "205", true), "fish:3:mic:stream-one", "human", "original", "205", true)
        assertCaption(feed.read(second, "205", true), "fish:3:mic:stream-two", "human", "original", "205", true)
    }

    @Test fun participantNamespaceDoesNotCollideWhenIdentitiesContainColons() {
        val feed = Feed()
        val first = feed.begin("b:c", identity = "a", final = true)!!
        val second = feed.begin("c", identity = "a:b", final = true)!!
        assertNotEquals(first.utteranceId, second.utteranceId)
        assertCaption(feed.read(first, "First.", true), "fish:1:a:b:c", "human", "original", "First.", true)
        assertCaption(feed.read(second, "Second.", true), "fish:3:a:b:c", "human", "original", "Second.", true)
    }

    @Test fun finalizedSegmentIsRetainedForTheWholeCappedRun() {
        val feed = Feed()
        val first = feed.begin("first", final = true)!!
        assertCaption(feed.read(first, "First.", true), "fish:3:mic:first", "human", "original", "First.", true)
        repeat(10000) {
            val stream = feed.begin("u$it", final = true)!!
            feed.read(stream, "205", true)
        }
        assertNull(feed.begin("first"))
        assertNull(feed.streams.replace(first, "late", false))
    }

    @Test fun newRunHasIndependentRevisionHistory() {
        val old = Feed()
        val final = old.begin("u1", final = true)!!
        old.read(final, "Old final.", true)
        val next = Feed()
        val interim = next.begin("u1")!!
        assertCaption(next.read(interim, "New interim", false), "fish:3:mic:u1", "human", "original", "New interim", false)
    }
}
