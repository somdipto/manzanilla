package com.agentdeck.hotel

import android.content.Context

/** Route preference only; credentials remain in their existing encrypted stores. */
internal class TranslationSettings(context: Context) {
    private val preferences = context.getSharedPreferences("deck", Context.MODE_PRIVATE)

    val directFishSelected: Boolean get() = preferences.getBoolean("translation_direct_fish", false)

    fun selectDirectFish(): Boolean = preferences.edit().putBoolean("translation_direct_fish", true).commit()
    fun selectHotelConnection(): Boolean = preferences.edit().putBoolean("translation_direct_fish", false).commit()
}
