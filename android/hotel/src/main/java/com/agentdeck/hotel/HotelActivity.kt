package com.agentdeck.hotel

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.FrameLayout
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import com.agentdeck.ui.ReceptionView
import org.json.JSONObject

/** Manzanilla reception: the packaged Reception UI, full screen. No setup, no enrollment. */
class HotelActivity : Activity() {
    private var reception: ReceptionView? = null
    private val deck by lazy { getSharedPreferences("deck", MODE_PRIVATE) }
    private val messages by lazy { GuestMessages(this) }
    private val ui = Handler(Looper.getMainLooper())
    private var night = false
    private var sessionActive = false
    private var lastCaption = ""
    private val ringing = AtomicBoolean(false)
    private val updater by lazy { Updater(this) { status -> runOnUiThread { onUpdate(status) } } }
    private val store by lazy { SecretStore(this) }
    private val fishStore by lazy { SecretStore(this, "fish") }
    private val local by lazy { GeminiVoice(this, { m -> runOnUiThread { forward(m) } }, store) }
    private val fish by lazy { FishVoice(this, { m -> runOnUiThread { forward(m) } }) { fishKey() } }
    private var pendingStart: Pair<String, String>? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var greetPlayer: MediaPlayer? = null
    private var greetToken = 0
    private var greetText = ""
    private var onGreetDone: (() -> Unit)? = null
    private fun fishKey(): String {
        val baked = BuildConfig.FISH_API_KEY
        if (baked.isNotBlank()) return baked
        return fishStore.load().orEmpty()
    }
    private fun appearance() = if (deck.getString("reception_appearance", "light") == "dark") "dark" else "light"
    private fun voiceReady() = fishKey().isNotBlank() || store.has()
    private fun hotelName(): String {
        val host = runCatching { android.net.Uri.parse(BuildConfig.BACKEND_URL).host }.getOrNull()
        return if (host.isNullOrBlank()) "Hotel reception" else host
    }
    private fun receptionInfo() = JSONObject().put("type", "reception_info")
        .put("hotel_name", hotelName())
        .put("sample_hotel", false)
        .put("configured", BuildConfig.FISH_API_KEY.isNotBlank() || fishStore.has())
        .put("staff_language", "es")
    private fun pushState() {
        val page = reception ?: return
        page.push(JSONObject().put("type", "appearance").put("value", appearance()))
        page.push(JSONObject().put("type", "night_mode").put("enabled", night))
        page.push(receptionInfo())
        page.push(prep())
        page.push(JSONObject().put("type", "connection").put("connected", voiceReady()))
    }
    private fun caption(text: String) = JSONObject().put("type", "reception_event").put("phase", "listening").put("transcript", text).put("source", "input")
    /** Every engine event reaches the UI verbatim. Mic level also becomes energy, and the last finished turn feeds the message draft. */
    private fun forward(message: JSONObject) {
        val type = message.optString("type")
        if (type == "reception_event") {
            val phase = message.optString("phase")
            if (phase == "ended" || phase == "error" || phase == "stopped") sessionActive = false
            if (!message.optBoolean("partial") && message.has("original")) {
                val said = message.optString("text").ifBlank { message.optString("original") }
                if (said.isNotBlank()) lastCaption = said
            }
        }
        if (type == "mic_level") {
            reception?.push(message)
            reception?.push(JSONObject().put("type", "energy").put("level", message.optDouble("level")))
            return
        }
        reception?.push(message)
    }
    private fun stopSession() {
        stopGreeting()
        fish.stop()
        local.stop(true)
        sessionActive = false
        reception?.push(JSONObject().put("type", "reception_event").put("phase", "stopped"))
    }
    private fun ringBell() {
        if (!ringing.compareAndSet(false, true)) return
        Thread {
            try {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 350)
                ui.postDelayed({ try { tone.release() } catch (_: Throwable) {}; ringing.set(false) }, 400)
            } catch (_: Throwable) { ringing.set(false) }
        }.start()
    }
    private fun openStaffSetup() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            reception?.push(JSONObject().put("type", "reception_event").put("phase", "error").put("text", "Allow notifications so the staff key screen can open."))
            return
        }
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel("staff") == null) nm.createNotificationChannel(NotificationChannel("staff", "Staff setup", NotificationManager.IMPORTANCE_LOW))
        val tap = android.app.PendingIntent.getActivity(this, 4, android.content.Intent(this, KeyEntryActivity::class.java), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        nm.notify(4, Notification.Builder(this, "staff")
            .setSmallIcon(R.drawable.ic_manzanilla)
            .setContentTitle("Staff setup")
            .setContentText("Add the Fish Audio key for live duplex.")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build())
    }
    private fun prep() = JSONObject().put("type", "prep")
        .put("status", if (fishKey().isNotBlank() || store.has()) "ready" else "nokey")
        .put("engine", if (fishKey().isNotBlank()) "fish" else if (store.has()) "gemini" else "none")
        .put("baked", BuildConfig.FISH_API_KEY.isNotBlank())
        .put("version", BuildConfig.VERSION_NAME)
    private fun onUpdate(status: String) {
        val page = reception ?: return
        when {
            status.startsWith("offer:") -> page.push(JSONObject().put("type", "update").put("phase", "ready").put("version", status.removePrefix("offer:")))
            status.startsWith("progress:") -> {
                val rest = status.removePrefix("progress:")
                val pct = rest.toIntOrNull()
                val text = when {
                    pct != null && pct >= 0 -> "Downloading $pct%"
                    rest.startsWith("m") -> "Downloading ${rest.removePrefix("m")} MB"
                    else -> "Starting download…"
                }
                page.push(JSONObject().put("type", "update_status").put("phase", "progress").put("percent", pct ?: -1).put("text", text))
            }
            status.startsWith("confirm:") -> page.push(JSONObject().put("type", "update_status").put("phase", "confirm").put("text", status.removePrefix("confirm:")))
            status.startsWith("failed:") -> page.push(JSONObject().put("type", "update_status").put("phase", "failed").put("text", status.removePrefix("failed:")))
            status.startsWith("installed") -> page.push(JSONObject().put("type", "update_status").put("phase", "installed").put("text", "Updated"))
        }
    }
    private fun begin(g: String, st: String) {
        if (fishKey().isNotBlank()) {
            sessionActive = true
            fish.start(g, st)
            playGreeting(g) { fish.greetingDone() }
        } else if (store.has()) { sessionActive = true; local.start(g, st) }
        else reception?.push(JSONObject().put("type", "reception_event").put("phase", "error").put("need_key", true).put("text", "Add a Fish Audio key in staff setup to test live duplex."))
    }
    private fun playGreeting(lang: String, done: () -> Unit) {
        val token = ++greetToken
        releaseGreeting()
        onGreetDone = done
        fun finish() {
            if (token != greetToken) return
            val cb = onGreetDone
            onGreetDone = null
            releaseGreeting()
            cb?.invoke()
        }
        try {
            val afd = assets.openFd("greetings/$lang.wav")
            val player = MediaPlayer()
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            player.setOnCompletionListener { finish() }
            player.setOnErrorListener { _, _, _ -> finish(); true }
            player.prepare()
            player.start()
            greetPlayer = player
        } catch (_: Throwable) {
            val engine = tts
            val text = greetText
            if (ttsReady && engine != null && text.isNotBlank()) {
                val ready = engine.setLanguage(Locale.forLanguageTag(lang))
                if (ready != TextToSpeech.LANG_MISSING_DATA && ready != TextToSpeech.LANG_NOT_SUPPORTED && engine.speak(text.take(180), TextToSpeech.QUEUE_FLUSH, null, "greet-$token") != TextToSpeech.ERROR) return
            }
            finish()
        }
    }
    private fun releaseGreeting() {
        greetPlayer?.let { player ->
            player.setOnCompletionListener(null)
            player.setOnErrorListener(null)
            runCatching { if (player.isPlaying) player.stop() }
            player.release()
        }
        greetPlayer = null
    }
    private fun stopGreeting() {
        greetToken++
        onGreetDone = null
        releaseGreeting()
        tts?.stop()
    }
    private fun finishGreeting(id: String) {
        val token = id.removePrefix("greet-").toIntOrNull() ?: return
        if (token != greetToken) return
        val cb = onGreetDone
        onGreetDone = null
        cb?.invoke()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        tts = TextToSpeech(this) { code ->
            ttsReady = code == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { if (id?.startsWith("greet-") == true) finishGreeting(id) }
                @Deprecated("") override fun onError(id: String?) { if (id?.startsWith("greet-") == true) finishGreeting(id) }
            })
        }
        val root = FrameLayout(this)
        val view = ReceptionView(this) { handle(it) }
        reception = view
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        night = deck.getBoolean("reception_night", false)
        view.setShown(true)
        pushState()
        if (BuildConfig.DEBUG) { intent?.getStringExtra("key")?.let { store.save(it) }; intent?.getStringExtra("wav")?.let { w -> view.postDelayed({ local.start(intent.getStringExtra("guest") ?: "en", intent.getStringExtra("staff") ?: "es", intent.getStringExtra("turn") ?: "guest", w) }, 22000) } }

        if (BuildConfig.DEBUG) intent?.getStringExtra("scene")?.let { sc -> view.postDelayed({
            fun ev(side: String, o: String, t: String, f: String, to: String) = view.push(JSONObject().put("type", "reception_event").put("phase", "listening").put("side", side).put("original", o).put("text", t).put("from", f).put("to", to))
            if (sc == "error") { view.push(JSONObject().put("type", "reception_event").put("phase", "error").put("text", "No internet connection. Live translation needs a network.")) }
            else { view.push(JSONObject().put("type", "reception_event").put("phase", "listening"))
                if (sc != "idle") { ev("guest", "Where is the breakfast room?", "¿Dónde está el comedor del desayuno?", "en", "es")
                ev("staff", "Está en la planta baja, al lado del vestíbulo.", "It is on the ground floor, next to the lobby.", "es", "en")
                ev("guest", "Could we get two extra towels and a late checkout for room 412?", "¿Podrían darnos dos toallas más y salida tardía para la habitación 412?", "en", "es") } } }, 24000) }
        if (BuildConfig.DEBUG) intent?.getStringExtra("goto")?.let { g -> view.postDelayed({ view.push(JSONObject().put("type", "goto").put("page", g)) }, 24000) }
    }

    private fun handle(message: JSONObject) {
        when (message.optString("action")) {
            "appearance" -> {
                val value = if (message.optString("value") == "dark") "dark" else "light"
                deck.edit().putString("reception_appearance", value).apply()
                reception?.push(JSONObject().put("type", "appearance").put("value", value))
            }
            "night" -> {
                if (sessionActive) return
                night = message.optBoolean("enabled", !night)
                deck.edit().putBoolean("reception_night", night).apply()
                reception?.push(JSONObject().put("type", "night_mode").put("enabled", night))
            }
            "bell" -> ringBell()
            "settings" -> openStaffSetup()
            "info" -> pushState()
            "start" -> {
                greetText = message.optString("greet")
                val g = message.optString("language", "en"); val st = message.optString("staff_language", "es")
                if (!DeskLanguages.has(g) || !DeskLanguages.has(st) || g == st) {
                    reception?.push(JSONObject().put("type", "reception_event").put("phase", "error").put("text", "Pick a guest language and a different reception language."))
                    return
                }
                if (sessionActive) {
                    reception?.push(JSONObject().put("type", "reception_event").put("phase", "error").put("text", "End the current conversation before starting a new one."))
                    return
                }
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) { pendingStart = Pair(g, st); requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 5) }
                else begin(g, st)
            }
            "stop" -> stopSession()
            "message" -> reception?.push(messages.draft(lastCaption))
            "confirm" -> if (message.optBoolean("approved")) {
                if (messages.save(message.optString("draft_id"))) reception?.push(JSONObject().put("type", "reception_event").put("phase", "saved").put("saved", true).put("text", "Saved on this tablet only. The front desk has not been notified."))
                else reception?.push(JSONObject().put("type", "reception_event").put("phase", "error").put("text", "That message could not be saved on this tablet."))
            } else { messages.discard(); reception?.push(JSONObject().put("type", "reception_event").put("phase", "listening")) }
            "checkin" -> reception?.push(caption("Check-in is not connected to the property system on this tablet. Use the desk terminal."))
            "operator" -> { val ev = caption("No operator line is connected on this tablet. Call the desk phone."); ev.put("operator_available", false); reception?.push(ev) }
            "taxi" -> reception?.push(caption("No taxi service is connected on this tablet. Book it from the desk phone."))
            "question" -> reception?.push(caption("No question routing is connected on this tablet. Answer it at the desk."))
            "silence" -> { tts?.stop(); stopGreeting() }
            "activity" -> Unit
            "exit" -> { stopGreeting(); fish.stop(); local.stop(); sessionActive = false; finish() }
            "apply_update" -> updater.installOffered()
        }
    }
    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (code != 5) return
        val ps = pendingStart; pendingStart = null
        if (ps != null && r.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) begin(ps.first, ps.second)
        else reception?.push(JSONObject().put("type", "reception_event").put("phase", "error").put("text", "Microphone permission is needed for live translation."))
    }
    override fun onResume() {
        super.onResume()
        reception?.setShown(true)
        pushState()
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        updater.check()
    }
    override fun onPause() { super.onPause(); stopGreeting(); fish.stop(); local.stop(); sessionActive = false; reception?.setShown(false) }
    override fun onDestroy() { stopGreeting(); fish.release(); tts?.shutdown(); reception?.dispose(); reception = null; super.onDestroy() }
}
