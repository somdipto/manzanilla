package com.agentdeck.hotel

import android.content.Context
import org.json.JSONObject

/** Offline interface copy. Preload during activity setup, never from an audio callback. */
object UiText {
    private const val UNAVAILABLE = "This service is unavailable. Please ask hotel staff."
    private val placeholder = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)\\}")
    @Volatile private var messages: JSONObject? = null
    private var directions: Map<String, String> = emptyMap()

    /** Call before constructing voice sessions; the packaged catalogue is parsed once. */
    fun preload(context: Context) {
        if (messages != null) return
        synchronized(this) {
            if (messages != null) return
            val catalogue = context.applicationContext.assets.open("reception-locales.json").bufferedReader(Charsets.UTF_8).use {
                JSONObject(it.readText())
            }
            check(catalogue.getInt("schema") == 1) { "Unsupported interface catalogue schema" }
            val languages = catalogue.getJSONArray("languages")
            val loadedDirections = HashMap<String, String>(languages.length())
            for (index in 0 until languages.length()) {
                val language = languages.getJSONObject(index)
                loadedDirections[language.getString("code")] = language.getString("direction")
            }
            // Publish direction data before the volatile ready marker.
            directions = loadedDirections
            messages = catalogue.getJSONObject("messages")
        }
    }

    fun direction(context: Context, language: String): String {
        preload(context)
        val code = if (DeskLanguages.has(language)) language else "es"
        return directions.getValue(code)
    }

    fun text(context: Context, language: String, message: String, values: Map<String, String> = emptyMap()): String {
        preload(context)
        val code = if (DeskLanguages.has(language)) language else "es"
        val catalogue = checkNotNull(messages)
        val fallback = catalogue.getJSONObject(UNAVAILABLE).getString(code)
        val translated = catalogue.optJSONObject(message)?.opt(code) as? String ?: return fallback
        if (translated.isBlank()) return fallback
        val names = placeholder.findAll(translated).map { it.groupValues[1] }.toSet()
        if (names != values.keys) return fallback
        val literal = placeholder.replace(translated, "")
        if ('{' in literal || '}' in literal) return fallback
        // Regex replacement by lambda preserves dollar signs and backslashes in values.
        return placeholder.replace(translated) { values.getValue(it.groupValues[1]) }
    }
}
