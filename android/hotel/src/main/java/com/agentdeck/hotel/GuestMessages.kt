package com.agentdeck.hotel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Guest messages the receptionist confirms. This is a local pad, not a delivery path:
 * nothing here reaches the staff, the property system or a phone. The copy in every
 * event says so, and the saved note is only readable on this tablet.
 */
class GuestMessages(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("guest_messages", Context.MODE_PRIVATE)
    private var pending: JSONObject? = null

    /** Builds the draft for the last thing the guest said and keeps it for `save`. */
    fun draft(message: String): JSONObject {
        val text = message.trim()
        val draft = JSONObject()
            .put("type", "reception_event")
            .put("phase", "confirming")
            .put("draft_id", "draft-${System.currentTimeMillis()}")
            .put("guest_name", "")
            .put("room_reference", "")
            .put("message", text)
            .put("text", if (text.isBlank()) "Nothing has been said yet in this conversation." else "Confirm to keep this note on this tablet. Staff are not notified.")
        pending = draft
        return draft
    }

    /** Keeps the confirmed draft on this device. Returns false only when storage refuses it. */
    fun save(draftId: String): Boolean {
        val draft = pending ?: return false
        if (draft.optString("draft_id") != draftId) return false
        val list = JSONArray(prefs.getString("items", "[]"))
        val next = JSONArray()
        next.put(draft)
        for (i in 0 until minOf(list.length(), 49)) next.put(list.getJSONObject(i))
        val ok = prefs.edit().putString("items", next.toString()).commit()
        if (ok) pending = null
        return ok
    }

    fun discard() { pending = null }
}
