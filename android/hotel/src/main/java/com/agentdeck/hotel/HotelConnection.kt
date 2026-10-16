package com.agentdeck.hotel

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** URL and device token are encrypted and replaced together; never embedded in an APK. */
class HotelConnection(context: Context) {
    data class Credentials(val base: String, val token: String)
    private val store = SecretStore(context, "hotel_connection")
    var errorMessage: String? = null
        private set

    fun load(): Credentials? {
        val value = store.load() ?: return null
        return try {
            val json = JSONObject(value)
            validated(json.getString("base"), json.getString("token"))
        } catch (_: Exception) { null }
    }

    fun save(base: String, token: String): Boolean {
        val credentials = validated(base, token)
        if (credentials == null) {
            errorMessage = "Enter an HTTPS hotel service URL and its device credential. No login, query or fragment is allowed in the URL."
            return false
        }
        val saved = store.save(JSONObject().put("base", credentials.base).put("token", credentials.token).toString())
        errorMessage = if (saved) null else store.errorMessage
        return saved
    }

    private fun validated(base: String, token: String): Credentials? {
        val url = base.trim().toHttpUrlOrNull() ?: return null
        val credential = token.trim()
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null || credential.length !in 32..4096 ||
            credential.any { it.isWhitespace() || it.isISOControl() }) return null
        return Credentials(url.toString().trimEnd('/'), credential)
    }
}
