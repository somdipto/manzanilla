package com.agentdeck.hotel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.util.Log
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.DataPublishReliability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Full-duplex reception test through Fish Agents. Transport is LiveKit; ASR is the agent's
 * configured model (Deepgram by default), not Fish file ASR. The API key never goes into logs.
 */
class FishVoice(
    private val ctx: Context,
    private val push: (JSONObject) -> Unit,
    private val key: () -> String
) {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val generation = AtomicInteger(0)
    private val meta = ctx.getSharedPreferences("fishmeta", Context.MODE_PRIVATE)
    @Volatile private var running = false
    private var job: Job? = null
    private var room: Room? = null

    private fun ev(phase: String) = JSONObject().put("type", "reception_event").put("phase", phase)
    private fun fail(gen: Int, msg: String) {
        if (gen != generation.get()) return
        Log.e("FV", msg)
        push(ev("error").put("text", msg).put("need_key", msg.contains("key", ignoreCase = true)))
        stop()
    }

    fun start(guest: String, staff: String) {
        if (running) return
        val apiKey = key().trim()
        if (apiKey.isBlank()) {
            push(ev("error").put("text", "Add a Fish Audio key in Settings to test live duplex.").put("need_key", true))
            return
        }
        if (!DeskLanguages.has(guest) || !DeskLanguages.has(staff) || guest == staff) {
            push(ev("error").put("text", "Choose two different languages."))
            return
        }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            push(ev("error").put("text", "Microphone permission is needed for live translation."))
            return
        }
        val gen = generation.incrementAndGet()
        running = true
        push(ev("connecting"))
        job = scope.launch(Dispatchers.IO) {
            try {
                val voice = BuildConfig.FISH_VOICE_ID.ifBlank { FishPlan.DEFAULT_VOICE }
                val agent = ensureAgent(apiKey, voice)
                val session = createSession(apiKey, agent, guest, staff)
                if (gen != generation.get()) return@launch
                withContext(Dispatchers.Main) { connect(gen, session) }
            } catch (e: FishCall) {
                fail(gen, e.message ?: "Fish call failed")
            } catch (e: Throwable) {
                if (gen == generation.get()) fail(gen, if (e is java.net.UnknownHostException || e is java.net.ConnectException) "No internet. Live duplex needs a connection." else "Fish duplex failed: ${e.javaClass.simpleName}")
            }
        }
    }

    fun stop() {
        if (!running && room == null && job == null) return
        generation.incrementAndGet()
        running = false
        val old = job
        val live = room
        job = null
        room = null
        old?.cancel()
        scope.launch(Dispatchers.IO) { hangup(live) }
    }

    fun release() {
        stop()
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun connect(gen: Int, session: Session) {
        if (gen != generation.get()) return
        val live = LiveKit.create(ctx.applicationContext)
        room = live
        scope.launch {
            live.events.collect { event ->
                if (gen != generation.get()) return@collect
                when (event) {
                    is RoomEvent.DataReceived -> if (event.topic == "agent-event") onAgent(gen, event.data)
                    is RoomEvent.ParticipantAttributesChanged -> {
                        val state = event.changedAttributes["lk.agent.state"] ?: return@collect
                        val phase = when (state) {
                            "speaking" -> "speaking"
                            "thinking" -> "thinking"
                            "initializing", "listening", "idle" -> "listening"
                            else -> return@collect
                        }
                        push(ev(phase))
                    }
                    is RoomEvent.Disconnected -> if (running && gen == generation.get()) {
                        running = false
                        push(ev("error").put("text", "The duplex call disconnected."))
                    }
                    else -> Unit
                }
            }
        }
        live.registerTextStreamHandler("lk.transcription") { reader, fromIdentity ->
            scope.launch(Dispatchers.IO) {
                if (gen != generation.get()) return@launch
                val text = reader.readAll().joinToString("")
                val attrs = reader.info.attributes
                val side = if (fromIdentity.value == live.localParticipant.identity?.value) "guest" else "staff"
                caption(side, text, attrs["lk.transcription_final"] == "true")
            }
        }
        live.connect(session.url, session.token)
        if (gen != generation.get()) {
            hangup(live)
            return
        }
        speaker()
        live.localParticipant.setMicrophoneEnabled(true)
        push(ev("listening"))
        scope.launch {
            delay(FishPlan.CLIENT_CAP_MS)
            if (gen == generation.get() && running) {
                push(ev("error").put("text", "Test call stopped at the 9-minute cap so it cannot keep spending."))
                stop()
            }
        }
    }

    private fun speaker() {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = true
    }

    private fun caption(side: String, text: String, final: Boolean) {
        val spoken = text.trim()
        if (spoken.isEmpty()) return
        val event = ev(if (side == "staff") "speaking" else "listening").put("side", side).put("text", spoken)
        if (final) event.put("original", "") else event.put("partial", true).put("original", spoken)
        push(event)
    }

    private fun onAgent(gen: Int, data: ByteArray) {
        val event = try { JSONObject(String(data, Charsets.UTF_8)) } catch (_: Throwable) { return }
        when (event.optString("type")) {
            "error" -> fail(gen, "The voice provider failed (${event.optString("code", "error")}). End the call and try again.")
            "session.ended" -> {
                if (gen != generation.get()) return
                push(ev("ended").put("text", "Call ended (${event.optString("reason", "ended")})."))
                stop()
            }
        }
    }

    private fun ensureAgent(apiKey: String, voiceId: String): String {
        val rev = FishPlan.CONFIG_REV + ":" + voiceId
        val baked = BuildConfig.FISH_AGENT_ID.trim()
        val known = baked.ifBlank { meta.getString("agent", "").orEmpty() }
        val id = known.ifBlank {
            findAgent(apiKey) ?: createAgent(apiKey, voiceId)
        }
        if (meta.getString("rev", "") != rev || known.isBlank()) {
            val patched = call(apiKey, "PATCH", "/v1/agent/agents/$id/config", FishPlan.agentConfig(voiceId))
            if (patched.code !in 200..299) throw FishCall(httpError("Could not configure the Fish agent", patched))
            val published = call(apiKey, "POST", "/v1/agent/agents/$id/publish", null)
            if (published.code !in 200..299 && published.code != 409) throw FishCall(httpError("Could not publish the Fish agent", published))
            meta.edit().putString("agent", id).putString("rev", rev).apply()
        }
        return id
    }

    private fun findAgent(apiKey: String): String? {
        val q = URLEncoder.encode(FishPlan.AGENT_NAME, "UTF-8")
        val listed = call(apiKey, "GET", "/v1/agent/agents?search=$q&page_size=20", null)
        if (listed.code == 401) throw FishCall("Fish rejected the key (401). Check the key in Settings.")
        if (listed.code !in 200..299) return null
        val agents = JSONObject(listed.body).optJSONArray("agents") ?: JSONArray()
        for (i in 0 until agents.length()) {
            val agent = agents.getJSONObject(i)
            if (agent.optString("name") == FishPlan.AGENT_NAME && agent.optString("status") != "archived") return agent.getString("agent_id")
        }
        return null
    }

    private fun createAgent(apiKey: String, voiceId: String): String {
        val body = JSONObject()
            .put("name", FishPlan.AGENT_NAME)
            .put("description", "Spain hotel reception interpreter duplex test")
            .put("config", FishPlan.agentConfig(voiceId))
        val created = call(apiKey, "POST", "/v1/agent/agents", body)
        if (created.code !in 200..299) throw FishCall(httpError("Could not create the Fish agent", created))
        return JSONObject(created.body).getString("agent_id")
    }

    private fun createSession(apiKey: String, agentId: String, guest: String, staff: String): Session {
        var created = call(apiKey, "POST", "/v1/agent/sessions", FishPlan.sessionBody(agentId, guest, staff))
        if (created.code == 409) {
            call(apiKey, "POST", "/v1/agent/agents/$agentId/publish", null)
            created = call(apiKey, "POST", "/v1/agent/sessions", FishPlan.sessionBody(agentId, guest, staff))
        }
        if (created.code !in 200..299) throw FishCall(httpError("Could not start the Fish call", created))
        val json = JSONObject(created.body)
        if (json.optString("transport") != "livekit") throw FishCall("Fish returned an unsupported transport.")
        val url = json.optString("livekit_url")
        val token = json.optString("token")
        if (url.isBlank() || token.isBlank()) throw FishCall("Fish did not return a LiveKit session.")
        return Session(url, token)
    }

    private fun call(apiKey: String, method: String, path: String, body: JSONObject?): HttpResult {
        val builder = Request.Builder().url("https://api.fish.audio$path").header("Authorization", "Bearer $apiKey").header("Accept", "application/json")
        val request = if (method == "GET") builder.get() else {
            val payload = body?.toString()?.toRequestBody("application/json".toMediaType()) ?: ByteArray(0).toRequestBody(null)
            builder.method(method, payload)
        }.build()
        http.newCall(request).execute().use { response ->
            return HttpResult(response.code, response.body?.string().orEmpty().replace(apiKey, "[key]"))
        }
    }

    private fun httpError(prefix: String, result: HttpResult): String {
        val detail = when (result.code) {
            401 -> "Fish rejected the key (401)."
            402 -> "Fish quota exceeded (402). This test is capped so it does not keep spending."
            422 -> "Fish rejected the configuration (422)."
            429 -> "Fish rate limited the call (429)."
            else -> "Fish returned ${result.code}."
        }
        return "$prefix. $detail"
    }

    private suspend fun hangup(live: Room?) {
        if (live == null) return
        try {
            live.localParticipant.publishData(
                """{"type":"user.hangup"}""".toByteArray(),
                DataPublishReliability.RELIABLE,
                "client-event"
            )
        } catch (_: Throwable) {}
        try { live.disconnect() } catch (_: Throwable) {}
        try { live.release() } catch (_: Throwable) {}
    }

    private class FishCall(message: String) : Exception(message)
    private data class HttpResult(val code: Int, val body: String)
    private data class Session(val url: String, val token: String)
}
