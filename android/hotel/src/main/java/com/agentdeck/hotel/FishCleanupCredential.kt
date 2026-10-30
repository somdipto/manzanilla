package com.agentdeck.hotel

import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

/** The encrypted record is cleanup-only authority for one exact paid reservation. */
internal object FishCleanupCredential {
    const val STORE = "fish_cleanup"

    fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun encode(reservation: String, expectedHash: String?, credential: String): String? {
        if (reservation.isBlank() || credential.isBlank() || hash(credential) != expectedHash) return null
        return JSONObject().put("reservation", reservation).put("hash", expectedHash)
            .put("credential", credential).toString()
    }

    fun resolve(record: String?, reservation: String, expectedHash: String?): String? = try {
        val saved = JSONObject(record ?: "")
        saved.getString("credential").takeIf {
            saved.getString("reservation") == reservation && saved.getString("hash") == expectedHash &&
                it.isNotBlank() && hash(it) == expectedHash
        }
    } catch (_: Exception) { null }

    fun lookup(reservation: String): String =
        "https://api.fish.audio/v1/agent/sessions?end_user_id=${URLEncoder.encode(reservation, "UTF-8")}&status=pending,active,unknown,completed,failed&page_size=2"

    fun isDirect(reservation: String, url: String?, allowLookup: Boolean): Boolean =
        url != null && (url.matches(Regex("https://api\\.fish\\.audio/v1/agent/sessions/[A-Za-z0-9_-]+/end")) ||
            (allowLookup && url == lookup(reservation)))
}
