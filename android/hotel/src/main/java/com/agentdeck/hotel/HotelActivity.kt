package com.agentdeck.hotel

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.agentdeck.ui.ReceptionView
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Native capture, credentials and gateway ownership; the packaged guest UI has none. */
class HotelActivity : Activity() {
    private var reception: ReceptionView? = null
    private val deck by lazy { getSharedPreferences("deck", MODE_PRIVATE) }
    private val messages by lazy { GuestMessages(this) }
    private val owner = SessionOwner()
    private val ui = Handler(Looper.getMainLooper())
    private val ringing = AtomicBoolean(false)
    private val store by lazy { SecretStore(this) }
    private val fishStore by lazy { SecretStore(this, "fish") }
    private val updater by lazy { Updater(this) { status -> runOnUiThread { onUpdate(status) } } }
    private val updatePoll = object : Runnable {
        override fun run() {
            if (!foreground || destroyed) return
            updater.check()
            ui.postDelayed(this, 10 * 60_000L)
        }
    }
    private val localProvider = lazy { GeminiVoice(this, { event -> runOnUiThread { forward(event) } }, store) }
    private val local by localProvider
    private val fishProvider = lazy { FishVoice(this, { event -> runOnUiThread { forward(event) } }) { fishStore.load().orEmpty() } }
    private val fish by fishProvider
    private val cloudProvider = lazy { CloudVoice(this, { event -> runOnUiThread { forward(event) } }, { event -> runOnUiThread { cloudTool(event) } }) }
    private val cloud by cloudProvider
    private val gatewayProvider = lazy { HotelGateway(BuildConfig.BACKEND_URL, BuildConfig.DEVICE_TOKEN,
        { lease, event -> runOnUiThread { if (current(lease)) push(lease, event) } },
        { lease, event -> runOnUiThread { if (current(lease)) dialOperation(lease, event) } }) }
    private val gateway by gatewayProvider
    private val afterRelease = ArrayList<() -> Unit>()
    private enum class Engine { FISH, GEMINI, CLOUD }
    private var engine: Engine? = null
    private var night = false
    private var foreground = false
    private var destroyed = false
    private var backCallback: OnBackInvokedCallback? = null
    private var pendingPermission: SessionOwner.Lease? = null
    private var audioState = "off"
    private val guestUtterances = LinkedHashMap<String, String>()
    private var lastActivity = 0L
    private var greetPlayer: MediaPlayer? = null
    private var greetToken = 0
    private val watchdog = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (owner.current != null && !owner.closing && SystemClock.elapsedRealtime() - lastActivity >= 120_000)
                stopSession("timeout", "Conversation ended after two minutes without guest activity.")
            ui.postDelayed(this, 1_000)
        }
    }

    private fun backendConfigured() = BuildConfig.BACKEND_URL.startsWith("https://") && BuildConfig.DEVICE_TOKEN.isNotBlank()
    private fun current(lease: SessionOwner.Lease) = owner.accepts(lease.request.id, lease.generation)
    private fun scoped(lease: SessionOwner.Lease, message: JSONObject) = message
        .put("session_id", lease.request.id).put("generation", lease.generation)
    private fun push(lease: SessionOwner.Lease, message: JSONObject) { reception?.push(scoped(lease, message)) }
    private fun phase(lease: SessionOwner.Lease, phase: String, text: String = "") =
        push(lease, JSONObject().put("type", "reception_event").put("phase", phase).put("text", text))
    private fun audio(lease: SessionOwner.Lease, state: String) =
        push(lease, JSONObject().put("type", "audio_state").put("state", state))
    private fun notice(text: String) { reception?.push(JSONObject().put("type", "notice").put("text", text)) }

    private fun pushState() {
        val page = reception ?: return
        val fishReady = fishStore.has() || BuildConfig.FISH_API_KEY.isNotBlank()
        val geminiReady = store.has()
        val ready = backendConfigured() || fishReady || geminiReady
        page.push(JSONObject().put("type", "appearance").put("value", deck.getString("reception_appearance", "dark")))
        page.push(JSONObject().put("type", "night_mode").put("enabled", night))
        page.push(JSONObject().put("type", "reception_info").put("hotel_name", "Hotel reception")
            .put("sample_hotel", false).put("configured", ready).put("staff_language", "es"))
        val guest = deck.getString("reception_guest_language", null)
        val staff = deck.getString("reception_staff_language", null)
        if (guest != null && staff != null) page.push(JSONObject().put("type", "language_preferences")
            .put("language", guest).put("staff_language", staff))
        page.push(JSONObject().put("type", "prep").put("status", if (ready) "ready" else "nokey").put("version", BuildConfig.VERSION_NAME))
        page.push(JSONObject().put("type", "connection").put("connected", ready))
    }

    private fun forward(message: JSONObject) {
        val type = message.optString("type")
        val lease = owner.current
        if (lease == null) {
            if (type == "audio_state" && message.optString("state") == "off" &&
                owner.confirmLateRelease(message.optString("session_id"), message.optInt("generation", -1))) {
                audioState = "off"
                reception?.push(message)
            }
            return
        }
        if (!owner.accepts(message.optString("session_id"), message.optInt("generation", -1), type == "audio_state")) return
        if (type == "audio_state") {
            val value = message.optString("state")
            if (value != "off" && value != "recording" && value != "unknown") return
            audioState = if (owner.closing && value == "recording") "unknown" else value
            reception?.push(message.put("state", audioState))
            return
        }
        if (type == "reception_event") {
            when (message.optString("phase")) {
                "idle", "ended", "stopped", "closed", "timeout", "error", "failed", "blocked" -> {
                    val phase = message.optString("phase")
                    stopSession(if (phase == "idle") "timeout" else phase, message.optString("text", message.optString("message")))
                    return
                }
            }
        }
        if (type == "caption" && message.optString("field") == "original") {
            val id = message.optString("utterance_id")
            val text = message.optString("text")
            val value = if (message.optString("operation") == "append") guestUtterances[id].orEmpty() + text else text
            guestUtterances[id] = value
            if (guestUtterances.size > 16) guestUtterances.remove(guestUtterances.keys.first())
            if (value.isNotBlank()) lastActivity = SystemClock.elapsedRealtime()
        }
        if (current(lease)) reception?.push(message)
    }

    private fun begin(request: SessionOwner.Request) {
        val lease = owner.acquire(request) ?: run { notice("Wait until the previous microphone release is confirmed before starting again."); return }
        guestUtterances.clear()
        messages.discard()
        lastActivity = SystemClock.elapsedRealtime()
        audioState = "off"
        phase(lease, "connecting")
        audio(lease, "off")
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingPermission = lease
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 5)
        } else connect(lease)
    }

    private fun connect(lease: SessionOwner.Lease) {
        if (!current(lease) || !foreground) return
        val request = lease.request
        if (request.mode == "reception") {
            if (!backendConfigured()) {
                stopSession("error", "Hotel assistance needs the hotel's secure backend. Ask staff to configure the hotel connection.")
                return
            }
            engine = Engine.CLOUD
            gateway.begin(lease)
            cloud.start(request.guest, request.staff, request.id, lease.generation)
        } else if (backendConfigured() || fishStore.has() || BuildConfig.FISH_API_KEY.isNotBlank()) {
            engine = Engine.FISH
            fish.start(request.guest, request.staff, request.mode, request.id, lease.generation)
        } else if (store.has()) {
            engine = Engine.GEMINI
            local.start(request.guest, request.staff, request.mode, request.id, lease.generation)
        } else {
            stopSession("error", "Voice is not configured. Authorized staff can connect the hotel or configure a device credential.")
            return
        }
        if (!current(lease)) return
        playGreeting(lease) {
            if (current(lease) && foreground) when (engine) {
                Engine.FISH -> fish.greetingDone()
                Engine.GEMINI -> local.greetingDone()
                Engine.CLOUD -> cloud.greetingDone()
                null -> Unit
            }
        }
    }

    private fun stopSession(reason: String = "stopped", text: String = "", done: (() -> Unit)? = null) {
        pendingPermission = null
        if (done != null) afterRelease.add(done)
        val lease = owner.beginClose()
        if (lease == null) { if (owner.current == null) finishReleaseActions(); return }
        stopGreeting()
        messages.discard()
        guestUtterances.clear()
        if (gatewayProvider.isInitialized()) gateway.end()
        phase(lease, "ending")
        audio(lease, "stopping")
        val selected = engine
        engine = null
        val released: () -> Unit = {
            runOnUiThread {
                if (owner.current == lease && owner.closing) {
                    val off = audioState == "off"
                    audio(lease, if (off) "off" else "unknown")
                    owner.released(lease, off)
                    phase(lease, reason, text)
                    finishReleaseActions()
                }
            }
        }
        when (selected) {
            Engine.FISH -> fish.stop(released)
            Engine.GEMINI -> local.stop(true, released)
            Engine.CLOUD -> cloud.stop(released)
            null -> released()
        }
    }

    private fun finishReleaseActions() {
        while (afterRelease.isNotEmpty()) afterRelease.removeAt(0).invoke()
    }

    private fun playGreeting(lease: SessionOwner.Lease, done: () -> Unit) {
        stopGreeting()
        val token = greetToken
        phase(lease, "greeting")
        try {
            val player = MediaPlayer()
            greetPlayer = player
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            assets.openFd("greetings/${lease.request.guest}.wav").use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            player.setOnCompletionListener {
                if (token == greetToken && current(lease)) { stopGreeting(); done() }
            }
            player.setOnErrorListener { _, _, _ ->
                if (token == greetToken && current(lease)) stopSession("error", "The welcome audio could not play. Ask staff to check the speaker before starting.")
                true
            }
            player.setOnPreparedListener {
                if (token != greetToken || !current(lease) || !foreground || it !== greetPlayer) return@setOnPreparedListener
                try {
                    it.start()
                    if (it.isPlaying && token == greetToken && current(lease) && foreground && it === greetPlayer) {
                        push(lease, JSONObject().put("type", "app_greeting").put("state", "started")
                            .put("language", lease.request.guest).put("staff_language", lease.request.staff))
                    }
                } catch (_: Exception) {
                    if (token == greetToken && current(lease)) stopSession("error", "The welcome audio could not play. Ask staff to check the speaker before starting.")
                }
            }
            player.prepareAsync()
        } catch (_: Exception) {
            stopSession("error", "The welcome audio is unavailable. Ask staff to check this installation.")
        }
    }

    private fun stopGreeting() {
        greetToken++
        greetPlayer?.let { player ->
            player.setOnCompletionListener(null); player.setOnPreparedListener(null); player.setOnErrorListener(null)
            runCatching { player.stop() }
            player.release()
        }
        greetPlayer = null
    }

    private fun ringBell() {
        if (!ringing.compareAndSet(false, true)) return
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 350)
            ui.postDelayed({ tone.release(); ringing.set(false) }, 400)
        } catch (_: Exception) { ringing.set(false) }
    }

    private fun openStaffSetup() = stopSession(done = {
        if (!destroyed && foreground) startActivity(Intent(this, KeyEntryActivity::class.java))
    })

    private fun cloudTool(message: JSONObject) {
        val lease = owner.current ?: return
        if (!owner.accepts(message.optString("session_id"), message.optInt("generation", -1))) return
        when (message.optString("type")) {
            "confirm" -> phaseConfirmation(lease, "voice:${message.optString("id")}",
                message.optString("tool").replace('_', ' ') + "\n" + message.optJSONObject("arguments").toString())
            "handoff" -> {
                val status = openDialer(message.optString("number"))
                cloud.handoff(message.optString("id"), status)
                push(lease, JSONObject().put("type", "operation_result").put("status", status)
                    .put("text", if (status == "dialer_opened") "The phone dialer is open. A person has not yet answered." else "The phone dialer could not open. Ask reception to call the configured number."))
            }
        }
    }

    private fun phaseConfirmation(lease: SessionOwner.Lease, id: String, summary: String) =
        push(lease, JSONObject().put("type", "reception_event").put("phase", "confirming").put("draft_id", id).put("message", summary))
    private fun openDialer(number: String): String {
        if (!number.matches(Regex("\\+[1-9][0-9]{6,14}"))) return "failed"
        return try { startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))); "dialer_opened" }
        catch (_: Exception) { "failed" }
    }
    private fun dialOperation(lease: SessionOwner.Lease, response: JSONObject) {
        val number = response.optString("number", response.optJSONObject("result")?.optString("number").orEmpty())
        val status = openDialer(number)
        push(lease, JSONObject().put("type", "operation_result").put("status", status)
            .put("text", if (status == "dialer_opened") "The phone dialer is open. No answered call or taxi booking is confirmed." else "This device could not open the dialer. Ask reception to call for you."))
    }

    private fun onUpdate(status: String) {
        val page = reception ?: return
        when {
            status.startsWith("checking:") -> page.push(JSONObject().put("type", "update_status").put("phase", "checking").put("text", "Checking for updates…"))
            status.startsWith("current:") -> page.push(JSONObject().put("type", "update_status").put("phase", "current").put("text", "Up to date · ${BuildConfig.VERSION_NAME}"))
            status.startsWith("offer:") -> page.push(JSONObject().put("type", "update").put("phase", "ready").put("version", status.removePrefix("offer:")))
            status.startsWith("progress:") -> {
                val value = status.removePrefix("progress:")
                val percent = value.toIntOrNull()
                val text = if (percent != null && percent >= 0) "Downloading $percent%" else if (value.startsWith("m")) "Downloading ${value.removePrefix("m")} MB" else "Starting download…"
                page.push(JSONObject().put("type", "update_status").put("phase", "progress").put("percent", percent ?: -1).put("text", text))
            }
            status.startsWith("confirm:") -> page.push(JSONObject().put("type", "update_status").put("phase", "confirm").put("text", status.removePrefix("confirm:")))
            status.startsWith("failed:") -> page.push(JSONObject().put("type", "update_status").put("phase", "failed").put("text", status.removePrefix("failed:")))
            status.startsWith("installed") -> page.push(JSONObject().put("type", "update_status").put("phase", "installed").put("text", "Updated"))
        }
    }

    private fun handle(message: JSONObject) {
        val action = message.optString("action")
        when (action) {
            "stop", "message", "confirm", "checkin", "operator", "taxi", "question" ->
                if (!owner.accepts(message.optString("session_id"), message.optInt("generation", -1), duringClose = true)) return
            "silence", "activity" -> if (owner.current != null &&
                !owner.accepts(message.optString("session_id"), message.optInt("generation", -1))) return
        }
        when (action) {
            "appearance" -> {
                val value = if (message.optString("value") == "dark") "dark" else "light"
                deck.edit().putString("reception_appearance", value).apply()
                window.setBackgroundDrawable(ColorDrawable(if (value == "dark") Color.rgb(8, 15, 21) else Color.rgb(16, 34, 48)))
                reception?.push(JSONObject().put("type", "appearance").put("value", value))
            }
            "night" -> if (owner.current == null) {
                night = message.optBoolean("enabled", !night)
                deck.edit().putBoolean("reception_night", night).apply()
                reception?.push(JSONObject().put("type", "night_mode").put("enabled", night))
            }
            "languages" -> if (owner.current == null) {
                val guest = message.optString("language"); val staff = message.optString("staff_language")
                if (DeskLanguages.has(guest) && DeskLanguages.has(staff)) deck.edit()
                    .putString("reception_guest_language", guest).putString("reception_staff_language", staff).apply()
            }
            "bell" -> ringBell()
            "settings" -> openStaffSetup()
            "info" -> pushState()
            "start" -> {
                val id = message.optString("session_id")
                val mode = message.optString("mode")
                val guest = message.optString("language"); val staff = message.optString("staff_language")
                val validId = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
                if (!validId || !DeskLanguages.has(guest) || !DeskLanguages.has(staff) || (mode != "translation" && mode != "reception") || (mode == "translation" && guest == staff)) {
                    notice("Choose a supported mode and languages; translation needs two different languages.")
                    return
                }
                begin(SessionOwner.Request(id, mode, guest, staff, message.optString("greet")))
            }
            "stop" -> stopSession()
            "message" -> owner.current?.takeIf { current(it) }?.let {
                push(it, messages.draft(guestUtterances.values.joinToString("\n").takeLast(4000)))
            }
            "confirm" -> owner.current?.takeIf { current(it) }?.let { lease ->
                val id = message.optString("draft_id"); val approved = message.optBoolean("approved")
                when {
                    id.startsWith("operation:") -> gateway.confirm(lease, id, approved)
                    id.startsWith("voice:") -> { cloud.confirmation(id.removePrefix("voice:"), approved); phase(lease, "listening") }
                    !approved -> { messages.discard(); phase(lease, "listening") }
                    messages.save(id) -> push(lease, JSONObject().put("type", "reception_event").put("phase", "saved").put("saved", true)
                        .put("text", "Saved on this tablet only. The front desk has not been notified."))
                    else -> push(lease, JSONObject().put("type", "notice").put("status", "save_failed").put("text", "The note could not be saved on this tablet. Review it and retry."))
                }
            }
            "checkin", "operator", "taxi", "question" -> owner.current?.takeIf { current(it) && it.request.mode == "reception" }?.let {
                gateway.action(it, message.optString("action"), message.optJSONObject("arguments") ?: JSONObject())
            }
            "activity" -> lastActivity = SystemClock.elapsedRealtime()
            "silence" -> if (greetPlayer != null && owner.current != null) stopSession() else stopGreeting()
            "exit" -> stopSession(done = { finish() })
            "check_updates" -> updater.check()
            "apply_update" -> if (owner.current == null) updater.installOffered() else notice("End the conversation before installing the update.")
        }
    }

    private fun navigateBack() {
        val event = JSONObject().put("type", "navigation").put("action", "back").put("source", "android")
        val lease = owner.current
        reception?.push(if (lease != null) scoped(lease, event) else event)
        if (lease != null) stopSession()
    }

    @Deprecated("Legacy system Back dispatch")
    override fun onBackPressed() = navigateBack()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val dark = deck.getString("reception_appearance", "dark") != "light"
        window.setBackgroundDrawable(ColorDrawable(if (dark) Color.rgb(8, 15, 21) else Color.rgb(16, 34, 48)))
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = OnBackInvokedCallback { navigateBack() }.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
            }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        reception = ReceptionView(this, ::handle) { stopSession("error", "The reception screen stopped. Return to the launcher and reopen Reception.") }
        setContentView(FrameLayout(this).apply { addView(reception, FrameLayout.LayoutParams(-1, -1)) })
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        night = deck.getBoolean("reception_night", false)
        ui.post(watchdog)
    }
    override fun onResume() {
        super.onResume()
        foreground = true
        reception?.setShown(true)
        pushState()
        updater.check()
        ui.removeCallbacks(updatePoll)
        ui.postDelayed(updatePoll, 10 * 60_000L)
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != 5) return
        val lease = pendingPermission ?: return
        pendingPermission = null
        if (!current(lease)) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED && foreground) connect(lease)
        else stopSession("error", "Microphone permission is required. No conversation has started.")
    }
    override fun onPause() {
        foreground = false
        ui.removeCallbacks(updatePoll)
        stopSession()
        reception?.setShown(false)
        super.onPause()
    }
    override fun onDestroy() {
        destroyed = true
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        ui.removeCallbacks(watchdog)
        ui.removeCallbacks(updatePoll)
        stopSession(done = {
            if (fishProvider.isInitialized()) fish.release()
            if (cloudProvider.isInitialized()) cloud.release()
            if (gatewayProvider.isInitialized()) gateway.dispose()
        })
        reception?.dispose(); reception = null
        super.onDestroy()
    }
}
