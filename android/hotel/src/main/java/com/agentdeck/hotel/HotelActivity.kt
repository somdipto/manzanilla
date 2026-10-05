package com.agentdeck.hotel

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.FrameLayout
import java.util.Locale
import com.agentdeck.ui.ReceptionView
import org.json.JSONObject

/** Manzanilla reception: the packaged Reception UI, full screen. No setup, no enrollment. */
class HotelActivity : Activity() {
    private var reception: ReceptionView? = null
    private var night = false
    private val updater by lazy { Updater(this) { } }
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val langs = setOf("en", "es", "ja", "zh", "fr", "de", "it", "pt")
    private fun uiSpeech(id: String, phase: String) { runOnUiThread { reception?.push(JSONObject().put("type", "ui_speech").put("id", id).put("phase", phase)) } }
    private fun speak(text: String, language: String, id: String) {
        val engine = tts
        if (!ttsReady || engine == null || language !in langs) { uiSpeech(id, "unavailable"); return }
        val r = engine.setLanguage(Locale.forLanguageTag(language))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) { uiSpeech(id, "unavailable"); return }
        if (engine.speak(text.take(300), TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) uiSpeech(id, "unavailable")
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        tts = TextToSpeech(this) { code ->
            ttsReady = code == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { if (id != null) uiSpeech(id, "speaking") }
                override fun onDone(id: String?) { if (id != null) uiSpeech(id, "ended") }
                @Deprecated("") override fun onError(id: String?) { if (id != null) uiSpeech(id, "unavailable") }
            })
        }
        val root = FrameLayout(this)
        val view = ReceptionView(this) { handle(it) }
        reception = view
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        view.setShown(true)
        view.push(JSONObject().put("type", "demo_mode").put("enabled", true))
        view.push(JSONObject().put("type", "night_mode").put("enabled", night))
        view.push(JSONObject().put("type", "connection").put("connected", false))
    }

    private fun handle(message: JSONObject) {
        when (message.optString("action")) {
            "night" -> { night = message.optBoolean("enabled", !night); reception?.push(JSONObject().put("type", "night_mode").put("enabled", night)) }
            "speak" -> speak(message.optString("text"), message.optString("language", "en"), message.optString("id", "reception-ui").take(80))
            "silence", "stop" -> tts?.stop()
            else -> Unit // exit/start/etc: nothing to leave to, and no live backend yet
        }
    }

    override fun onResume() { super.onResume(); reception?.setShown(true); updater.check() }
    override fun onPause() { super.onPause(); tts?.stop(); reception?.setShown(false) }
    override fun onDestroy() { tts?.shutdown(); reception?.dispose(); reception = null; super.onDestroy() }
}
