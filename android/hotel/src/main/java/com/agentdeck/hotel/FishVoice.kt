package com.agentdeck.hotel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.RemoteAudioTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import livekit.org.webrtc.AudioTrackSink
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Interpreter transport only. Credentials are staff-saved; managed sessions use the trusted hotel service. */
class FishVoice(ctx: Context, private val push: (JSONObject) -> Unit, private val key: () -> String) {
    private val ctx = ctx.applicationContext
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    // Managed creation has a 60s server deadline. Keep its dispatched response recoverable on Stop.
    private val sessionHttp = http.newBuilder().readTimeout(75, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS).build()
    private val meta = ctx.getSharedPreferences("fishmeta", Context.MODE_PRIVATE)
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val admission = admission(this.ctx)
    private inner class Run(val owner: VoiceSession, val greet: String) {
        val timing = AudioTurnTiming(SystemClock::elapsedRealtime) {
            Log.i("AudioTiming", "source=interpreter-sdk session_id=${owner.id} generation=${owner.generation} $it")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        // HTTP creation can finish late after cancellation. It must not delay local media release.
        val creation = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val reservation = "${owner.id}:${owner.generation}"
        @Volatile var sessionRequested = false
        @Volatile var sessionCall: Call? = null
        @Volatile var localOff = false
        @Volatile var endUncertain = false
        @Volatile var endJob: Job? = null
        var endInProgress = false
        val calls = ConcurrentHashMap.newKeySet<Call>()
        var room: Room? = null
        @Volatile var endRequest: Request? = null
        @Volatile var creationRecovery: Request? = null
        val sinks = ArrayList<Pair<RemoteAudioTrack, AudioTrackSink>>()
        val captions = LiveKitCaptions()
        val interpreter = InterpreterCaptions()
        var previousMode: Int? = null
        var previousSpeaker = false
        @Volatile var recording = false
        @Volatile var muted = false
    }
    @Volatile private var current: Run? = null

    fun admissionIssue(): String? = admission.reason() ?: if (current != null) "The previous conversation cleanup is still finishing." else null
    fun localAudioState(): String = admission.localAudioState()

    /** A deliberate subsequent start attempt can retry the same uncertain end, never mint a new session. */
    fun retryCleanup() {
        val run = current ?: run {
            admission.retrySettledRelease()
            retryRecoveredEnd()
            return
        }
        synchronized(run) {
            if (!run.endUncertain || (run.endRequest == null && run.creationRecovery == null) || run.endInProgress || run.endJob?.isActive == true) return
            run.endJob = cleanup.launch { endProvider(run) }
        }
    }

    private fun retryRecoveredEnd() {
        val reservation = admission.recoveryReservation() ?: return
        val url = meta.getString("pending_end_url", null) ?: meta.getString("pending_recovery_url", null)
        if (url == null) {
            // acquire is persisted before agent setup. Dispatch always persists recovery first.
            // Only the dead process's reservation qualifies; a live setup cannot be released here.
            if (!meta.contains("pending_end_url") && !meta.contains("pending_recovery_url") &&
                !meta.contains("pending_end_credential_hash") && !meta.contains("pending_recovery_lookup")) {
                admission.releaseRestoredBeforeDispatch(reservation)
            }
            return
        }
        val connection = HotelConnection(ctx).load()
        val lookup = !meta.contains("pending_end_url") && meta.getBoolean("pending_recovery_lookup", false)
        val direct = url.matches(Regex("https://api\\.fish\\.audio/v1/agent/sessions/[A-Za-z0-9_-]+/end")) ||
            (lookup && url == directLookup(reservation))
        val managed = connection != null && (
            (url.startsWith("${connection.base}/api/device/fish-session/") &&
                url.removePrefix("${connection.base}/api/device/fish-session/").matches(Regex("[A-Za-z0-9_-]+/end"))) ||
            url == "${connection.base}/api/device/fish-session-requests/${URLEncoder.encode(reservation, "UTF-8")}/end")
        val credential = if (direct) key().trim() else if (managed) connection!!.token else return
        if (credential.isBlank() || credentialHash(credential) != meta.getString("pending_end_credential_hash", null)) return
        if (!admission.beginRecovery(reservation)) return
        recoveredEndJob = cleanup.launch {
            val builder = Request.Builder().url(url).header("Authorization", "Bearer $credential")
            val request = if (lookup) builder.get().build()
                else builder.post("{}".toRequestBody("application/json".toMediaType())).build()
            val acknowledged = confirmEnd(request)
            admission.remoteEnded(reservation, acknowledged)
            Log.i("AudioTiming", "endpoint=recovered_provider_end acknowledged=$acknowledged receipt_ms=${SystemClock.elapsedRealtime()}")
        }
    }

    @Synchronized fun start(guest: String, staff: String, mode: String = "translation", sessionId: String = "", epoch: Int = 0, greet: String = "") {
        if (current != null) return
        val run = Run(VoiceSession(sessionId, epoch, guest, staff, mode, push), greet)
        if (!admission.acquire(run.reservation)) {
            // Activity already owns this proposed generation and needs a terminal acknowledgement.
            // Do not detach this unadmitted run: that could release another run's reservation.
            run.owner.close()
            run.owner.detached(admission.localAudioState() == "off", JSONObject().put("type", "reception_event")
                .put("phase", "error").put("text", admission.reason()
                    ?: "Conversation cleanup ownership could not be saved on this device. Ask staff to check device storage."))
            return
        }
        current = run
        run.owner.emit(JSONObject().put("type", "audio_state").put("state", "off"))
        run.timing.marker("run_started_requested_config_rev_${FishPlan.CONFIG_REV}")
        if (!AudioPolicy.allowStart { terminate(run, "error", it) }) return
        if (mode != "translation") { terminate(run, "error", "Assistance requires the configured hotel service; an interpreter cannot provide it."); return }
        if (!DeskLanguages.has(guest) || !DeskLanguages.has(staff) || guest == staff) {
            terminate(run, "error", "Choose two different languages for translation."); return
        }
        run.timing.marker("requested_asr_${FishPlan.recognitionModel(guest, staff)}")
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            terminate(run, "error", "Microphone permission is needed for translation."); return
        }
        val connection = HotelConnection(ctx).load()
        val managed = connection != null
        val apiKey = if (managed) "" else key().trim()
        if (!managed && apiKey.isBlank()) {
            terminate(run, "error", "Ask staff to configure or recover the translation credential in Settings."); return
        }
        run.owner.phase("connecting")
        run.creation.launch {
            try {
                val session = if (connection != null) managedSession(run, connection.base, connection.token, guest, staff) else {
                    val agent = ensureAgent(run, apiKey, BuildConfig.FISH_VOICE_ID.ifBlank { FishPlan.DEFAULT_VOICE })
                    createSession(run, apiKey, agent, guest, staff)
                }
                run.timing.marker("provider_ticket_received")
                run.scope.launch {
                    try { connect(run, session) }
                    catch (_: CancellationException) { }
                    catch (_: Throwable) { terminate(run, "error", "Translation transport could not connect. Ask staff to check the connection.") }
                }
            } catch (_: CancellationException) { }
            catch (_: Throwable) { terminate(run, "error", "Translation could not connect. Check the internet connection or ask staff to check service access.") }
        }
    }

    /** Guest-controlled microphone gate. Muting never releases capture or touches session ownership. */
    fun setMuted(muted: Boolean) {
        val run = current ?: return
        run.scope.launch {
            val live = run.room ?: return@launch
            if (!run.owner.active() || run.muted == muted) return@launch
            try {
                check(live.localParticipant.setMicrophoneEnabled(!muted))
                if (!run.owner.use { run.muted = muted }) return@launch
                run.timing.marker(if (muted) "microphone_muted" else "microphone_unmuted")
                run.owner.emit(JSONObject().put("type", "audio_state").put("state", if (muted) "muted" else "recording"))
            } catch (_: CancellationException) { }
            catch (_: Throwable) { run.owner.emit(JSONObject().put("type", "notice").put("status", "unavailable")
                .put("text", "The microphone state did not change. Try again or stop the conversation.")) }
        }
    }

    fun stop(onStopped: () -> Unit = {}) { val run = current; if (run == null) onStopped() else detach(run, null, onStopped) }
    fun release() = stop()

    private suspend fun connect(run: Run, session: Session) {
        if (!run.owner.active()) return
        run.timing.marker("transport_connect_started")
        val live = LiveKit.create(ctx.applicationContext, overrides = LiveKitOverrides(audioOptions = AudioOptions(
            javaAudioDeviceModuleCustomizer = { builder ->
                builder.setUseHardwareAcousticEchoCanceler(true)
                builder.setUseHardwareNoiseSuppressor(true)
                // WebRTC's Java audio device defaults to normal-latency playout. Request
                // Android's supported low-latency path (API 26+, also our minSdk) while
                // retaining echo cancellation, noise suppression and adaptive buffering.
                builder.setUseLowLatency(true)
                run.timing.marker("android_low_latency_playout_requested")
            }
        )))
        if (!run.owner.use { run.room = live }) { live.release(); return }
        run.scope.launch {
            live.events.collect { event ->
                if (!run.owner.active()) return@collect
                when (event) {
                    is RoomEvent.DataReceived -> if (event.topic == "agent-event") onAgent(run, event.data)
                    is RoomEvent.ParticipantAttributesChanged -> when (event.changedAttributes["lk.agent.state"]) {
                        "speaking" -> run.owner.phase("speaking")
                        "thinking" -> {
                            run.timing.marker("provider_thinking_state_received")
                            run.owner.phase("thinking")
                        }
                        "listening", "idle" -> {
                            run.timing.outputComplete(sdkState = true)
                            if (run.recording) run.owner.phase("listening")
                        }
                    }
                    is RoomEvent.TrackSubscribed -> (event.track as? RemoteAudioTrack)?.let { track -> attachOutput(run, track) }
                    is RoomEvent.Reconnecting -> {
                        run.timing.marker("transport_reconnect_gap_started")
                        run.owner.emit(JSONObject().put("type", "notice").put("text", "Audio connection interrupted. Captions may have a gap; no previous translation will be replayed."))
                        run.owner.phase("connecting", "Audio connection interrupted; reconnecting.")
                    }
                    is RoomEvent.Reconnected -> {
                        run.timing.marker("transport_reconnected")
                        if (run.recording) run.owner.phase("listening")
                    }
                    is RoomEvent.Disconnected -> terminate(run, "error", "Translation disconnected. Please start a new conversation.")
                    else -> Unit
                }
            }
        }
        // Verified Fish wire protocol: segment ID is a speaker's revision key, NOT a translation link.
        // https://docs.fish.audio/agents/deploy/protocol.md#transcription-and-agent-state
        live.registerTextStreamHandler("lk.transcription") { reader, fromIdentity ->
            val attrs = reader.info.attributes
            val mine = fromIdentity.value == live.localParticipant.identity?.value
            val stream = run.captions.begin(fromIdentity.value, attrs["lk.segment_id"].orEmpty(), reader.info.id,
                mine, attrs["lk.transcription_final"] == "true") ?: return@registerTextStreamHandler
            if (mine && stream.final) run.timing.providerInputFinal()
            run.scope.launch(Dispatchers.IO) {
                if (!run.owner.active()) return@launch
                try {
                    val text = StringBuilder()
                    reader.flow.collect { chunk ->
                        text.append(chunk)
                        run.captions.replace(stream, text.toString(), false)?.let { run.interpreter.accept(it).forEach(run.owner::emit) }
                    }
                    run.captions.replace(stream, text.toString(), true)?.let { run.interpreter.accept(it).forEach(run.owner::emit) }
                } catch (_: CancellationException) { }
                catch (_: Exception) { run.owner.emit(JSONObject().put("type", "notice").put("status", "unavailable").put("text", "A transcript segment was unavailable; audio may continue.")) }
            }
        }
        live.connect(session.url, session.token)
        if (!run.owner.active()) return
        run.timing.marker("transport_connected")
        run.owner.ready { capture(run) }
        run.scope.launch {
            while (run.owner.active()) {
                val now = SystemClock.elapsedRealtime()
                if (run.recording && !run.muted) {
                    val level = live.localParticipant.audioLevel
                    run.timing.inputLevel(level, 0.03f, now)
                    run.owner.emit(JSONObject().put("type", "mic_level").put("level", level))
                }
                delay(50)
            }
        }
        run.scope.launch {
            while (run.owner.active()) {
                delay(5000)
                // A single outstanding request per connection; no cross-clock subtraction.
                repeat(2) { direction ->
                    val publisher = direction == 0
                    val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                    val observe = livekit.org.webrtc.RTCStatsCollectorCallback { report ->
                        if (run.owner.active()) report.statsMap.values.forEach { stat ->
                            val allowed = RTC_FIELDS[stat.type].orEmpty()
                            if (allowed.isNotEmpty()) {
                                val values = stat.members.filterKeys { it in allowed }
                                Log.i("AudioTiming", "session_id=${run.owner.id} generation=${run.owner.generation} endpoint=rtc_stats receipt_ms=${SystemClock.elapsedRealtime()} direction=${if (publisher) "publisher" else "subscriber"} type=${stat.type} values=$values")
                            }
                        }
                        done.complete(Unit)
                    }
                    if (publisher) live.getPublisherRTCStats(observe) else live.getSubscriberRTCStats(observe)
                    done.await()
                }
            }
        }
        run.scope.launch {
            delay(FishPlan.CLIENT_CAP_MS)
            terminate(run, "timeout", "Conversation reached its duration limit. Please start a new conversation.")
        }
    }

    private fun capture(run: Run) {
        run.scope.launch {
            if (!run.owner.active()) return@launch
            val manager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            run.owner.use {
                run.previousMode = manager.mode
                @Suppress("DEPRECATION")
                run.previousSpeaker = manager.isSpeakerphoneOn
                manager.mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION")
                manager.isSpeakerphoneOn = true
            }
            try {
                val live = run.room ?: return@launch
                if (!run.owner.active()) return@launch
                run.timing.marker("microphone_enable_requested")
                check(live.localParticipant.setMicrophoneEnabled(!run.muted))
                run.owner.use {
                    run.recording = true
                    run.timing.marker("microphone_enabled")
                    run.owner.emit(JSONObject().put("type", "audio_state").put("state", if (run.muted) "muted" else "recording"))
                    run.owner.phase("listening")
                }
            } catch (_: CancellationException) { }
            catch (_: Throwable) { terminate(run, "error", "Microphone capture could not start. Check permission or contact reception.") }
        }
    }

    private fun attachOutput(run: Run, track: RemoteAudioTrack) {
        val sink = object : AudioTrackSink {
            override fun onData(audioData: ByteBuffer?, bitsPerSample: Int, sampleRate: Int, numberOfChannels: Int,
                                numberOfFrames: Int, absoluteCaptureTimestampMs: Long) {
                if (!run.owner.active() || audioData == null || bitsPerSample != 16) return
                run.timing.sdkDecodedReceived(audioData, minOf(audioData.remaining(), numberOfFrames * numberOfChannels * 2))
            }
        }
        run.owner.use { track.addSink(sink); run.sinks.add(track to sink) }
    }

    private fun onAgent(run: Run, data: ByteArray) {
        val event = try { JSONObject(String(data, Charsets.UTF_8)) } catch (_: Exception) { return }
        when (event.optString("type")) {
            // Observed live: recovered=true precedes successful continued speech.
            "error" -> if (event.optBoolean("recovered")) {
                run.timing.marker("provider_recovered_error_received")
                run.owner.emit(JSONObject().put("type", "notice").put("status", "recovering")
                    .put("text", "The voice service recovered from a temporary interruption."))
            } else terminate(run, "error", "Translation service failed. Please start a new conversation or contact reception.")
            "session.ended" -> terminate(run, if (event.optString("reason") == "conversation_timeout") "timeout" else "stopped", "Conversation ended.")
            // An interpreter is never authorized to execute hotel or client tools.
            "client_tool.call" -> terminate(run, "error", "This translation service requested an unsupported action. Contact reception.")
        }
    }

    private fun terminate(run: Run, phase: String, text: String) =
        detach(run, JSONObject().put("type", "reception_event").put("phase", phase).put("text", text))

    private fun detach(run: Run, terminal: JSONObject?, onStopped: () -> Unit = {}) {
        if (!run.owner.close(onStopped)) return
        run.timing.interrupted()
        run.timing.marker("local_release_started")
        run.creation.coroutineContext[Job]?.cancel()
        run.calls.forEach { if (it !== run.sessionCall) it.cancel() }
        run.endJob = cleanup.launch {
            var off = true
            // Only local producers: never join an in-flight provider HTTP creation here.
            run.scope.coroutineContext[Job]?.cancelAndJoin()
            val live = run.room
            run.sinks.forEach { (track, sink) -> try { track.removeSink(sink) } catch (_: Exception) { off = false } }
            run.sinks.clear()
            if (live != null) {
                try { live.disconnect() } catch (_: Exception) { off = false }
                try { live.release() } catch (_: Exception) { off = false }
            }
            run.previousMode?.let { previous ->
                try {
                    val manager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    manager.mode = previous
                    @Suppress("DEPRECATION")
                    manager.isSpeakerphoneOn = run.previousSpeaker
                } catch (_: Exception) { off = false }
            }
            run.recording = false
            run.localOff = off
            admission.localReleased(run.reservation, off)
            run.timing.marker(if (off) "local_audio_released" else "local_audio_release_uncertain")
            run.owner.detached(off, terminal)
            // A late creation response must bind its end request before the owned cleanup settles.
            run.creation.coroutineContext[Job]?.join()
            endProvider(run)
        }
    }

    private fun endProvider(run: Run) {
        synchronized(run) {
            if (run.endInProgress) return
            run.endInProgress = true
        }
        try {
            val request = run.endRequest ?: run.creationRecovery
            var acknowledged = !run.sessionRequested
            if (run.sessionRequested && request != null) {
                admission.ending(run.reservation)
                run.timing.marker("provider_end_started")
                acknowledged = confirmEnd(request)
            }
            run.endUncertain = !acknowledged
            admission.remoteEnded(run.reservation, acknowledged)
            run.timing.marker(if (acknowledged) "provider_end_acknowledged_or_not_created" else "provider_end_uncertain")
            if (run.localOff) synchronized(this) { if (current === run) current = null }
        } finally { synchronized(run) { run.endInProgress = false } }
    }

    /** Only an exact, per-request provider identity may recover a lost creation response.
     * An empty/eventually-consistent listing is uncertainty, never proof that nothing was created.
     */
    private fun confirmEnd(request: Request): Boolean {
        return try {
            var end = request
            if (request.method == "GET") {
                val query = http.newCall(request)
                query.timeout().timeout(10, TimeUnit.SECONDS)
                val data = query.execute().use { response ->
                    check(response.isSuccessful)
                    JSONObject(response.body?.string().orEmpty())
                }
                val rows = data.getJSONArray("sessions")
                check(!data.getBoolean("has_more") && rows.length() == 1)
                val row = rows.getJSONObject(0)
                check(row.getString("end_user_id") == request.url.queryParameter("end_user_id"))
                if (row.optString("status") == "completed" || row.optString("status") == "failed") return true
                val id = row.getString("session_id")
                check(id.matches(Regex("[A-Za-z0-9_-]{1,200}")))
                end = Request.Builder().url("https://api.fish.audio/v1/agent/sessions/$id/end")
                    .header("Authorization", request.header("Authorization").orEmpty())
                    .post("{}".toRequestBody("application/json".toMediaType())).build()
            }
            val call = http.newCall(end)
            call.timeout().timeout(10, TimeUnit.SECONDS)
            call.execute().use { response ->
                if (end.url.host == "api.fish.audio") response.code == 204
                else response.code == 200 && JSONObject(response.body?.string().orEmpty()).optString("status") == "ended"
            }
        } catch (_: Exception) { false }
    }

    private fun directLookup(reservation: String): String =
        "https://api.fish.audio/v1/agent/sessions?end_user_id=${URLEncoder.encode(reservation, "UTF-8")}&status=pending,active,unknown,completed,failed&page_size=2"

    private fun retainCreationRecovery(run: Run, request: Request) {
        val direct = request.url.host == "api.fish.audio"
        val url = if (direct) directLookup(run.reservation) else
            request.url.toString().removeSuffix("/fish-session") + "/fish-session-requests/${URLEncoder.encode(run.reservation, "UTF-8")}/end"
        val authorization = request.header("Authorization").orEmpty()
        val builder = Request.Builder().url(url).header("Authorization", authorization)
        run.creationRecovery = if (direct) builder.get().build()
            else builder.post("{}".toRequestBody("application/json".toMediaType())).build()
        if (meta.getString("outstanding_session", null) == run.reservation) {
            check(meta.edit().putString("pending_recovery_url", url).putBoolean("pending_recovery_lookup", direct)
                .putString("pending_end_credential_hash", credentialHash(authorization.removePrefix("Bearer "))).commit())
        }
    }

    private fun managedSession(run: Run, base: String, token: String, guest: String, staff: String): Session {
        val body = JSONObject().put("guest_language", guest).put("staff_language", staff).put("mode", "translation")
            .put("request_id", run.reservation)
        val request = Request.Builder().url("$base/api/device/fish-session").header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return parseSession(run, execute(run, request, createsSession = true), "$base/api/device/fish-session", token)
    }
    private fun ensureAgent(run: Run, apiKey: String, voiceId: String): String {
        val staff = run.owner.staff
        val scope = MessageDigest.getInstance("SHA-256")
            .digest("$apiKey:${FishPlan.agentConfig(voiceId, staff)}:${FishPlan.CONFIG_REV}".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val agentName = "${FishPlan.AGENT_NAME} ${FishPlan.CONFIG_REV} $staff ${scope.take(12)}"
        val known = if (meta.getString("scope", "") == scope) meta.getString("agent", "").orEmpty() else ""
        val id = known.ifBlank { findAgent(run, apiKey, agentName) ?: createAgent(run, apiKey, voiceId, staff, agentName) }
        if (known.isBlank()) {
            call(run, apiKey, "PATCH", "/v1/agent/agents/$id/config", FishPlan.agentConfig(voiceId, staff))
            call(run, apiKey, "POST", "/v1/agent/agents/$id/publish", null)
            if (run.owner.active()) meta.edit().remove("rev").putString("agent", id).putString("scope", scope).apply()
        }
        return id
    }
    private fun findAgent(run: Run, apiKey: String, name: String): String? {
        val query = URLEncoder.encode(name, "UTF-8")
        val agents = call(run, apiKey, "GET", "/v1/agent/agents?search=$query&page_size=20", null).optJSONArray("agents") ?: JSONArray()
        for (i in 0 until agents.length()) {
            val agent = agents.getJSONObject(i)
            if (agent.optString("name") == name && agent.optString("status") != "archived") return agent.getString("agent_id")
        }
        return null
    }
    private fun createAgent(run: Run, apiKey: String, voiceId: String, staff: String, name: String): String =
        call(run, apiKey, "POST", "/v1/agent/agents", JSONObject().put("name", name)
            .put("description", "Hotel interpreter").put("config", FishPlan.agentConfig(voiceId, staff))).getString("agent_id")

    private fun createSession(run: Run, apiKey: String, id: String, guest: String, staff: String): Session =
        parseSession(run, call(run, apiKey, "POST", "/v1/agent/sessions", FishPlan.sessionBody(id, guest, staff, run.greet)
            .put("end_user_id", run.reservation)),
            "https://api.fish.audio/v1/agent/sessions", apiKey)
    private fun parseSession(run: Run, json: JSONObject, sessionBase: String, credential: String): Session {
        // Own termination even when the returned transport ticket is malformed.
        val id = json.getString("session_id")
        check(id.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid session identity" }
        // Correlate local monotonic observations with provider history without logging tokens,
        // credentials or guest speech. A session id is not a transport credential.
        Log.i("AudioTiming", "endpoint=provider_session_identity session_id=${run.owner.id} generation=${run.owner.generation} provider_session_id=$id receipt_ms=${SystemClock.elapsedRealtime()}")
        run.endRequest = Request.Builder().url("$sessionBase/$id/end")
            .header("Authorization", "Bearer $credential")
            .post("{}".toRequestBody("application/json".toMediaType())).build()
        if (meta.getString("outstanding_session", null) == run.reservation) {
            check(meta.edit().putString("pending_end_url", run.endRequest!!.url.toString())
                .putString("pending_end_credential_hash", credentialHash(credential)).commit()) { "Could not retain cleanup ownership" }
        }
        check(json.optString("transport") == "livekit") { "Unsupported transport" }
        val url = json.getString("livekit_url")
        val token = json.getString("token")
        check(url.startsWith("wss://") && token.isNotBlank()) { "Invalid transport ticket" }
        return Session(url, token)
    }
    private fun call(run: Run, apiKey: String, method: String, path: String, body: JSONObject?): JSONObject {
        val builder = Request.Builder().url("https://api.fish.audio$path").header("Authorization", "Bearer $apiKey")
        if (method == "GET") builder.get() else builder.method(method, (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
        return execute(run, builder.build(), createsSession = method == "POST" && path == "/v1/agent/sessions")
    }
    private fun execute(run: Run, request: Request, createsSession: Boolean = false): JSONObject {
        val call = (if (createsSession) sessionHttp else http).newCall(request)
        if (!run.owner.use {
            run.calls.add(call)
            if (createsSession) {
                retainCreationRecovery(run, request)
                run.sessionCall = call
                run.sessionRequested = true
                admission.requested(run.reservation)
                run.timing.marker("provider_session_create_requested")
            }
        }) throw CancellationException("Session closed")
        try {
            call.execute().use { response ->
                if (createsSession && !response.isSuccessful &&
                    admission.rejected(run.reservation, request.url.host != "api.fish.audio", response.code,
                        creationNotCreated = response.header("X-Manzanilla-Session-Creation") == "not-created")) {
                    run.sessionRequested = false
                }
                check(response.isSuccessful) { "Service request rejected" }
                val text = response.body?.string().orEmpty()
                return if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        } finally { run.calls.remove(call) }
    }
    private data class Session(val url: String, val token: String)

    companion object {
        private var sharedAdmission: FishSessionAdmission? = null
        @Volatile private var recoveredEndJob: Job? = null
        private val RTC_FIELDS = mapOf(
            "candidate-pair" to setOf("currentRoundTripTime", "availableOutgoingBitrate", "state", "nominated"),
            "inbound-rtp" to setOf("packetsLost", "jitter", "jitterBufferDelay", "jitterBufferEmittedCount", "concealedSamples", "totalSamplesReceived"),
            "outbound-rtp" to setOf("packetsSent", "bytesSent")
        )
        private fun credentialHash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        @Synchronized private fun admission(ctx: Context): FishSessionAdmission =
            sharedAdmission ?: run {
                val meta = ctx.getSharedPreferences("fishmeta", Context.MODE_PRIVATE)
                FishSessionAdmission(meta.getString("outstanding_session", null)) { id ->
                    meta.edit().putString("outstanding_session", id)
                        .remove("pending_end_url").remove("pending_end_credential_hash")
                        .remove("pending_recovery_url").remove("pending_recovery_lookup").commit()
                }.also { sharedAdmission = it }
            }
    }
}
