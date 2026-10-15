package com.agentdeck.hotel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Guest messages the receptionist confirms. This is a local pad, not a delivery path:
 * nothing here reaches the staff, the property system or a phone. The copy in every
 * event says so, and the saved note is only readable on this tablet.
 */
class GuestMessages(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("guest_messages", Context.MODE_PRIVATE)
    private var pending: JSONObject? = null
    private var savedId: String? = null

    /** Builds an explicit review of recent microphone text; never infers a speaker or delivery. */
    fun draft(message: String): JSONObject {
        val text = message.trim()
        if (text.isBlank()) {
            pending = null
            return JSONObject().put("type", "notice").put("text", "Say your message first, then choose Message to review it.")
        }
        val draft = JSONObject()
            .put("type", "reception_event")
            .put("phase", "confirming")
            .put("draft_id", UUID.randomUUID().toString())
            .put("guest_name", "")
            .put("room_reference", "")
            .put("message", text)
            .put("text", "Confirm to keep this note on this tablet. Staff are not notified.")
        pending = draft
        return draft
    }

    /** Keeps the confirmed draft on this device. Returns false only when storage refuses it. */
    fun save(draftId: String): Boolean {
        if (draftId == savedId) return true
        val draft = pending ?: return false
        if (draft.optString("draft_id") != draftId || draft.optString("message").isBlank()) return false
        val list = try { JSONArray(prefs.getString("items", "[]")) } catch (_: Exception) { return false }
        val next = JSONArray()
        next.put(draft)
        for (i in 0 until minOf(list.length(), 49)) next.put(list.getJSONObject(i))
        val ok = prefs.edit().putString("items", next.toString()).commit()
        if (ok) { pending = null; savedId = draftId }
        return ok
    }

    fun discard() { pending = null; savedId = null }
}
