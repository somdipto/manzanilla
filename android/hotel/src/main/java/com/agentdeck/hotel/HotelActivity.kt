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
    private val updater by lazy { Updater(this) { status -> runOnUiThread { onUpdate(status) } } }
    private val store by lazy { SecretStore(this) }
    private val fishStore by lazy { SecretStore(this, "fish") }
    private val local by lazy { GeminiVoice(this, { m -> runOnUiThread { reception?.push(m) } }, store) }
    private val fish by lazy { FishVoice(this, { m -> runOnUiThread { reception?.push(m) } }) { fishKey() } }
    private var pendingStart: Triple<String, String, String?>? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val langs = setOf("en", "es", "ja", "zh", "fr", "de", "it", "pt")
    private fun fishKey(): String {
        val baked = BuildConfig.FISH_API_KEY
        if (baked.isNotBlank()) return baked
        return fishStore.load().orEmpty()
    }
    private fun prep() = JSONObject().put("type", "prep")
        .put("status", if (fishKey().isNotBlank() || store.has()) "ready" else "nokey")
        .put("engine", if (fishKey().isNotBlank()) "fish" else if (store.has()) "gemini" else "none")
        .put("baked", BuildConfig.FISH_API_KEY.isNotBlank())
        .put("version", BuildConfig.VERSION_NAME)
    private fun onUpdate(status: String) {
        when {
            status.startsWith("offer:") -> reception?.push(JSONObject().put("type", "update").put("version", status.removePrefix("offer:")))
            status.startsWith("Downloading") || status.startsWith("Installing") || status.startsWith("Update") -> reception?.push(JSONObject().put("type", "update_status").put("text", status))
        }
    }
    private fun begin(g: String, st: String) {
        if (fishKey().isNotBlank()) fish.start(g, st)
        else if (store.has()) local.start(g, st)
        else reception?.push(JSONObject().put("type", "reception_event").put("phase", "error").put("need_key", true).put("text", "Add a Fish Audio key in Settings to test live duplex."))
    }
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
                override fun onStart(id: String?) { android.util.Log.i("LV", "tts onStart $id");  if (id != null) uiSpeech(id, "speaking") }
                override fun onDone(id: String?) { android.util.Log.i("LV", "tts onDone $id");  if (id != null) uiSpeech(id, "ended") }
                @Deprecated("") override fun onError(id: String?) {  if (id != null) uiSpeech(id, "unavailable") }
            })
        }
        val root = FrameLayout(this)
        val view = ReceptionView(this) { handle(it) }
        reception = view
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        view.setShown(true)
        view.push(JSONObject().put("type", "demo_mode").put("enabled", true))
        view.push(JSONObject().put("type", "local_voice").put("enabled", true))
        if (BuildConfig.DEBUG) { intent?.getStringExtra("key")?.let { store.save(it) }; intent?.getStringExtra("wav")?.let { w -> view.postDelayed({ local.start(intent.getStringExtra("guest") ?: "en", intent.getStringExtra("staff") ?: "es", intent.getStringExtra("turn") ?: "guest", w) }, 22000) } }

        if (BuildConfig.DEBUG) intent?.getStringExtra("scene")?.let { sc -> view.postDelayed({
            fun ev(side: String, o: String, t: String, f: String, to: String) = view.push(JSONObject().put("type", "reception_event").put("phase", "listening").put("side", side).put("original", o).put("text", t).put("from", f).put("to", to))
            if (sc == "error") { view.push(JSONObject().put("type", "reception_event").put("phase", "error").put("text", "No internet connection. Live translation needs a network.")) }
            else { view.push(JSONObject().put("type", "reception_event").put("phase", "listening"))
                if (sc != "idle") { ev("guest", "Where is the breakfast room?", "¿Dónde está el comedor del desayuno?", "en", "es")
                ev("staff", "Está en la planta baja, al lado del vestíbulo.", "It is on the ground floor, next to the lobby.", "es", "en")
                ev("guest", "Could we get two extra towels and a late checkout for room 412?", "¿Podrían darnos dos toallas más y salida tardía para la habitación 412?", "en", "es") } } }, 24000) }
        if (BuildConfig.DEBUG) intent?.getStringExtra("goto")?.let { g -> view.postDelayed({ view.push(JSONObject().put("type", "goto").put("page", g)) }, 24000) }
        reception?.push(prep())
        view.push(JSONObject().put("type", "night_mode").put("enabled", night))
        view.push(JSONObject().put("type", "connection").put("connected", false))
    }

    private fun handle(message: JSONObject) {
        when (message.optString("action")) {
            "night" -> { night = message.optBoolean("enabled", !night); reception?.push(JSONObject().put("type", "night_mode").put("enabled", night)) }
            "speak" -> speak(message.optString("text"), message.optString("language", "en"), message.optString("id", "reception-ui").take(80))
            "start" -> if (message.optString("mode") == "translation") {
                val g = message.optString("language", "en"); val st = message.optString("staff_language", "es")
                if (DeskLanguages.has(g) && DeskLanguages.has(st) && g != st) {
                    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) { pendingStart = Triple(g, st, null); requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 5) }
                    else begin(g, st)
                }
            }
            "stop" -> { fish.stop(); local.stop(true); tts?.stop() }
            "info" -> reception?.push(prep())
            "save_key" -> { val v = message.optString("key").trim(); reception?.push(if (v.length >= 20 && store.save(v)) prep() else JSONObject().put("type", "prep").put("status", "keyfail")) }
            "save_fish_key" -> { val v = message.optString("key").trim(); reception?.push(if (v.length >= 20 && fishStore.save(v)) prep() else JSONObject().put("type", "prep").put("status", "keyfail")) }
            "clear_key" -> { store.clear(); reception?.push(prep()) }
            "clear_fish_key" -> { fishStore.clear(); reception?.push(prep()) }
            "apply_update" -> updater.installOffered()
            "turn" -> local.setTurn(message.optString("side", "guest"))
            "silence" -> tts?.stop()
            "install_voice" -> try { startActivity(android.content.Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Throwable) { android.util.Log.e("LV", "install voice", e) }
            else -> Unit // exit/start/etc: nothing to leave to, and no live backend yet
        }
    }


    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        val ps = pendingStart; pendingStart = null
        if (code == 5 && ps != null && r.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) begin(ps.first, ps.second)
        else reception?.push(JSONObject().put("type", "reception_event").put("phase", "error").put("text", "Microphone permission is needed for live translation."))
    }
    override fun onResume() { super.onResume(); reception?.setShown(true); updater.check() }
    override fun onPause() { super.onPause(); fish.stop(); local.stop(); tts?.stop(); reception?.setShown(false) }
    override fun onDestroy() { fish.release(); tts?.shutdown(); reception?.dispose(); reception = null; super.onDestroy() }
}
