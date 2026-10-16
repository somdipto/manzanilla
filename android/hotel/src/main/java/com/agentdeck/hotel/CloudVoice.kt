package com.agentdeck.hotel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.agentdeck.audio.LiveSpeaker
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Concierge only: authenticated Gemini Live backend, hotel knowledge and authorized gateway tools. */
class CloudVoice(private val ctx: Context, private val push: (JSONObject) -> Unit, private val toolEvent: (JSONObject) -> Unit) {
    private inner class Run(val owner: VoiceSession) {
        val playbackLock = Any()
        @Volatile var speaker: LiveSpeaker? = null
        lateinit var client: CloudClient
        var mic: HotelMic? = null
        @Volatile var recording = false
        var setupTimeout: java.util.concurrent.ScheduledFuture<*>? = null
        fun disposeTransport() { if (::client.isInitialized) client.dispose() }
        val clock = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ConciergeClock").apply { isDaemon = true } }
        @Volatile var lastSpeech = SystemClock.elapsedRealtime()
        var item = ""
        var itemStart = 0L
        val timing = AudioTurnTiming(SystemClock::elapsedRealtime) { Log.i("AudioTiming", "source=assistance $it") }
    }
    @Volatile private var current: Run? = null

    @Synchronized fun start(guest: String, staff: String, sessionId: String, epoch: Int) {
        if (current != null) return
        val run = Run(VoiceSession(sessionId, epoch, guest, staff, "reception", push))
        current = run
        run.owner.emit(JSONObject().put("type", "audio_state").put("state", "off"))
        if (!AudioPolicy.allowStart { terminate(run, "error", it) }) return
        val connection = HotelConnection(ctx).load()
        if (connection == null) {
            terminate(run, "error", "Assistance is unavailable. Ask staff to configure the trusted hotel service, or contact reception.")
            return
        }
        if (!DeskLanguages.has(guest) || !DeskLanguages.has(staff)) {
            terminate(run, "error", "Choose a supported language."); return
        }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            terminate(run, "error", "Microphone permission is needed for assistance."); return
        }
        run.owner.phase("connecting")
        run.owner.use {
            run.client = CloudClient(connection.base, connection.token, { event -> onEvent(run, event) }, { pcm ->
                synchronized(run.playbackLock) {
                    run.owner.use {
                        try {
                            val speaker = run.speaker ?: LiveSpeaker { terminate(run, "error", "Speech playback failed. Retry or contact reception.") }
                                .also { it.start(); run.speaker = it }
                            val firstFrame = speaker.submittedSamples()
                            if (!speaker.append(pcm)) terminate(run, "error", "Speech playback could not keep up. Retry or contact reception.")
                            else {
                                run.timing.rawReceived(firstFrame)
                                run.owner.phase("speaking")
                            }
                        } catch (_: Exception) { terminate(run, "error", "Speech playback is unavailable. Contact reception.") }
                    }
                }
            })
            run.setupTimeout = run.clock.schedule({
                terminate(run, "error", "Voice setup timed out. Retry or contact reception.")
            }, 20, TimeUnit.SECONDS)
            run.client.start("reception", guest, staff, sessionId, epoch)
            run.clock.scheduleAtFixedRate({
                synchronized(run.playbackLock) { run.timing.playbackHead(run.speaker?.playedSamples() ?: 0L) }
                run.owner.use {
                    run.owner.emit(JSONObject().put("type", "energy").put("level", run.speaker?.currentEnergy() ?: 0f))
                    if (run.recording && SystemClock.elapsedRealtime() - run.lastSpeech > 120_000)
                        terminate(run, "timeout", "Conversation ended after inactivity.")
                }
            }, 0, 50, TimeUnit.MILLISECONDS)
        }
    }

    fun greetingDone() { current?.let { run -> run.owner.greeted { capture(run) } } }

    private fun capture(run: Run) {
        run.mic = HotelMic(ctx, { bytes, size ->
            var sent = false
            run.owner.use { sent = run.client.sendAudio(bytes, size) }
            sent
        }, { terminate(run, "error", "Microphone or audio connection failed. Retry or contact reception.") }, {
            run.recording = true
            run.owner.emit(JSONObject().put("type", "audio_state").put("state", "recording"))
            run.owner.phase("listening")
            run.lastSpeech = SystemClock.elapsedRealtime()
        }, { level ->
            val at = SystemClock.elapsedRealtime()
            if (level > 0.012f) run.lastSpeech = at
            run.timing.inputLevel(level, 0.012f, at)
            run.owner.emit(JSONObject().put("type", "mic_level").put("level", level))
        }, echoControl = { echo ->
            run.owner.emit(JSONObject().put("type", "notice").put("status", "unverified")
                .put("text", "${echo.description} Echo performance has not been physically verified."))
        })
        run.mic?.start()
    }

    private fun onEvent(run: Run, event: JSONObject) {
        if (!run.owner.active()) return
        if (event.has("session_id") && event.optString("session_id") != run.owner.id) return
        if (event.has("generation") && event.optInt("generation") != run.owner.generation) return
        when (event.optString("type")) {
            "ready" -> {
                run.setupTimeout?.cancel(false)
                run.owner.ready { capture(run) }
            }
            "caption" -> run.owner.emit(event)
            "state" -> {
                val state = event.optString("state")
                if (state == "listening") run.timing.outputComplete()
                if (state in setOf("listening", "thinking", "speaking")) run.owner.phase(state)
            }
            "audio_item" -> synchronized(run.playbackLock) {
                run.owner.use {
                    val item = event.optString("item_id")
                    if (item != run.item) { run.item = item; run.itemStart = run.speaker?.submittedSamples() ?: 0 }
                }
            }
            "interrupt" -> synchronized(run.playbackLock) {
                if (!run.owner.active()) return
                val heardMs = ((run.speaker?.playedSamples() ?: 0) - run.itemStart).coerceAtLeast(0) * 1000 / 24000
                // Gemini has no OpenAI conversation.item.truncate. This reports local heard position only;
                // the relay's Gemini interruption cancels generation, and native playback is flushed here.
                run.client.send(JSONObject().put("type", "played").put("item_id", run.item).put("ms", heardMs))
                run.speaker?.stop()
                run.timing.interrupted()
                run.speaker = null
                run.owner.emit(JSONObject().put("type", "energy").put("level", 0))
            }
            "confirm", "handoff" -> run.owner.use { toolEvent(run.owner.scoped(event)) }
            "error" -> terminate(run, "error", event.optString("message", "Voice service unavailable. Contact reception."))
            "ended" -> terminate(run, "stopped", "Conversation ended.")
        }
    }

    fun confirmation(id: String, accepted: Boolean) { current?.let { run -> run.owner.use {
        run.client.send(JSONObject().put("type", "confirmation").put("id", id).put("accepted", accepted))
    } } }
    fun handoff(id: String, status: String) { current?.let { run -> run.owner.use {
        run.client.send(JSONObject().put("type", "handoff_result").put("id", id).put("status", status))
    } } }
    fun stop(onStopped: () -> Unit = {}) { val run = current; if (run == null) onStopped() else detach(run, null, onStopped) }
    fun release() = stop()
    private fun terminate(run: Run, phase: String, text: String) =
        detach(run, JSONObject().put("type", "reception_event").put("phase", phase).put("text", text))

    private fun detach(run: Run, terminal: JSONObject?, onStopped: () -> Unit = {}) {
        if (!run.owner.close(onStopped)) return
        run.setupTimeout?.cancel(false)
        run.clock.shutdownNow()
        Thread({
            var off = true
            try { run.mic?.stop() } catch (_: Exception) { off = false }
            try { synchronized(run.playbackLock) { run.timing.interrupted(); run.speaker?.stop(); run.speaker = null } } catch (_: Exception) { off = false }
            try { run.disposeTransport() } catch (_: Exception) { off = false }
            synchronized(this) { if (current === run) current = null }
            run.owner.detached(off, terminal)
        }, "ConciergeDetach").apply { isDaemon = true; start() }
    }
}
