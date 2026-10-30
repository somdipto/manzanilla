package com.agentdeck.hotel

import org.json.JSONObject

/** Retains each canonical delta before speaking stable clauses. EOF is never successful completion. */
internal class TranslationStream(
    private val received: (String) -> Unit,
    private val clause: (String) -> Unit
) {
    private val pending = StringBuilder()
    private var stopped = false
    private var done = false
    private var count = 0
    fun accept(data: String) {
        check(!done)
        if (data == "[DONE]") {
            check(stopped)
            done = true
            return
        }
        val event = JSONObject(data)
        check(!event.has("error"))
        val choices = event.getJSONArray("choices")
        if (choices.length() == 0) return
        check(choices.length() == 1)
        val choice = choices.getJSONObject(0)
        val delta = choice.optJSONObject("delta") ?: JSONObject()
        check(!delta.has("tool_calls") && !delta.has("function_call") && !delta.has("refusal"))
        val content = delta.opt("content")
        if (content != null && content != JSONObject.NULL) {
            check(content is String && !stopped)
            count += content.length
            check(count <= 16000)
            val text = TranslationRecords.canonical(content)
            received(text)
            pending.append(text)
            // Delay punctuation until the following boundary is visible; do not speak tokens/drafts.
            var boundary = -1
            for (index in 1 until pending.length) {
                if (pending[index - 1] in ".!?。！？;؛\n" && (pending[index].isWhitespace() || pending[index - 1] in "。！？\n")) boundary = index
            }
            if (boundary > 0 && (0 until boundary).any { !pending[it].isWhitespace() }) emit(boundary)
        }
        val reason = choice.opt("finish_reason")
        if (reason != null && reason != JSONObject.NULL) {
            check(!stopped && reason == "stop")
            stopped = true
        }
    }
    fun finish() {
        check(done && stopped && count > 0)
        if (pending.isNotEmpty()) emit(pending.length)
    }
    private fun emit(end: Int) {
        val text = pending.substring(0, end)
        pending.delete(0, end)
        clause(text)
    }
}
