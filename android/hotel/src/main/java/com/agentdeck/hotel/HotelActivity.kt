package com.agentdeck.hotel

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
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
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.UUID
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Native capture, credentials and gateway ownership; the packaged guest UI has none. */
class HotelActivity : Activity() {
    private var reception: ReceptionView? = null
    private val deck by lazy { getSharedPreferences("deck", MODE_PRIVATE) }
    private fun text(message: String, values: Map<String, String> = emptyMap(),
                     language: String = owner.current?.request?.guest ?: deck.getString("reception_guest_language", "es").orEmpty()): String =
        UiText.text(this, language.takeIf(DeskLanguages::has) ?: "es", message, values)

    private fun updateText(message: String): String {
        Regex("^Update (.+) is downloaded\\. It will apply when reception is idle\\.$").matchEntire(message)?.let {
            return text("Update {version} is downloaded. It will apply when reception is idle.", mapOf("version" to it.groupValues[1]))
        }
        Regex("^Could not check for updates \\(([a-z ]+), HTTP ([0-9]{3})\\)\\. Tap to retry\\.$").matchEntire(message)?.let {
            return text("Could not check for updates ({stage}, HTTP {status}). Tap to retry.",
                mapOf("stage" to it.groupValues[1], "status" to it.groupValues[2]))
        }
        return text(message)
    }

    private val owner = SessionOwner()
    private val ui = Handler(Looper.getMainLooper())
    private val ringing = AtomicBoolean(false)
    private val fishStore by lazy { SecretStore(this, "fish") }
    private var updateStatus: String? = null
    private val updater by lazy { Updater(this,
        onStatus = { status -> runOnUiThread { onUpdate(status) } },
        canInstall = ::canInstallUpdate) }
    private val updatePoll = object : Runnable {
        override fun run() {
            if (!foreground || destroyed) return
            updater.check()
            ui.postDelayed(this, 10 * 60_000L)
        }
    }
    private val fishProvider = lazy { FishVoice(this, { event -> runOnUiThread { forward(event) } }) { fishStore.load().orEmpty().ifBlank { BuildConfig.FISH_API_KEY } } }
    private val fish by fishProvider
    private val hotelConnection by lazy { HotelConnection(this) }
    private var gateway: HotelGateway? = null
    private val afterRelease = ArrayList<() -> Unit>()
    private enum class Engine { FISH }
    private var engine: Engine? = null
    private var night = false
    private var foreground = false
    private var destroyed = false
    private var backCallback: OnBackInvokedCallback? = null
    private var pendingPermission: SessionOwner.Lease? = null
    private var returnToTranslation = false
    private var audioState = "off"
    private enum class HotelStatus { unconfigured, checking, ready, offline, unauthorized }
    private var hotelStatus = HotelStatus.unconfigured
    private var hotelStatusAt = 0L
    private var hotelProbeGeneration = 0L
    private var hotelProbe: Call? = null
    private var hotelIdentity: HotelConnection.Credentials? = null
    private var hotelLocale = ""
    private var hotelConfigVersion = 0
    private var hotelName = ""
    private var hotelServices = JSONObject()
    private var hotelServicesSupported = false
    private val hotelServiceNames = arrayOf("checkin", "question", "taxi", "operator", "message")
    private var pendingMaintenance: String? = null
    private var awaitingStaffSetup = false
    private val maintenanceActions = setOf("check_updates", "apply_update", "appearance_light", "appearance_dark", "night_on", "night_off")
    private var readinessClient = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val hotelPoll = object : Runnable {
        override fun run() {
            if (!foreground || destroyed) return
            refreshHotelStatus()
            ui.postDelayed(this, 25_000L)
        }
    }
    private var lastActivity = 0L
    private val watchdog = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (owner.current != null && !owner.closing && SystemClock.elapsedRealtime() - lastActivity >= 120_000)
                stopSession("timeout", text("Conversation ended after two minutes without guest activity."))
            if (owner.current == null && audioState != fish.localAudioState()) {
                audioState = fish.localAudioState()
                pushState()
            }
            updater.tryInstallIfIdle()
            consumeMaintenance()
            ui.postDelayed(this, 1_000)
        }
    }

    private fun backendConfigured() = hotelConnection.load() != null
    private fun guestLocale() = deck.getString("reception_guest_language", "es").orEmpty().takeIf(DeskLanguages::has) ?: "es"

    private fun cancelHotelProbe() {
        hotelProbeGeneration++
        hotelProbe?.cancel()
        hotelProbe = null
    }

    /** Configuration is not evidence of authenticated reachability. No operation or microphone is opened. */
    private fun refreshHotelStatus() {
        val connection = hotelConnection.load()
        val language = guestLocale()
        if (connection != hotelIdentity || language != hotelLocale) {
            cancelHotelProbe()
            if (connection != hotelIdentity) { hotelConfigVersion = 0; hotelName = "" }
            hotelIdentity = connection
            hotelLocale = language
            hotelServices = JSONObject()
            hotelServicesSupported = false
            hotelStatusAt = 0L
        }
        if (connection == null) {
            hotelStatus = HotelStatus.unconfigured
            pushState()
            return
        }
        if (!foreground || destroyed || hotelProbe != null) return
        if (hotelStatusAt == 0L || SystemClock.elapsedRealtime() - hotelStatusAt > 30_000L)
            hotelStatus = HotelStatus.checking
        val generation = ++hotelProbeGeneration
        val body = JSONObject().put("app_version", BuildConfig.VERSION_NAME).put("config_version", hotelConfigVersion)
            .put("protocol", 1).put("language", language)
        val request = Request.Builder().url(connection.base.trimEnd('/') + "/api/device/heartbeat")
            .header("Authorization", "Bearer ${connection.token}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val call = readinessClient.newCall(request)
        hotelProbe = call
        pushState()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = settle(HotelStatus.offline, null)
            override fun onResponse(call: Call, response: Response) {
                var payload: JSONObject? = null
                val status = response.use {
                    when {
                        it.code == 401 || it.code == 403 -> HotelStatus.unauthorized
                        !it.isSuccessful -> HotelStatus.offline
                        else -> try {
                            val source = it.body?.source() ?: throw IOException()
                            source.request(262_145)
                            if (source.buffer.size > 262_144) throw IOException()
                            payload = JSONObject(source.readUtf8())
                            val hotel = payload!!.getJSONObject("hotel")
                            if (payload!!.getInt("protocol") != 1 || hotel.getString("name").isBlank() ||
                                hotel.getInt("config_version") < 1) throw IOException()
                            // Absence is the old-backend contract, not an authentication failure.
                            payload!!.optJSONObject("services")?.let { services ->
                                for (key in hotelServiceNames)
                                    if (services.opt(key) !is Boolean) throw IOException()
                            }
                            if (payload!!.has("services") && payload!!.optJSONObject("services") == null) throw IOException()
                            HotelStatus.ready
                        } catch (_: Exception) { payload = null; HotelStatus.offline }
                    }
                }
                settle(status, payload)
            }
            private fun settle(status: HotelStatus, payload: JSONObject?) = runOnUiThread {
                if (destroyed || !foreground || generation != hotelProbeGeneration || hotelProbe !== call ||
                    hotelConnection.load() != connection || guestLocale() != language) return@runOnUiThread
                hotelProbe = null
                hotelStatus = status
                hotelStatusAt = SystemClock.elapsedRealtime()
                hotelServices = JSONObject()
                hotelServicesSupported = false
                if (status == HotelStatus.ready && payload != null) {
                    val hotel = payload.getJSONObject("hotel")
                    hotelName = hotel.getString("name").take(300).filterNot { it.isISOControl() }
                    hotelConfigVersion = hotel.getInt("config_version")
                    payload.optJSONObject("services")?.let { services ->
                        hotelServicesSupported = true
                        for (key in hotelServiceNames)
                            hotelServices.put(key, services.getBoolean(key))
                    }
                }
                pushState()
            }
        })
    }

    private fun hotelBlockReason(action: String? = null): String {
        if (hotelConnection.load() != hotelIdentity || guestLocale() != hotelLocale ||
            (hotelStatusAt != 0L && SystemClock.elapsedRealtime() - hotelStatusAt > 30_000L)) {
            hotelStatus = if (backendConfigured()) HotelStatus.checking else HotelStatus.unconfigured
            refreshHotelStatus()
        }
        return when (hotelStatus) {
            HotelStatus.unconfigured -> "Hotel services need staff setup."
            HotelStatus.checking -> "Checking the hotel connection. Please wait."
            HotelStatus.offline -> "The hotel connection is unavailable. Please ask staff."
            HotelStatus.unauthorized -> "The hotel device is not authorized. Please ask staff."
            HotelStatus.ready -> when {
                !hotelServicesSupported -> "The hotel service needs an update. Please ask staff."
                action != null && !hotelServices.optBoolean(action) -> "This service is not configured by the hotel. Please ask staff."
                else -> ""
            }
        }
    }
    private fun canInstallUpdate(): Boolean = foreground && !destroyed && owner.current == null &&
        !owner.closing && pendingPermission == null && audioState == "off" &&
        fish.localAudioState() == "off" && fish.admissionIssue() == null
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
        val configured = backendConfigured()
        val audioGate = AudioPolicy.blockReason(configured, fishStore.status(), BuildConfig.FISH_API_KEY.isNotBlank(),
            TranslationSettings(this).directFishSelected, BuildConfig.DISTRIBUTION)
        if (owner.current == null) audioState = fish.localAudioState()
        val audioReady = audioGate.isEmpty()
        page.push(JSONObject().put("type", "appearance").put("value", deck.getString("reception_appearance", "dark")))
        page.push(JSONObject().put("type", "night_mode").put("enabled", night))
        page.push(JSONObject().put("type", "reception_info").put("hotel_name", hotelName.ifBlank { text("Hotel reception") })
            .put("sample_hotel", false).put("configured", configured).put("distribution", BuildConfig.DISTRIBUTION)
            .put("state", hotelStatus.name).put("services", safeServices())
            .put("services_supported", hotelServicesSupported)
            .put("bundled_key_available", BuildConfig.DISTRIBUTION == "personal-test" && BuildConfig.FISH_API_KEY.isNotBlank())
            .put("audio_available", audioReady)
            .put("local_audio_state", audioState)
            .put("audio_gate", if (audioGate.isEmpty()) "" else text(audioGate))
            .put("staff_language", deck.getString("reception_staff_language", "es").orEmpty().takeIf(DeskLanguages::has) ?: "es"))
        val guest = deck.getString("reception_guest_language", null)
        val staff = deck.getString("reception_staff_language", null)
        if (guest != null && staff != null) page.push(JSONObject().put("type", "language_preferences")
            .put("language", guest).put("staff_language", staff))
        page.push(JSONObject().put("type", "prep").put("status", if (configured) "ready" else "nokey").put("version", BuildConfig.VERSION_NAME))
        page.push(JSONObject().put("type", "connection").put("connected", hotelStatus == HotelStatus.ready).put("state", hotelStatus.name))
        if (BuildConfig.DISTRIBUTION == "personal-test") updateStatus?.let(::onUpdate)
    }

    private fun safeServices() = JSONObject().apply {
        for (key in hotelServiceNames)
            put(key, hotelStatus == HotelStatus.ready && hotelServicesSupported && hotelServices.optBoolean(key))
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
            if (value != "off" && value != "recording" && value != "unknown" && value != "muted") return
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
        if (type == "caption" && message.optString("field") == "original" && message.optString("text").isNotBlank())
            lastActivity = SystemClock.elapsedRealtime()
        if (current(lease)) reception?.push(message)
    }

    private fun begin(request: SessionOwner.Request) {
        if (updater.isInstalling()) {
            reception?.push(JSONObject().put("type", "audio_state").put("state", "off")
                .put("session_id", request.id).put("generation", 0))
            reception?.push(JSONObject().put("type", "reception_event").put("phase", "error")
                .put("text", text("An app update is being installed. Please wait for it to finish before starting a conversation.", language = request.guest))
                .put("session_id", request.id).put("generation", 0))
            return
        }
        val usesHotel = request.mode == "reception" || TranslationRouting.useManagedConnection(backendConfigured(),
            TranslationSettings(this).directFishSelected, BuildConfig.DISTRIBUTION)
        val readinessError = when {
            request.mode == "translation" && BuildConfig.DISTRIBUTION == "hotel" && !backendConfigured() ->
                "Translation needs hotel setup. Please ask staff."
            usesHotel -> hotelBlockReason().let { if (request.mode == "translation" && hotelStatus == HotelStatus.ready) "" else it }
            else -> ""
        }
        if (readinessError.isNotEmpty()) {
            reception?.push(JSONObject().put("type", "audio_state").put("state", "off")
                .put("session_id", request.id).put("generation", 0))
            reception?.push(JSONObject().put("type", "reception_event").put("phase", "error")
                .put("text", text(readinessError, language = request.guest))
                .put("session_id", request.id).put("generation", 0))
            return
        }
        val blocked = if (request.mode == "translation") fish.admissionIssue() else null
        if (blocked != null) {
            fish.retryCleanup()
            // The WebView has proposed an ID but native admission has not acquired it.
            // Reject that proposal explicitly; do not leave the screen stuck in Connecting.
            audioState = fish.localAudioState()
            val last = owner.lastReleasedLease
            if (last != null && audioState == "off") owner.confirmLateRelease(last.request.id, last.generation)
            reception?.push(JSONObject().put("type", "audio_state").put("state", audioState)
                .put("session_id", request.id).put("generation", 0))
            reception?.push(JSONObject().put("type", "reception_event").put("phase", "error").put("text",
                if (BuildConfig.DISTRIBUTION == "hotel") text("Translation is unavailable. Please ask staff.", language = request.guest) else blocked)
                .put("session_id", request.id).put("generation", 0))
            return
        }
        val lease = owner.acquire(request) ?: run { notice(text("Wait until the previous microphone release is confirmed before starting again.")); return }
        lastActivity = SystemClock.elapsedRealtime()
        audioState = "off"
        phase(lease, "connecting")
        audio(lease, "off")
        if (request.mode == "reception") {
            connect(lease)
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingPermission = lease
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 5)
        } else connect(lease)
    }

    private fun connect(lease: SessionOwner.Lease) {
        if (!current(lease) || !foreground) return
        val request = lease.request
        if (request.mode == "translation" && BuildConfig.DISTRIBUTION == "hotel" && !backendConfigured()) {
            stopSession("error", text("Translation needs hotel setup. Please ask staff.", language = request.guest))
            return
        }
        if (request.mode == "translation" && TranslationRouting.useManagedConnection(backendConfigured(),
                TranslationSettings(this).directFishSelected, BuildConfig.DISTRIBUTION) &&
            hotelBlockReason().isNotEmpty() && hotelStatus != HotelStatus.ready) {
            stopSession("error", text("Translation is unavailable. Please ask staff.", language = request.guest))
            return
        }
        if (request.mode == "reception") {
            gateway?.dispose()
            val credentials = hotelConnection.load()
            gateway = HotelGateway(credentials?.base.orEmpty(), credentials?.token.orEmpty(),
                { currentLease, event -> runOnUiThread { if (current(currentLease)) push(currentLease, event) } },
                { currentLease, event -> runOnUiThread { if (current(currentLease)) dialOperation(currentLease, event) } },
                { currentLease, message, values -> text(message, values, currentLease.request.guest) },
                connectionFailure = { failedState -> runOnUiThread {
                    if (current(lease) && hotelConnection.load() == credentials) {
                        cancelHotelProbe()
                        hotelStatus = if (failedState == "unauthorized") HotelStatus.unauthorized else HotelStatus.offline
                        hotelStatusAt = SystemClock.elapsedRealtime()
                        pushState()
                    }
                } })
                .also { it.begin(lease) }
            audio(lease, "off")
            phase(lease, "ready")
            return
        } else {
            // The only approved route is the Fish-hosted interpreter; never fall back to another provider.
            engine = Engine.FISH
            fish.start(request.guest, request.staff, request.mode, request.id, lease.generation, request.greeting)
        }
    }

    private fun stopSession(reason: String = "stopped", text: String = "", done: (() -> Unit)? = null) {
        pendingPermission = null
        if (done != null) afterRelease.add(done)
        val lease = owner.beginClose()
        if (lease == null) { if (owner.current == null) finishReleaseActions(); return }
        gateway?.end()
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
            null -> released()
        }
    }

    private fun finishReleaseActions() {
        while (afterRelease.isNotEmpty()) afterRelease.removeAt(0).invoke()
        updater.tryInstallIfIdle()
        consumeMaintenance()
    }


    private fun ringBell() {
        if (!ringing.compareAndSet(false, true)) return
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 350)
            ui.postDelayed({ tone.release(); ringing.set(false) }, 400)
        } catch (_: Exception) { ringing.set(false) }
    }

    private fun openStaffSetup(voiceOnly: Boolean = false) = stopSession(done = {
        if (!destroyed && foreground) {
            val hotel = BuildConfig.DISTRIBUTION == "hotel"
            val setup = Intent(this, KeyEntryActivity::class.java).putExtra("voice_only", voiceOnly && !hotel)
            if (voiceOnly || hotel) {
                if (hotel) awaitingStaffSetup = true
                @Suppress("DEPRECATION")
                startActivityForResult(setup, if (hotel) 42 else 41)
            } else startActivity(setup)
        }
    })

    private fun openDialer(number: String): String {
        if (!number.matches(Regex("\\+[1-9][0-9]{6,14}"))) return "failed"
        return try { startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))); "dialer_opened" }
        catch (_: Exception) { "failed" }
    }
    private fun dialOperation(lease: SessionOwner.Lease, response: JSONObject) {
        val number = response.optString("number", response.optJSONObject("result")?.optString("number").orEmpty())
        val status = openDialer(number)
        push(lease, JSONObject(response.toString()).put("type", "operation_result").put("status", status)
            .put("text", if (status == "dialer_opened") text("The phone dialer is open. No answered call or taxi booking is confirmed.") else text("This device could not open the dialer. Ask reception to call for you.")))
    }

    private fun onUpdate(status: String) {
        updateStatus = status
        val page = reception ?: return
        if (BuildConfig.DISTRIBUTION == "hotel") return
        when {
            status.startsWith("checking:") -> page.push(JSONObject().put("type", "update_status").put("phase", "checking").put("text", text("Checking for updates…")))
            status.startsWith("current:") -> page.push(JSONObject().put("type", "update_status").put("phase", "current").put("text", text("Up to date · {version}", mapOf("version" to BuildConfig.VERSION_NAME))))
            status.startsWith("offer:") -> page.push(JSONObject().put("type", "update_status").put("phase", "ready")
                .put("version", status.removePrefix("offer:"))
                .put("text", text("Update {version} downloaded. It will apply when this device is idle.", mapOf("version" to status.removePrefix("offer:")))))
            status.startsWith("waiting:") -> page.push(JSONObject().put("type", "update_status").put("phase", "ready")
                .put("text", updateText(status.removePrefix("waiting:"))))
            status.startsWith("progress:") -> {
                val value = status.removePrefix("progress:")
                val percent = value.toIntOrNull()
                val text = if (percent != null) { if (percent >= 0) text("Downloading {percent}%", mapOf("percent" to percent.toString())) else text("Starting download…") }
                    else if (value.startsWith("m") && value.removePrefix("m").toLongOrNull() != null) text("Downloading {size} MB", mapOf("size" to value.removePrefix("m")))
                    else updateText(value.ifBlank { "Starting download…" })
                page.push(JSONObject().put("type", "update_status").put("phase", "progress").put("percent", percent ?: -1).put("text", text))
            }
            status.startsWith("confirm:") -> page.push(JSONObject().put("type", "update_status").put("phase", "confirm").put("text", updateText(status.removePrefix("confirm:"))))
            status.startsWith("failed:") -> page.push(JSONObject().put("type", "update_status").put("phase", "failed").put("text", updateText(status.removePrefix("failed:"))))
            status.startsWith("installed") -> page.push(JSONObject().put("type", "update_status").put("phase", "installed").put("text", text("Updated")))
        }
    }

    private fun handle(message: JSONObject) {
        val action = message.optString("action")
        if (BuildConfig.DISTRIBUTION == "hotel" &&
            (action == "appearance" || action == "night" || action == "check_updates" || action == "apply_update")) return
        when (action) {
            "stop", "message", "confirm", "checkin", "operator", "taxi", "question" ->
                if (!owner.accepts(message.optString("session_id"), message.optInt("generation", -1), duringClose = true)) return
            "watch_request", "unwatch_request" ->
                if (!foreground || owner.current?.request?.mode != "reception" ||
                    !owner.accepts(message.optString("session_id"), message.optInt("generation", -1))) return
            "silence", "activity", "mute" -> if (owner.current != null &&
                !owner.accepts(message.optString("session_id"), message.optInt("generation", -1))) return
        }
        when (action) {
            "appearance" -> {
                val value = message.optString("value")
                if (value == "dark" || value == "light") setAppearance(value)
            }
            "night" -> if (owner.current == null) {
                night = message.optBoolean("enabled", !night)
                deck.edit().putBoolean("reception_night", night).apply()
                reception?.push(JSONObject().put("type", "night_mode").put("enabled", night))
            }
            "languages" -> if (owner.current?.request?.mode != "translation") {
                val guest = message.optString("language"); val staff = message.optString("staff_language")
                if (DeskLanguages.has(guest) && DeskLanguages.has(staff)) {
                    val changed = guest != guestLocale()
                    deck.edit().putString("reception_guest_language", guest).putString("reception_staff_language", staff).apply()
                    if (changed) { cancelHotelProbe(); refreshHotelStatus() }
                    pushState()
                }
            }
            "bell" -> ringBell()
            "settings" -> openStaffSetup()
            "voice_setup" -> openStaffSetup(voiceOnly = true)
            "info" -> pushState()
            "start" -> {
                val id = message.optString("session_id")
                val mode = message.optString("mode")
                val guest = message.optString("language"); val staff = message.optString("staff_language")
                val validId = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
                if (!validId || !DeskLanguages.has(guest) || !DeskLanguages.has(staff) || (mode != "translation" && mode != "reception") || (mode == "translation" && guest == staff)) {
                    notice(text("Choose a supported mode and languages; translation needs two different languages."))
                    return
                }
                begin(SessionOwner.Request(id, mode, guest, staff, message.optString("greet")))
            }
            "stop" -> stopSession()
            "message" -> dispatchHotelAction(message)
            "confirm" -> owner.current?.takeIf { current(it) }?.let { lease ->
                val id = message.optString("draft_id"); val approved = message.optBoolean("approved")
                val draftAction = gateway?.confirmationAction(lease, id)
                if (draftAction == null) notice(text("This request is no longer available. Review a new request."))
                else {
                    val reason = if (!approved || gateway?.canReconcileConfirmation(lease, id, approved) == true) ""
                        else hotelBlockReason(draftAction)
                    if (reason.isEmpty()) gateway?.confirm(lease, id, approved)
                    else push(lease, JSONObject().put("type", "operation_result").put("status", "unavailable")
                        .put("text", text(reason, language = lease.request.guest)))
                }
            }
            "checkin", "operator", "taxi", "question" -> dispatchHotelAction(message)
            "watch_request" -> owner.current?.let { gateway?.watch(it, message.optString("item_id")) }
            "unwatch_request" -> owner.current?.let { gateway?.unwatch(it) }
            "activity" -> lastActivity = SystemClock.elapsedRealtime()
            "mute" -> owner.current?.takeIf { current(it) && it.request.mode == "translation" }
                ?.let { fish.setMuted(message.optBoolean("muted")) }
            "silence" -> if (owner.current != null) stopSession()
            "exit" -> stopSession(done = { finish() })
            "check_updates" -> updater.check()
            "apply_update" -> if (canInstallUpdate()) updater.installOffered()
                else onUpdate("confirm:The downloaded update will wait until the conversation and audio cleanup finish.")
        }
    }

    private fun dispatchHotelAction(message: JSONObject) {
        val lease = owner.current?.takeIf { current(it) && it.request.mode == "reception" } ?: return
        val action = message.optString("action")
        val arguments = message.optJSONObject("arguments") ?: JSONObject()
        // An uncertain submitted call must reconcile its original identity, even when a later probe fails.
        val reason = if (gateway?.canReconcile(lease, action, arguments) == true) "" else hotelBlockReason(action)
        if (reason.isNotEmpty()) {
            push(lease, JSONObject().put("type", "operation_result").put("status", "unavailable")
                .put("text", text(reason, language = lease.request.guest)))
            return
        }
        gateway?.action(lease, action, arguments)
    }

    private fun consumeMaintenance() {
        val action = pendingMaintenance ?: return
        if (!canInstallUpdate()) return
        pendingMaintenance = null
        when (action) {
            "check_updates" -> updater.check()
            "apply_update" -> updater.installOffered()
            "appearance_light" -> setAppearance("light")
            "appearance_dark" -> setAppearance("dark")
            "night_on", "night_off" -> {
                night = action == "night_on"
                deck.edit().putBoolean("reception_night", night).apply()
            }
        }
        pushState()
    }

    private fun setAppearance(value: String) {
        deck.edit().putString("reception_appearance", value).apply()
        window.setBackgroundDrawable(ColorDrawable(if (value == "dark") Color.rgb(8, 15, 21) else Color.rgb(243, 245, 242)))
        reception?.push(JSONObject().put("type", "appearance").put("value", value))
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
        UiText.preload(this)
        val dark = deck.getString("reception_appearance", "dark") != "light"
        window.setBackgroundDrawable(ColorDrawable(if (dark) Color.rgb(8, 15, 21) else Color.rgb(243, 245, 242)))
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = OnBackInvokedCallback { navigateBack() }.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
            }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        reception = ReceptionView(this, ::handle) { stopSession("error", text("The reception screen stopped. Return to the launcher and reopen Reception.")) }
        setContentView(FrameLayout(this).apply { addView(reception, FrameLayout.LayoutParams(-1, -1)) })
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        night = deck.getBoolean("reception_night", false)
        ui.post(watchdog)
    }
    override fun onResume() {
        super.onResume()
        foreground = true
        reception?.setShown(true)
        refreshHotelStatus()
        ui.removeCallbacks(hotelPoll)
        ui.postDelayed(hotelPoll, 25_000L)
        pushState()
        if (returnToTranslation) {
            returnToTranslation = false
            reception?.push(JSONObject().put("type", "translation_setup_return"))
        }
        // Permission results can arrive before onResume. Start only after this
        // window is foreground again, and consume the pending request once.
        pendingPermission?.takeIf {
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }?.let { lease ->
            pendingPermission = null
            if (current(lease)) connect(lease)
        }
        updater.onForeground()
        consumeMaintenance()
        updater.check()
        ui.removeCallbacks(updatePoll)
        ui.postDelayed(updatePoll, 10 * 60_000L)
    }
    @Deprecated("Receives the internal translation setup result for API 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Restore only non-sensitive language selection, never a prior conversation.
        // Both Save and Cancel return here; neither starts microphone capture.
        if (requestCode == 41) returnToTranslation = true
        if (requestCode == 42) {
            val protectedReturn = awaitingStaffSetup
            awaitingStaffSetup = false
            if (protectedReturn && resultCode == RESULT_OK && BuildConfig.DISTRIBUTION == "hotel") {
                data?.getStringExtra("maintenance_action")?.takeIf { it in maintenanceActions }?.let {
                    pendingMaintenance = it
                    consumeMaintenance()
                }
            }
        }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != 5) return
        val lease = pendingPermission ?: return
        if (!current(lease)) { pendingPermission = null; return }
        if (results.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            pendingPermission = null
            stopSession("error", text("Microphone permission is required. No conversation has started."))
        } else if (foreground) {
            pendingPermission = null
            connect(lease)
        }
    }
    override fun onPause() {
        foreground = false
        ui.removeCallbacks(updatePoll)
        ui.removeCallbacks(hotelPoll)
        cancelHotelProbe()
        owner.current?.let { gateway?.unwatch(it) }
        // The system permission dialog pauses us without hiding this window.
        // No microphone exists yet; retain its proposed session and visible UI.
        if (pendingPermission == null) {
            stopSession()
            reception?.setShown(false)
        }
        super.onPause()
    }
    override fun onStop() {
        // Leaving the app while permission is pending must cancel the proposal.
        // A later permission grant must never start capture in the background.
        if (pendingPermission != null) {
            stopSession()
            reception?.setShown(false)
        }
        super.onStop()
    }
    override fun onDestroy() {
        destroyed = true
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        ui.removeCallbacks(watchdog)
        ui.removeCallbacks(updatePoll)
        ui.removeCallbacks(hotelPoll)
        cancelHotelProbe()
        readinessClient.connectionPool.evictAll()
        readinessClient.dispatcher.executorService.shutdown()
        stopSession(done = {
            if (fishProvider.isInitialized()) fish.release()
            gateway?.dispose()
        })
        reception?.dispose(); reception = null
        super.onDestroy()
    }
}
