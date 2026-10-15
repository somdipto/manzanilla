package com.agentdeck.hotel

import org.json.JSONObject

/** Gemini Live has no documented input/output association ID or input final boundary.
 * Never associate an input fragment with a model turn. Output chunks alone share a model-turn key.
 * https://ai.google.dev/api/live#BidiGenerateContentServerContent
 */
internal class TranslationCaptions(
    private val prefix: String,
    private val guest: String,
    private val staff: String,
    private val target: String,
    private val publishInput: Boolean
) {
    private var input = 0L
    private var output = 0L
    private val text = StringBuilder()

    @Synchronized fun accept(content: JSONObject): List<JSONObject> {
        val captions = ArrayList<JSONObject>(3)
        if (publishInput) content.optJSONObject("inputTranscription")?.let { transcription ->
            val fragment = transcription.optString("text")
            if (fragment.isNotEmpty()) {
                val language = transcription.optString("languageCode")
                val side = if (language.substringBefore('-') == staff.substringBefore('-')) "staff" else "guest"
                val destination = when (language.substringBefore('-')) {
                    guest.substringBefore('-') -> staff
                    staff.substringBefore('-') -> guest
                    else -> ""
                }
                captions.add(UnpairedCaptions.caption("$prefix:input:${input++}", side, language, destination, "original", "replace", fragment, false))
            }
        }
        content.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { fragment ->
            text.append(fragment)
            captions.add(UnpairedCaptions.caption("$prefix:output:$output", "assistant", "", target, "text", "append", fragment, false))
        }
        // generationComplete can precede the last audio/transcript chunk; only turnComplete closes a turn.
        if (content.optBoolean("turnComplete") || content.optBoolean("interrupted")) {
            if (text.isNotEmpty()) captions.add(UnpairedCaptions.caption("$prefix:output:$output", "assistant", "", target, "text", "replace", text.toString(), true))
            text.setLength(0)
            output++
        }
        return captions
    }
}

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
