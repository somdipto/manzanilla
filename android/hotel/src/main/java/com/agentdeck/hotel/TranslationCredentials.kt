package com.agentdeck.hotel

import android.content.Context
import org.json.JSONObject

/** One encrypted atomic record: a failed save cannot mix credentials from different configurations. */
class TranslationCredentials(context: Context) {
    class Configuration(val fishKey: String, val translationKey: String, val voiceId: String)

    private val store = SecretStore(context, "translation_credentials")
    private val legacyFish = SecretStore(context, "fish")

    fun load(): Configuration? {
        val encoded = store.load() ?: return null
        return runCatching {
            val record = JSONObject(encoded)
            val fish = record.getString("fish_key")
            val translation = record.getString("translation_key")
            val voice = record.getString("voice_id")
            require(valid(fish) && valid(translation) && valid(voice))
            Configuration(fish, translation, voice)
        }.getOrNull()
    }

    /** Only a user-saved legacy key is eligible; compiled keys are never read. */
    fun savedFishKey(): String? = load()?.fishKey ?: legacyFish.load()?.takeIf(::valid)

    fun save(fishKey: String, translationKey: String, voiceId: String): Boolean {
        if (!valid(fishKey) || !valid(translationKey) || !valid(voiceId)) return false
        return store.save(JSONObject().put("fish_key", fishKey).put("translation_key", translationKey)
            .put("voice_id", voiceId).toString())
    }

    private fun valid(value: String): Boolean = value.isNotBlank() && value.length <= 4096 && value.none { it.isWhitespace() || it.isISOControl() }
}
