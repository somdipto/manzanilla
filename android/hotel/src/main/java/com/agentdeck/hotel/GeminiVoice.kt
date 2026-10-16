package com.agentdeck.hotel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.agentdeck.audio.LiveSpeaker
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** BYOK interpreter, never a concierge. The Live Translate model has no instructions or tools.
 * https://ai.google.dev/gemini-api/docs/live-api/live-translate
 * Input PCM16/16kHz, output PCM16/24kHz; capture requires BOTH setupComplete replies and greeting.
 */
class GeminiVoice(private val ctx: Context, private val push: (JSONObject) -> Unit, private val store: SecretStore) {
    companion object { const val MODEL = "gemini-3.5-live-translate-preview" }
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private inner class Run(val owner: VoiceSession) {
        val sessions = listOf(Sess(this, "guest", owner.staff, true), Sess(this, "staff", owner.guest, false))
        val playbackLock = Any()
        val playbackOwner = TurnPlaybackOwner()
        var speaker: LiveSpeaker? = null
        var mic: HotelMic? = null
        @Volatile var recording = false
        @Volatile var lastSpeech = SystemClock.elapsedRealtime()
        val inputTiming = AudioTurnTiming(SystemClock::elapsedRealtime) { Log.i("AudioTiming", "source=interpreter-capture $it") }
        val clock = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "InterpreterClock").apply { isDaemon = true } }
        var setupTimeout: ScheduledFuture<*>? = null
    }
    private inner class Sess(val run: Run, val side: String, val target: String, publishInput: Boolean) {
        var ws: WebSocket? = null
        val detached = CountDownLatch(1)
        @Volatile var ready = false
        val captions = TranslationCaptions("gemini:$side", run.owner.guest, run.owner.staff, target, publishInput)
        val timing = AudioTurnTiming(SystemClock::elapsedRealtime) { Log.i("AudioTiming", "source=interpreter:$side $it") }
    }
    @Volatile private var current: Run? = null

    @Synchronized fun start(guest: String, staff: String, mode: String = "translation", sessionId: String = "", epoch: Int = 0) {
        if (current != null) return
        val run = Run(VoiceSession(sessionId, epoch, guest, staff, mode, push))
        current = run
        run.owner.emit(JSONObject().put("type", "audio_state").put("state", "off"))
        if (!AudioPolicy.allowStart { terminate(run, "error", it) }) return
        if (mode != "translation") { terminate(run, "error", "Assistance requires the trusted hotel service; an interpreter cannot provide it."); return }
        if (!DeskLanguages.has(guest) || !DeskLanguages.has(staff) || guest == staff) {
            terminate(run, "error", "Choose two different languages for translation."); return
        }
        val key = store.load()
        if (key.isNullOrBlank()) {
            terminate(run, "error", if (store.errorMessage != null) "The saved voice credential is unavailable. Ask staff to recover it in Settings."
                else "Ask staff to configure the translation credential in Settings.")
            return
        }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            terminate(run, "error", "Microphone permission is needed for translation."); return
        }
        run.owner.phase("connecting")
        run.owner.use {
            run.setupTimeout = run.clock.schedule({ terminate(run, "error", "Voice setup timed out. Check the connection or contact reception.") }, 20, TimeUnit.SECONDS)
            run.sessions.forEach { open(it, key) }
            run.clock.scheduleAtFixedRate({
                synchronized(run.playbackLock) {
                    val played = run.speaker?.playedSamples() ?: 0L
                    run.sessions.forEach { it.timing.playbackHead(played) }
                }
                run.owner.use {
                    run.owner.emit(JSONObject().put("type", "energy").put("level", run.speaker?.currentEnergy() ?: 0f))
                    if (run.recording && SystemClock.elapsedRealtime() - run.lastSpeech > 120_000)
                        terminate(run, "timeout", "Conversation ended after inactivity.")
                }
            }, 0, 50, TimeUnit.MILLISECONDS)
        }
    }

    fun greetingDone() { current?.let { run -> run.owner.greeted { capture(run) } } }

    private fun open(session: Sess, key: String) {
        val run = session.run
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" + URLEncoder.encode(key, "UTF-8")
        session.ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                run.owner.use {
                    val config = JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
                        .put("inputAudioTranscription", JSONObject()).put("outputAudioTranscription", JSONObject())
                        .put("translationConfig", JSONObject().put("targetLanguageCode", session.target).put("echoTargetLanguage", false))
                    webSocket.send(JSONObject().put("setup", JSONObject().put("model", "models/$MODEL").put("generationConfig", config)).toString())
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(session, text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(session, bytes.utf8())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                try { terminate(run, "error", "Voice connection failed. Check the internet connection or ask staff to check service access.") }
                finally { session.detached.countDown() }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                try { terminate(run, "error", "Voice connection ended. Please start a new conversation.") }
                finally { session.detached.countDown() }
            }
        })
    }

    private fun handle(session: Sess, raw: String) {
        val run = session.run
        if (!run.owner.active()) return
        try {
            val event = JSONObject(raw)
            if (event.has("error")) { terminate(run, "error", "The voice service rejected the request. Ask staff to check service access."); return }
            if (event.has("setupComplete")) {
                session.ready = true
                if (run.sessions.all { it.ready }) {
                    run.setupTimeout?.cancel(false)
                    run.owner.ready { capture(run) }
                }
                return
            }
            val content = event.optJSONObject("serverContent") ?: return
            if (content.optBoolean("interrupted")) {
                synchronized(run.playbackLock) {
                    if (run.playbackOwner.interrupt(session.side)) {
                        run.speaker?.stop()
                        run.speaker = null
                        run.sessions.forEach { it.timing.interrupted() }
                        run.owner.emit(JSONObject().put("type", "energy").put("level", 0))
                    } else session.timing.interrupted()
                }
            }
            session.captions.accept(content).forEach { run.owner.emit(it) }
            content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (index in 0 until parts.length()) {
                    val data = parts.getJSONObject(index).optJSONObject("inlineData") ?: continue
                    if (!data.optString("mimeType").startsWith("audio/pcm")) continue
                    val encoded = data.optString("data")
                    if (encoded.isEmpty()) continue
                    synchronized(run.playbackLock) {
                        run.owner.use {
                            val drained = run.speaker?.let { it.playedSamples() >= it.submittedSamples() } ?: true
                            when (run.playbackOwner.append(session.side, drained)) {
                                TurnPlaybackOwner.Decision.CLOSED -> return@use
                                TurnPlaybackOwner.Decision.INTERRUPTED -> {
                                    terminate(run, "error", "Translation audio arrived after interruption without a new turn boundary. Ask staff to restart rather than risk missing words.")
                                    return@use
                                }
                                TurnPlaybackOwner.Decision.CONFLICT -> {
                                    terminate(run, "error", "Translation turns overlapped before playback finished. Playback stopped rather than mixing or losing words. Ask staff to take turns and restart.")
                                    return@use
                                }
                                TurnPlaybackOwner.Decision.ACCEPT -> Unit
                            }
                            val pcm = Base64.decode(encoded, Base64.DEFAULT)
                            val speaker = run.speaker ?: LiveSpeaker { terminate(run, "error", "Speech playback failed. Please retry.") }
                                .also { it.start(); run.speaker = it }
                            val firstFrame = speaker.submittedSamples()
                            if (!speaker.append(pcm)) terminate(run, "error", "Speech playback could not keep up. Please retry.")
                            else {
                                session.timing.rawReceived(firstFrame)
                                run.owner.phase("speaking")
                            }
                        }
                    }
                }
            }
            if (content.optBoolean("turnComplete")) {
                synchronized(run.playbackLock) {
                    run.playbackOwner.finish(session.side)
                    session.timing.outputComplete()
                }
                if (run.recording) run.owner.phase("listening")
            }
        } catch (_: Exception) { terminate(run, "error", "Voice data could not be processed. Please retry or contact reception.") }
    }

    private fun capture(run: Run) {
        run.mic = HotelMic(ctx, { bytes, size ->
            var sent = false
            run.owner.use {
                val message = JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
                    .put("data", Base64.encodeToString(bytes, 0, size, Base64.NO_WRAP)).put("mimeType", "audio/pcm;rate=16000"))).toString()
                sent = run.sessions.all { it.ready && (it.ws?.queueSize() ?: Long.MAX_VALUE) < 64000 && it.ws?.send(message) == true }
            }
            sent
        }, { terminate(run, "error", "Microphone or audio connection failed. Please retry or contact reception.") }, {
            run.owner.use {
                run.recording = true
                run.lastSpeech = SystemClock.elapsedRealtime()
                run.owner.emit(JSONObject().put("type", "audio_state").put("state", "recording"))
                run.owner.phase("listening")
            }
        }, { level ->
            val at = SystemClock.elapsedRealtime()
            if (level > 0.012f) run.lastSpeech = at
            run.inputTiming.inputLevel(level, 0.012f, at)
            run.owner.emit(JSONObject().put("type", "mic_level").put("level", level))
        }, sampleRate = 16000, echoControl = { echo ->
            run.owner.emit(JSONObject().put("type", "notice").put("status", "unverified")
                .put("text", "${echo.description} Echo performance has not been physically verified."))
        })
        run.mic?.start()
    }

    fun stop(userEnd: Boolean = false, onStopped: () -> Unit = {}) {
        val run = current
        if (run == null) onStopped() else detach(run, null, onStopped)
    }
    private fun terminate(run: Run, phase: String, text: String) =
        detach(run, JSONObject().put("type", "reception_event").put("phase", phase).put("text", text))

    private fun detach(run: Run, terminal: JSONObject?, onStopped: () -> Unit = {}) {
        if (!run.owner.close(onStopped)) return
        run.clock.shutdownNow()
        run.sessions.forEach { it.ws?.cancel() }
        Thread({
            var off = true
            try { run.mic?.stop() } catch (_: Exception) { off = false }
            try { synchronized(run.playbackLock) {
                run.playbackOwner.close()
                run.sessions.forEach { it.timing.interrupted() }
                run.speaker?.stop()
                run.speaker = null
            } } catch (_: Exception) { off = false }
            run.sessions.forEach { session ->
                try { if (session.ws != null) session.detached.await() } catch (_: InterruptedException) { off = false }
            }
            synchronized(this) { if (current === run) current = null }
            run.owner.detached(off, terminal)
        }, "InterpreterDetach").apply { isDaemon = true; start() }
    }
}
