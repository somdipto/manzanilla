package com.agentdeck.hotel

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.agentdeck.audio.LiveSpeaker
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/** Personal route B: independent microphone, recognition, translation and owned output. */
internal class NativeTranslation(
    private val context: Context,
    private val owner: VoiceSession,
    private val credentials: TranslationCredentials.Configuration,
    private val failed: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).pingInterval(10, TimeUnit.SECONDS).build()
    // The TTS reader deliberately blocks for render credit; do not mistake a delayed pong
    // behind a large PCM message for network failure. ASR retains its independent ping loop.
    private val synthesisHttp = http.newBuilder().pingInterval(0, TimeUnit.SECONDS).build()
    private val lock = Any()
    private val records = TranslationRecords(owner.id, owner.guest, owner.staff)
    @Volatile private var closed = false
    @Volatile private var muted = false
    @Volatile private var failing = false
    private var asr: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var capture: Job? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var startup: Call? = null
    private var generation = 0
    @Volatile private var output: Output? = null
    private var accepted = false
    private var announced = false
    private var playbackReleased = true
    private val pendingAudio = ArrayDeque<ByteArray>()
    private val speakers = java.util.concurrent.ConcurrentHashMap.newKeySet<LiveSpeaker>()
    private val pendingTurns = ArrayDeque<String>()
    private class Output(val generation: Int, val id: String?, val language: String, val welcome: String?) {
        var socket: WebSocket? = null
        var call: Call? = null
        var job: Job? = null
        var speakingWatch: Job? = null
        var speaking = true
        var speaker: LiveSpeaker? = null
        val opened = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var playing = false
    }

    fun start() {
        scope.launch {
            try {
                checkModelAccess()
                if (closed) return@launch
                val request = Request.Builder().url("wss://api.fish.audio/v1/asr/realtime")
                    .header("Authorization", "Bearer ${credentials.fishKey}").header("model", "transcribe-stream-1").build()
                synchronized(lock) {
                    if (closed) return@synchronized
                    asr = http.newWebSocket(request, object : WebSocketListener() {
                        override fun onOpen(socket: WebSocket, response: Response) { guarded { send(socket, FishWire.asrConfig()) } }
                        override fun onMessage(socket: WebSocket, bytes: ByteString) { guarded { onRecognition(FishWire.decode(bytes)) } }
                        override fun onMessage(socket: WebSocket, text: String) { fail() }
                        override fun onFailure(socket: WebSocket, error: Throwable, response: Response?) { fail() }
                        override fun onClosed(socket: WebSocket, code: Int, reason: String) { fail() }
                    })
                }
                delay(10000)
                synchronized(lock) { if (!accepted && !closed) fail() }
            } catch (_: CancellationException) { }
            catch (_: Exception) { fail() }
        }
        scope.launch { delay(600000); fail() }
    }

    private fun checkModelAccess() {
        val request = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/openai/models/gemini-3.8-flash")
            .header("Authorization", "Bearer ${credentials.translationKey}").build()
        val call = http.newCall(request).also { it.timeout().timeout(8, TimeUnit.SECONDS) }
        synchronized(lock) { check(!closed); startup = call }
        try {
            call.execute().use { response ->
                check(response.isSuccessful)
                val source = response.body?.source() ?: error("Missing model metadata")
                check(!source.request(16385))
                check(FishWire.matchesModel(JSONObject(source.readUtf8()).getString("id")))
            }
        } finally { synchronized(lock) { startup = null } }
    }

    private fun onRecognition(event: JSONObject) = synchronized(lock) {
        if (closed || failing || !owner.active()) return@synchronized
        when (event.optString("type")) {
            "session_started" -> {
                check(!accepted)
                FishWire.validateStarted(event)
                records.accept(event)
                accepted = true
                startMicrophone()
                emit(JSONObject().put("type", "translation.ready").put("guest_language", owner.guest).put("staff_language", owner.staff))
                announced = true
                while (pendingAudio.isNotEmpty()) sendAudio(pendingAudio.removeFirst())
                startOutput(null, owner.guest, SpeechLanguages.greeting(owner.guest))
            }
            "user_turn_taken" -> interrupt()
            "speech_start", "draft", "committed" -> {
                val record = records.accept(event) ?: return@synchronized
                publish(record)
                if (record.getString("recognition_status") == "final" && record.getString("translation_status") == "waiting") enqueue(record)
            }
            "error" -> if (FishWire.recoverable(event)) {
                emit(JSONObject().put("type", "notice").put("status", "recovering")
                    .put("text", UiText.text(context, owner.guest, "A transcript segment was unavailable; audio may continue.")))
            } else fail()
            "session_done" -> fail()
        }
    }

    @SuppressLint("MissingPermission") // FishVoice checks RECORD_AUDIO before admitting this pipeline.
    private fun startMicrophone() {
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0)
        val mic = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 6400))
        recorder = mic
        check(mic.state == AudioRecord.STATE_INITIALIZED)
        aec = try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(mic.audioSessionId) else null } catch (_: Exception) { null }
        ns = try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(mic.audioSessionId) else null } catch (_: Exception) { null }
        try { aec?.let { if (it.hasControl()) it.enabled = true } } catch (_: Exception) { }
        try { ns?.let { if (it.hasControl()) it.enabled = true } } catch (_: Exception) { }
        emit(JSONObject().put("type", "audio_processing")
            .put("aec", try { aec?.enabled == true } catch (_: Exception) { false })
            .put("noise_suppression", try { ns?.enabled == true } catch (_: Exception) { false })
            .put("acoustic_reference_qualified", false))
        mic.startRecording()
        check(mic.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        emit(JSONObject().put("type", "audio_state").put("state", if (muted) "muted" else "recording"))
        owner.phase("listening")
        capture = scope.launch {
            val frame = ByteArray(3200)
            var filled = 0
            try {
                while (!closed && !failing && isActive) {
                    val read = mic.read(frame, filled, frame.size - filled, AudioRecord.READ_BLOCKING)
                    check(read > 0)
                    filled += read
                    if (filled != frame.size) continue
                    filled = 0
                    var power = 0.0
                    for (index in frame.indices step 2) {
                        val sample = ((frame[index].toInt() and 255) or (frame[index + 1].toInt() shl 8)).toShort().toInt()
                        power += sample.toDouble() * sample
                    }
                    emit(JSONObject().put("type", "mic_level").put("level", if (muted) 0.0 else sqrt(power / 1600) / 32768))
                    if (muted) frame.fill(0)
                    synchronized(lock) {
                        if (!closed) {
                            if (announced) sendAudio(frame)
                            else { check(pendingAudio.size < 10); pendingAudio.addLast(frame.copyOf()) }
                        }
                    }
                }
            } catch (_: CancellationException) { }
            catch (_: Exception) { if (!closed) fail() }
        }
    }

    fun setMuted(value: Boolean) = synchronized(lock) {
        if (closed) return@synchronized
        muted = value
        if (recorder != null) emit(JSONObject().put("type", "audio_state").put("state", if (value) "muted" else "recording"))
    }

    fun resolve(id: String, revision: Int, role: String) = synchronized(lock) {
        if (closed) return@synchronized
        val record = records.resolve(id, revision, role)
        publish(record)
        enqueue(record)
    }

    private fun enqueue(record: JSONObject) {
        val id = record.getString("utterance_id")
        if (output != null) {
            if (pendingTurns.size >= 8) publish(records.update(id, "translation_status" to "error"))
            else pendingTurns.addLast(id)
        } else startOutput(id, record.getString("target_language"), null)
    }

    private fun startOutput(id: String?, language: String, welcome: String?) {
        check(output == null && !failing && owner.active())
        val out = Output(++generation, id, language, welcome)
        output = out
        if (id != null) publish(records.update(id, "output_generation" to out.generation, "translation_status" to "streaming", "playback_status" to "queued"))
        else welcome(out, "queued")
        send(asr ?: error("ASR unavailable"), JSONObject().put("type", "agent_speaking").put("speaking", true))
        out.speakingWatch = scope.launch {
            var head = 0L
            var progress = android.os.SystemClock.elapsedRealtime()
            while (current(out)) {
                delay(50)
                synchronized(lock) {
                    if (!current(out)) return@launch
                    val now = android.os.SystemClock.elapsedRealtime()
                    val rendered = out.speaker?.playedSamples() ?: 0L
                    if (rendered > head) { head = rendered; progress = now }
                    val speaking = now - progress < 2000
                    if (out.speaking != speaking) {
                        out.speaking = speaking
                        try { send(asr ?: error("ASR unavailable"), JSONObject().put("type", "agent_speaking").put("speaking", speaking)) }
                        catch (_: Exception) { fail() }
                    }
                }
            }
        }
        out.job = scope.launch {
            try {
                openSynthesis(out)
                withTimeout(10000) { out.opened.await() }
                if (welcome != null) clause(out, TranslationRecords.canonical(welcome))
                else {
                    val record = records.get(id!!)
                    if (record.getString("source_language") == language) {
                        val text = TranslationRecords.canonical(TranslationRecords.speechText(record.getString("original")))
                        received(out, text)
                        clause(out, text)
                    } else translate(out, record)
                }
                synchronized(lock) {
                    if (!current(out)) return@launch
                    if (id != null) publish(records.update(id, "translation_status" to "complete"))
                    send(out.socket!!, JSONObject().put("event", "stop"))
                }
                withTimeout(600000) { out.finished.await() }
                val speaker = out.speaker ?: error("Missing synthesis audio")
                synchronized(lock) {
                    if (!current(out)) return@launch
                    check(speaker.submittedSamples() > 0L)
                    check(speaker.finishInput())
                }
                withTimeout(60000) {
                    while (current(out) && speaker.playedSamples() < speaker.submittedSamples()) delay(20)
                }
                synchronized(lock) { if (current(out)) finish(out, "complete") }
            } catch (_: TimeoutCancellationException) { synchronized(lock) { if (current(out)) finish(out, "error") } }
            catch (_: CancellationException) { }
            catch (_: Exception) { synchronized(lock) { if (current(out)) finish(out, "error") } }
        }
    }

    private fun openSynthesis(out: Output) = synchronized(lock) {
        if (!current(out)) return@synchronized
        val request = Request.Builder().url("wss://api.fish.audio/v1/tts/live")
            .header("Authorization", "Bearer ${credentials.fishKey}").header("model", "s2.1-pro").build()
        out.socket = synthesisHttp.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(socket: WebSocket, response: Response) = outputGuard(out) {
                send(socket, FishWire.ttsStart(credentials.voiceId, out.language))
                out.opened.complete(Unit)
            }
            override fun onMessage(socket: WebSocket, bytes: ByteString) {
                if (!current(out) || !owner.active()) return
                try {
                    val event = FishWire.decode(bytes)
                    when (event.getString("event")) {
                        "audio" -> {
                            val pcm = event.get("audio") as ByteArray
                            val speaker = synchronized(lock) {
                                if (!current(out) || !owner.active()) return
                                check(!out.finished.isCompleted)
                                if (!out.playing) {
                                    out.playing = true
                                    outputEvent(out, "playing")
                                    if (out.id != null) publish(records.update(out.id, "playback_status" to "playing")) else welcome(out, "playing")
                                    owner.phase("speaking")
                                }
                                out.speaker ?: LiveSpeaker {
                                    scope.launch { synchronized(lock) { if (current(out)) finish(out, "error") } }
                                }.also { it.start(); speakers.add(it); out.speaker = it }
                            }
                            // Keep this WebSocket reader backpressured, not ASR or capture.
                            // Do not launch a coroutine per message: that would just move the queue.
                            if (!speaker.append(pcm) { current(out) && owner.active() }) return
                        }
                        "finish" -> outputGuard(out) {
                            check(event.getString("reason") == "stop" && !out.finished.isCompleted)
                            out.finished.complete(Unit)
                        }
                        else -> error("Unexpected synthesis event")
                    }
                } catch (_: Exception) { outputGuard(out) { finish(out, "error") } }
            }
            override fun onMessage(socket: WebSocket, text: String) = outputGuard(out) { error("Expected binary synthesis") }
            override fun onFailure(socket: WebSocket, error: Throwable, response: Response?) = outputGuard(out) { throw IllegalStateException("Synthesis unavailable") }
            override fun onClosed(socket: WebSocket, code: Int, reason: String) = outputGuard(out) { check(out.finished.isCompleted) }
        })
    }

    private fun translate(out: Output, record: JSONObject) {
        val source = record.getString("source_language")
        val prompt = context.assets.open("translation-runtime.txt").bufferedReader().use { it.readText() }
            .replace("{{SOURCE_LANGUAGE_NAME}}", SpeechLanguages.english(source)).replace("{{SOURCE_LANGUAGE_CODE}}", source)
            .replace("{{TARGET_LANGUAGE_NAME}}", SpeechLanguages.english(out.language)).replace("{{TARGET_LANGUAGE_CODE}}", out.language)
        val body = JSONObject().put("model", "gemini-3.8-flash").put("temperature", 0).put("stream", true).put("max_tokens", 2048)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", prompt))
                .put(JSONObject().put("role", "user").put("content", TranslationRecords.speechText(record.getString("original")))))
        val request = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions")
            .header("Authorization", "Bearer ${credentials.translationKey}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val call = http.newCall(request).also { it.timeout().timeout(45, TimeUnit.SECONDS) }
        synchronized(lock) { check(current(out)); out.call = call }
        call.execute().use { response ->
            check(response.isSuccessful)
            val stream = TranslationStream(received = { received(out, it) }, clause = { clause(out, it) })
            val input = response.body?.source() ?: error("Missing stream")
            val data = StringBuilder()
            var bytes = 0
            while (!input.exhausted()) {
                check(current(out))
                val line = input.readUtf8LineStrict(65536)
                bytes += line.length
                check(bytes <= 262144)
                if (line.isEmpty()) {
                    if (data.isNotEmpty()) { stream.accept(data.toString()); data.setLength(0) }
                } else if (line.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).removePrefix(" "))
                }
            }
            if (data.isNotEmpty()) stream.accept(data.toString())
            stream.finish()
        }
    }

    private fun received(out: Output, text: String) = synchronized(lock) {
        check(current(out))
        out.id?.let { id -> publish(records.update(id, "translation" to (records.get(id).getString("translation") + text))) }
    }

    private fun clause(out: Output, text: String) = synchronized(lock) {
        check(current(out))
        send(out.socket!!, JSONObject().put("event", "text").put("text", text))
        if (text.isNotBlank()) send(out.socket!!, JSONObject().put("event", "flush"))
    }
    private fun interrupt() {
        output?.let { finish(it, "interrupted") }
        while (pendingTurns.isNotEmpty()) publish(records.update(pendingTurns.removeFirst(), "translation_status" to "cancelled", "playback_status" to "interrupted"))
    }
    private fun finish(out: Output, status: String) {
        if (!current(out)) return
        output = null
        // Publish the fence before any provider cleanup, and never join the writer under ASR's lock.
        out.speaker?.flush()
        if (out.id != null) {
            publish(records.finishOutput(out.id, status))
        } else welcome(out, status)
        outputEvent(out, status)
        out.call?.cancel()
        out.socket?.cancel()
        out.job?.cancel()
        out.speakingWatch?.cancel()
        out.speaker?.let { speaker ->
            scope.launch {
                try { speaker.stop(); speakers.remove(speaker) }
                catch (_: Exception) { playbackReleased = false; fail() }
            }
        }
        try { asr?.let { send(it, JSONObject().put("type", "agent_speaking").put("speaking", false)) } }
        catch (_: Exception) { if (!closed) fail() }
        if (out.id == null && status == "error") { fail(); return }
        owner.phase("listening")
        if (owner.active() && status != "interrupted" && pendingTurns.isNotEmpty()) {
            val next = pendingTurns.removeFirst()
            startOutput(next, records.get(next).getString("target_language"), null)
        }
    }
    private fun current(out: Output): Boolean = !closed && output === out
    private fun publish(record: JSONObject) = emit(JSONObject().put("type", "translation.utterance").put("record", record))
    private fun welcome(out: Output, status: String) = emit(JSONObject().put("type", "translation.welcome").put("text", out.welcome)
        .put("language", out.language).put("status", status).put("output_generation", out.generation))
    private fun outputEvent(out: Output, status: String) = emit(JSONObject().put("type", "translation.output").put("output_generation", out.generation).put("status", status))
    private fun emit(event: JSONObject) { if (!closed) owner.emit(event.put("session_id", owner.id)) }
    private fun sendAudio(bytes: ByteArray) {
        val socket = asr ?: error("ASR unavailable")
        check(socket.queueSize() < 65536 && socket.send(FishWire.audio(bytes)))
    }
    private fun send(socket: WebSocket, event: JSONObject) { check(socket.queueSize() < 65536 && socket.send(FishWire.encode(event))) }
    private fun guarded(action: () -> Unit) { if (!closed && owner.active()) try { action() } catch (_: Exception) { fail() } }
    private fun outputGuard(out: Output, action: () -> Unit) = synchronized(lock) {
        if (!current(out) || !owner.active()) return@synchronized
        try { action() } catch (_: Exception) { finish(out, "error") }
    }
    private fun fail() = synchronized(lock) {
        // Fence fresh work synchronously, before FishVoice's asynchronous teardown.
        if (!closed && !failing) {
            failing = true
            interrupt()
            failed()
        }
    }

    /** Called on FishVoice's local-release worker, never blocks its remote cleanup. */
    fun close(): Boolean {
        val mic: AudioRecord?
        synchronized(lock) {
            if (closed) return true
            interrupt()
            closed = true
            startup?.cancel()
            asr?.cancel()
            pendingAudio.clear()
            mic = recorder
        }
        var released = playbackReleased
        try { mic?.stop() } catch (_: Exception) { released = false }
        scope.cancel()
        runBlocking { capture?.join() }
        speakers.forEach { speaker -> try { speaker.stop() } catch (_: Exception) { released = false } }
        speakers.clear()
        try { mic?.release() } catch (_: Exception) { released = false }
        try { aec?.release(); ns?.release() } catch (_: Exception) { released = false }
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        return released
    }
}
