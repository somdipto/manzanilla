package com.agentdeck.hotel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.DataPublishReliability
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import kotlin.math.sqrt

/** Interpreter transport only. Credentials are staff-saved; managed sessions use the trusted hotel service. */
class FishVoice(private val ctx: Context, private val push: (JSONObject) -> Unit, private val key: () -> String) {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val meta = ctx.getSharedPreferences("fishmeta", Context.MODE_PRIVATE)
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private inner class Run(val owner: VoiceSession) {
        val timing = AudioTurnTiming(SystemClock::elapsedRealtime) { Log.i("AudioTiming", "source=interpreter-sdk $it") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val calls = ConcurrentHashMap.newKeySet<Call>()
        var room: Room? = null
        var endRequest: Request? = null
        val sinks = ArrayList<Pair<RemoteAudioTrack, AudioTrackSink>>()
        val captions = LiveKitCaptions()
        var previousMode: Int? = null
        var previousSpeaker = false
        @Volatile var recording = false
        @Volatile var outputLevel = 0f
        @Volatile var outputAt = 0L
    }
    @Volatile private var current: Run? = null

    @Synchronized fun start(guest: String, staff: String, mode: String = "translation", sessionId: String = "", epoch: Int = 0) {
        if (current != null) return
        val run = Run(VoiceSession(sessionId, epoch, guest, staff, mode, push))
        current = run
        run.owner.emit(JSONObject().put("type", "audio_state").put("state", "off"))
        if (!AudioPolicy.allowStart { terminate(run, "error", it) }) return
        if (mode != "translation") { terminate(run, "error", "Assistance requires the configured hotel service; an interpreter cannot provide it."); return }
        if (!DeskLanguages.has(guest) || !DeskLanguages.has(staff) || guest == staff) {
            terminate(run, "error", "Choose two different languages for translation."); return
        }
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
        run.scope.launch(Dispatchers.IO) {
            try {
                val session = if (connection != null) managedSession(run, connection.base, connection.token, guest, staff) else {
                    val agent = ensureAgent(run, apiKey, BuildConfig.FISH_VOICE_ID.ifBlank { FishPlan.DEFAULT_VOICE })
                    createSession(run, apiKey, agent, guest, staff)
                }
                withContext(Dispatchers.Main) { connect(run, session) }
            } catch (_: CancellationException) { }
            catch (_: Throwable) { terminate(run, "error", "Translation could not connect. Check the internet connection or ask staff to check service access.") }
        }
    }

    fun greetingDone() { current?.let { run -> run.owner.greeted { capture(run) } } }
    fun stop(onStopped: () -> Unit = {}) { val run = current; if (run == null) onStopped() else detach(run, null, onStopped) }
    fun release() = stop()

    private suspend fun connect(run: Run, session: Session) {
        if (!run.owner.active()) return
        val live = LiveKit.create(ctx.applicationContext)
        if (!run.owner.use { run.room = live }) { live.release(); return }
        run.scope.launch {
            live.events.collect { event ->
                if (!run.owner.active()) return@collect
                when (event) {
                    is RoomEvent.DataReceived -> if (event.topic == "agent-event") onAgent(run, event.data)
                    is RoomEvent.ParticipantAttributesChanged -> when (event.changedAttributes["lk.agent.state"]) {
                        "speaking" -> run.owner.phase("speaking")
                        "thinking" -> run.owner.phase("thinking")
                        "listening", "idle" -> {
                            run.timing.outputComplete(sdkState = true)
                            if (run.recording) run.owner.phase("listening")
                        }
                    }
                    is RoomEvent.TrackSubscribed -> (event.track as? RemoteAudioTrack)?.let { track -> attachOutput(run, track) }
                    is RoomEvent.Reconnecting -> run.owner.phase("connecting", "Audio connection interrupted; reconnecting.")
                    is RoomEvent.Reconnected -> if (run.recording) run.owner.phase("listening")
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
                        run.captions.replace(stream, text.toString(), false)?.let { run.owner.emit(it) }
                    }
                    run.captions.replace(stream, text.toString(), true)?.let { run.owner.emit(it) }
                } catch (_: CancellationException) { }
                catch (_: Exception) { run.owner.emit(JSONObject().put("type", "notice").put("status", "unavailable").put("text", "A transcript segment was unavailable; audio may continue.")) }
            }
        }
        live.connect(session.url, session.token)
        if (!run.owner.active()) return
        run.owner.ready { capture(run) }
        run.scope.launch {
            while (run.owner.active()) {
                val now = SystemClock.elapsedRealtime()
                run.owner.emit(JSONObject().put("type", "energy").put("level", if (now - run.outputAt <= 120) run.outputLevel else 0f))
                if (run.recording) {
                    val level = live.localParticipant.audioLevel
                    run.timing.inputLevel(level, 0.03f, now)
                    run.owner.emit(JSONObject().put("type", "mic_level").put("level", level))
                }
                delay(50)
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
                check(live.localParticipant.setMicrophoneEnabled(true))
                run.owner.use {
                    run.recording = true
                    run.owner.emit(JSONObject().put("type", "audio_state").put("state", "recording"))
                    run.owner.emit(JSONObject().put("type", "notice").put("status", "unverified")
                        .put("text", "Audio echo-control capability is not reported by this SDK path; echo performance has not been physically verified."))
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
                var sum = 0.0
                val start = audioData.position()
                val bytes = minOf(audioData.remaining(), numberOfFrames * numberOfChannels * 2)
                var offset = 0
                while (offset + 1 < bytes) {
                    val value = ((audioData.get(start + offset).toInt() and 255) or (audioData.get(start + offset + 1).toInt() shl 8)).toDouble()
                    sum += value * value
                    offset += 2
                }
                run.outputLevel = if (offset == 0) 0f else (sqrt(sum / (offset / 2)) / 32768.0).toFloat().coerceIn(0f, 1f)
                run.outputAt = SystemClock.elapsedRealtime()
                run.timing.sdkDecodedReceived()
            }
        }
        run.owner.use { track.addSink(sink); run.sinks.add(track to sink) }
    }

    private fun onAgent(run: Run, data: ByteArray) {
        val event = try { JSONObject(String(data, Charsets.UTF_8)) } catch (_: Exception) { return }
        when (event.optString("type")) {
            // Observed live: recovered=true precedes successful continued speech.
            "error" -> if (event.optBoolean("recovered")) {
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
        run.calls.forEach { it.cancel() }
        cleanup.launch {
            var off = true
            run.scope.coroutineContext[Job]?.cancelAndJoin()
            val live = run.room
            run.sinks.forEach { (track, sink) -> try { track.removeSink(sink) } catch (_: Exception) { off = false } }
            if (live != null) {
                withTimeoutOrNull(1000) { try { live.localParticipant.publishData("""{"type":"user.hangup"}""".toByteArray(), DataPublishReliability.RELIABLE, "client-event") } catch (_: Exception) { } }
                try { live.disconnect() } catch (_: Exception) { off = false }
                try { live.release() } catch (_: Exception) { off = false }
            }
            run.previousMode?.let { previous ->
                val manager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                try {
                    manager.mode = previous
                    @Suppress("DEPRECATION")
                    manager.isSpeakerphoneOn = run.previousSpeaker
                } catch (_: Exception) { off = false }
            }
            // End the paid provider session as well as releasing local WebRTC resources.
            run.endRequest?.let { request ->
                try {
                    val call = http.newCall(request)
                    call.timeout().timeout(10, TimeUnit.SECONDS)
                    call.execute().use { response ->
                        if (!response.isSuccessful) Log.w("FishVoice", "Provider session end was not acknowledged")
                    }
                } catch (_: Exception) { Log.w("FishVoice", "Provider session end could not be confirmed") }
            }
            synchronized(this@FishVoice) { if (current === run) current = null }
            run.owner.detached(off, terminal)
        }
    }

    private fun managedSession(run: Run, base: String, token: String, guest: String, staff: String): Session {
        val body = JSONObject().put("guest_language", guest).put("staff_language", staff).put("mode", "translation")
        val request = Request.Builder().url("$base/api/device/fish-session").header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return parseSession(run, execute(run, request), "$base/api/device/fish-session", token)
    }
    private fun ensureAgent(run: Run, apiKey: String, voiceId: String): String {
        val staff = run.owner.staff
        val scope = MessageDigest.getInstance("SHA-256")
            .digest("$apiKey:$voiceId:$staff:${FishPlan.CONFIG_REV}".toByteArray())
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
        parseSession(run, call(run, apiKey, "POST", "/v1/agent/sessions", FishPlan.sessionBody(id, guest, staff)),
            "https://api.fish.audio/v1/agent/sessions", apiKey)
    private fun parseSession(run: Run, json: JSONObject, sessionBase: String, credential: String): Session {
        check(json.optString("transport") == "livekit") { "Unsupported transport" }
        val url = json.getString("livekit_url")
        val token = json.getString("token")
        check(url.startsWith("wss://") && token.isNotBlank()) { "Invalid transport ticket" }
        val id = json.getString("session_id")
        check(id.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid session identity" }
        run.endRequest = Request.Builder().url("$sessionBase/$id/end")
            .header("Authorization", "Bearer $credential")
            .post("{}".toRequestBody("application/json".toMediaType())).build()
        return Session(url, token)
    }
    private fun call(run: Run, apiKey: String, method: String, path: String, body: JSONObject?): JSONObject {
        val builder = Request.Builder().url("https://api.fish.audio$path").header("Authorization", "Bearer $apiKey")
        if (method == "GET") builder.get() else builder.method(method, (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
        return execute(run, builder.build())
    }
    private fun execute(run: Run, request: Request): JSONObject {
        val call = http.newCall(request)
        if (!run.owner.use { run.calls.add(call) }) throw CancellationException("Session closed")
        try {
            call.execute().use { response ->
                check(response.isSuccessful) { "Service request rejected" }
                val text = response.body?.string().orEmpty()
                return if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        } finally { run.calls.remove(call) }
    }
    private data class Session(val url: String, val token: String)
}
