package com.agentdeck.hotel

import org.json.JSONObject

/** Arrival order is captured before asynchronous stream reading, so late interim readers cannot
 * replace a newer/final revision. Keys are speaker+segment, never guessed translation pairs.
 */
internal class LiveKitCaptions {
    internal data class Stream(val key: String, val revision: Long, val identity: String, val segment: String,
                               val streamId: String, val original: Boolean, val final: Boolean)
    private data class Revision(val number: Long, val final: Boolean)
    private val revisions = HashMap<String, Revision>()

    @Synchronized fun begin(identity: String, segment: String, streamId: String, original: Boolean, final: Boolean): Stream? {
        val key = "${identity.length}:$identity:${segment.ifBlank { streamId }}"
        val old = revisions[key]
        if (old?.final == true && !final) return null
        val number = (old?.number ?: 0) + 1
        revisions[key] = Revision(number, final)
        return Stream(key, number, identity, segment, streamId, original, final)
    }
    @Synchronized fun replace(stream: Stream, text: String, complete: Boolean): JSONObject? {
        if (revisions[stream.key]?.number != stream.revision) return null
        return UnpairedCaptions.liveKit(stream.identity, stream.segment, stream.streamId, stream.original, text, complete && stream.final)
    }
}
