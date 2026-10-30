package com.agentdeck.hotel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.SystemClock
import com.agentdeck.audio.LiveSpeaker
import android.util.Log
import io.livekit.android.AudioOptions
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteTrackPublication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Interpreter transport only. Credentials are staff-saved; managed sessions use the trusted hotel service. */
class FishVoice(ctx: Context, private val push: (JSONObject) -> Unit, private val key: () -> String) {
    private val ctx = ctx.applicationContext
    init { UiText.preload(this.ctx) }
    private fun text(run: Run, message: String, values: Map<String, String> = emptyMap()) =
        UiText.text(ctx, run.owner.guest, message, values)
    private fun text(message: String) = UiText.text(ctx,
        current?.owner?.guest ?: ctx.getSharedPreferences("deck", Context.MODE_PRIVATE)
            .getString("reception_guest_language", "es").orEmpty().takeIf(DeskLanguages::has) ?: "es", message)

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    // Managed creation has a 60s server deadline. Keep its dispatched response recoverable on Stop.
    private val sessionHttp = http.newBuilder().readTimeout(75, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS).build()
    private val meta = ctx.getSharedPreferences("fishmeta", Context.MODE_PRIVATE)
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val admission = admission(this.ctx)
    private inner class Run(val owner: VoiceSession) {
        // Keep diagnostic I/O off transport callbacks and the main thread.
        private val diagnostics = AudioTimingLog(
            dispatch = { task -> cleanup.launch { task() } },
            write = { point ->
                Log.i("AudioTiming", "source=interpreter-sdk session_id=${owner.id} generation=${owner.generation} $point")
                VoiceDiagnostics.record(owner.id, owner.generation, point)
            },
            dropped = { count -> Log.w("AudioTiming", "session_id=${owner.id} generation=${owner.generation} endpoint=diagnostic_points_dropped count=$count") }
        )
        val timing = AudioTurnTiming(SystemClock::elapsedRealtime, diagnostics::offer)
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
        var personal: NativeTranslation? = null
        var playback: ManagedPlayback? = null
        val speakers = ConcurrentHashMap.newKeySet<LiveSpeaker>()
        var audioEffects: ManagedAudioEffects? = null
        val outputFence = TranslationOutput()
        val recordRevisions = mutableMapOf<String, Int>()
        val terminalRecords = mutableSetOf<String>()
        var workerSession: String? = null
        var previousMode: Int? = null
        var previousSpeaker = false
        @Volatile var recording = false
        @Volatile var muted = false
        @Volatile var startupStage = FishStartupFailure.Stage.TRANSPORT
    }
    internal class ManagedPlayback(val pcm: TranslationPcm, val speaker: LiveSpeaker) {
        var watcher: Job? = null
        private var tail = false

        fun accept(data: ByteArray) {
            val packet = TranslationPcm.parse(data)
            // Controlled worker emits full 960-sample chunks, then at most one short tail.
            require(packet.length == 0 || !tail) { "PCM after final short chunk" }
            pcm.accept(packet)
            if (packet.length > 0) {
                check(speaker.offer(data, 16, packet.length))
                tail = packet.length < 1920
            } else check(speaker.finishInput())
        }

        fun rendered(): Long? = pcm.rendered(speaker.playedSamples())
    }
    @Volatile private var current: Run? = null

    fun admissionIssue(): String? = (admission.reason() ?: if (current != null) "The previous conversation cleanup is still finishing." else null)?.let { text(it) }
    fun localAudioState(): String = admission.localAudioState()

    /** Foreground recovery and later start attempts retry an owned uncertain end, never mint a new session. */
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
        val direct = FishCleanupCredential.isDirect(reservation, url, lookup)
        val managed = connection != null && (
            (url.startsWith("${connection.base}/api/device/fish-session/") &&
                url.removePrefix("${connection.base}/api/device/fish-session/").matches(Regex("[A-Za-z0-9_-]+/end"))) ||
            url == "${connection.base}/api/device/fish-session-requests/${URLEncoder.encode(reservation, "UTF-8")}/end")
        val expectedHash = meta.getString("pending_end_credential_hash", null)
        val credential = if (direct) {
            FishCleanupCredential.resolve(SecretStore(ctx, FishCleanupCredential.STORE).load(), reservation, expectedHash)
                ?: key().trim().takeIf { it.isNotBlank() && credentialHash(it) == expectedHash }
                ?: return
        } else if (managed) connection!!.token else return
        if (credential.isBlank() || credentialHash(credential) != expectedHash) return
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

    @Synchronized fun start(guest: String, staff: String, mode: String = "translation", sessionId: String = "", epoch: Int = 0) {
        if (current != null) return
        val run = Run(VoiceSession(sessionId, epoch, guest, staff, mode, push))
        if (!admission.acquire(run.reservation)) {
            // Activity already owns this proposed generation and needs a terminal acknowledgement.
            // Do not detach this unadmitted run: that could release another run's reservation.
            run.owner.close()
            run.owner.detached(admission.localAudioState() == "off", JSONObject().put("type", "reception_event")
                .put("phase", "error").put("text", admission.reason()?.let { text(run, it) }
                    ?: text(run, "Conversation cleanup ownership could not be saved on this device. Ask staff to check device storage.")))
            return
        }
        current = run
        run.owner.emit(JSONObject().put("type", "audio_state").put("state", "off"))
        run.timing.marker("run_started_application_controlled")
        if (!AudioPolicy.allowStart { terminate(run, "error", text(run, it)) }) return
        if (mode != "translation") { terminate(run, "error", text(run, "Assistance requires the configured hotel service; an interpreter cannot provide it.")); return }
        if (!SpeechLanguages.has(guest) || !SpeechLanguages.has(staff)) {
            terminate(run, "error", text(run, "Choose supported languages.")); return
        }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            terminate(run, "error", text(run, "Microphone permission is needed for translation.")); return
        }
        val connection = HotelConnection(ctx).load().takeIf {
            TranslationRouting.useManagedConnection(it != null, TranslationSettings(ctx).directFishSelected, BuildConfig.DISTRIBUTION)
        }
        val managed = connection != null
        // Never use personal credentials for a new hotel session. The key resolver remains
        // available to retryRecoveredEnd for an older direct reservation's exact credential.
        if (BuildConfig.DISTRIBUTION == "hotel" && !managed) {
            terminate(run, "error", text(run, "Translation needs hotel setup. Please ask staff.")); return
        }
        val credentials = if (managed) null else TranslationCredentials(ctx).load()
        if (!managed && credentials == null) {
            terminate(run, "error", text(run, "Fish Audio + Gemini: save both keys and a voice to translate.")); return
        }
        run.owner.phase("connecting")
        if (!managed) {
            try {
                val manager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                run.previousMode = manager.mode
                @Suppress("DEPRECATION")
                run.previousSpeaker = manager.isSpeakerphoneOn
                manager.mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION")
                manager.isSpeakerphoneOn = true
                val pipeline = NativeTranslation(ctx, run.owner, credentials!!) {
                    terminate(run, "error", text(run, "Translation service failed. Please start a new conversation or contact reception."))
                }
                run.personal = pipeline
                run.owner.ready { pipeline.start() }
            } catch (error: Throwable) { startupFailed(run, FishStartupFailure.Stage.TRANSPORT, error, false) }
            return
        }
        run.creation.launch {
            try {
                val session = managedSession(run, connection!!.base, connection.token, guest, staff)
                run.timing.marker("provider_ticket_received")
                run.scope.launch {
                    try { connect(run, session) }
                    catch (_: CancellationException) { }
                    catch (error: Throwable) { startupFailed(run, FishStartupFailure.Stage.TRANSPORT, error, managed) }
                }
            } catch (_: CancellationException) { }
            catch (error: Throwable) { startupFailed(run, run.startupStage, error, managed) }
        }
    }

    /** Guest-controlled microphone gate. Muting never releases capture or touches session ownership. */
    fun setMuted(muted: Boolean) {
        val run = current ?: return
        run.personal?.let { it.setMuted(muted); run.muted = muted; return }
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
                .put("text", text(run, "The microphone state did not change. Try again or stop the conversation."))) }
        }
    }

    fun stop(onStopped: () -> Unit = {}) { val run = current; if (run == null) onStopped() else detach(run, null, onStopped) }
    fun release() { stop() }

    fun resolveDirection(utteranceId: String, revision: Int, role: String) {
        val run = current ?: return
        if (!run.owner.active() || role !in setOf("guest", "receptionist")) return
        run.personal?.let {
            try { it.resolve(utteranceId, revision, role) } catch (_: IllegalArgumentException) { }
            return
        }
        run.scope.launch {
            if (!run.owner.active() || run.recordRevisions[utteranceId] != revision) return@launch
            val control = JSONObject().put("type", "translation.resolve").put("utterance_id", utteranceId)
                .put("revision", revision).put("role", role)
            try { run.room?.localParticipant?.publishData(control.toString().toByteArray(Charsets.UTF_8), topic = "client-event")?.getOrThrow() }
            catch (_: Exception) { run.owner.emit(JSONObject().put("type", "notice").put("status", "unavailable")
                .put("text", text(run, "This service is unavailable. Please ask hotel staff."))) }
        }
    }

    private suspend fun connect(run: Run, session: Session) {
        if (!run.owner.active()) return
        run.timing.marker("transport_connect_started")
        val live = LiveKit.create(ctx.applicationContext, overrides = LiveKitOverrides(audioOptions = AudioOptions(
            disableAudioPrewarming = true,
            javaAudioDeviceModuleCustomizer = { builder ->
                builder.setUseHardwareAcousticEchoCanceler(true)
                builder.setUseHardwareNoiseSuppressor(true)
                builder.setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                // External PCM output does not enter WebRTC software AEC's render graph.
                // Observe/enable platform effects on the actual recording session below.
                builder.setUseLowLatency(true)
            }
        )))
        if (!run.owner.use { run.room = live }) { live.release(); return }
        run.scope.launch {
            live.events.collect { event ->
                if (!run.owner.active()) return@collect
                when (event) {
                    is RoomEvent.DataReceived -> {
                        val roomName = live.name ?: return@collect
                        if (event.participant?.identity?.value != "agent-" + roomName.take(40)) return@collect
                        when (event.topic) {
                            "agent-event" -> onAgent(run, event.data)
                            "translation-audio-v1" -> onPcm(run, event.data)
                        }
                    }
                    is RoomEvent.TrackPublished -> (event.publication as? RemoteTrackPublication)?.setSubscribed(false)
                    is RoomEvent.TrackSubscribed -> (event.track as? RemoteAudioTrack)?.setVolume(0.0)
                    is RoomEvent.Reconnecting -> {
                        run.timing.marker("transport_reconnect_gap_started")
                        run.owner.emit(JSONObject().put("type", "notice").put("text", text(run, "Audio connection interrupted. Captions may have a gap; no previous translation will be replayed.")))
                        run.owner.phase("connecting", text(run, "Audio connection interrupted; reconnecting."))
                    }
                    is RoomEvent.Reconnected -> {
                        run.timing.marker("transport_reconnected")
                        if (run.recording) run.owner.phase("listening")
                    }
                    is RoomEvent.Disconnected -> terminate(run, "error", text(run, "Translation disconnected. Please start a new conversation."))
                    else -> Unit
                }
            }
        }
        live.connect(session.url, session.token, ConnectOptions(autoSubscribe = false))
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
            delay(600000)
            terminate(run, "timeout", text(run, "Conversation reached its duration limit. Please start a new conversation."))
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
                run.audioEffects = try { ManagedAudioEffects(manager) { event -> if (run.owner.active()) run.owner.emit(event) } }
                    catch (_: Exception) {
                        run.owner.emit(JSONObject().put("type", "audio_processing").put("aec", false).put("noise_suppression", false))
                        null
                    }
                check(live.localParticipant.setMicrophoneEnabled(!run.muted))
                run.audioEffects?.refresh()
                run.owner.use {
                    run.recording = true
                    run.timing.marker("microphone_enabled")
                    run.owner.emit(JSONObject().put("type", "audio_state").put("state", if (run.muted) "muted" else "recording"))
                    run.owner.phase("listening")
                }
            } catch (_: CancellationException) { }
            catch (_: Throwable) { terminate(run, "error", text(run, "Microphone capture could not start. Check permission or contact reception.")) }
        }
    }

    private fun clearPlayback(run: Run) {
        val old = run.playback ?: return
        run.playback = null
        old.watcher?.cancel()
        old.speaker.flush()
        cleanup.launch {
            try { old.speaker.stop(); run.speakers.remove(old.speaker) }
            catch (_: Exception) { terminate(run, "error", text(run, "Translation service failed. Please start a new conversation or contact reception.")) }
        }
    }

    private fun playbackFailed(run: Run) {
        clearPlayback(run)
        terminate(run, "error", text(run, "Translation service failed. Please start a new conversation or contact reception."))
    }

    private fun onPcm(run: Run, data: ByteArray) {
        try {
            val generation = TranslationPcm.generation(data)
            if (run.outputFence.stale(generation)) return
            val output = run.playback ?: error("PCM before playing")
            check(output.pcm.generation == generation)
            output.accept(data)
        } catch (_: Exception) { playbackFailed(run) }
    }

    private fun outputControl(run: Run, generation: Long, status: String) {
        if (run.outputFence.stale(generation)) return
        if (status == "complete" && (run.playback?.pcm?.generation != generation ||
            run.playback?.pcm?.complete != true)) { playbackFailed(run); return }
        if (!run.outputFence.accept(generation, status)) return
        if (status == "interrupted" || status == "error") { clearPlayback(run); return }
        if (status != "playing" || run.playback?.pcm?.generation == generation) return
        clearPlayback(run)
        try {
            val speaker = LiveSpeaker {
                run.scope.launch { if (run.playback?.pcm?.generation == generation) playbackFailed(run) }
            }
            speaker.start()
            run.speakers.add(speaker)
            val output = ManagedPlayback(TranslationPcm(generation), speaker)
            run.playback = output
            output.watcher = run.scope.launch {
                try {
                    while (run.owner.active() && run.playback === output) {
                        val sequence = output.rendered()
                        if (sequence != null) {
                            val control = JSONObject().put("type", "translation.played")
                                .put("output_generation", generation).put("sequence", sequence)
                            val room = run.room ?: error("Transport unavailable")
                            kotlinx.coroutines.withTimeout(10000) {
                                room.localParticipant.publishData(control.toString().toByteArray(Charsets.UTF_8), topic = "client-event").getOrThrow()
                            }
                        }
                        if (output.pcm.complete) break
                        delay(10)
                    }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) { if (run.playback === output) playbackFailed(run) }
                catch (_: CancellationException) { }
                catch (_: Exception) { if (run.playback === output) playbackFailed(run) }
            }
        } catch (_: Exception) { playbackFailed(run) }
    }

    private fun onAgent(run: Run, data: ByteArray) {
        if (data.size > 131072) return
        val event = try { JSONObject(String(data, Charsets.UTF_8)) } catch (_: Exception) { return }
        try {
            when (event.optString("type")) {
                "translation.ready", "translation.welcome", "translation.output", "translation.utterance" -> {
                    val record = if (event.optString("type") == "translation.utterance") event.getJSONObject("record") else null
                    val identity = (record ?: event).getString("session_id")
                    if (run.workerSession == null) run.workerSession = identity
                    if (identity != run.workerSession) return
                    if (record != null) {
                        val id = record.getString("utterance_id")
                        if (id in run.terminalRecords) return
                        val revision = record.getInt("revision")
                        if (revision <= (run.recordRevisions[id] ?: 0)) return
                        require(record.opt("original") is String && record.opt("translation") is String)
                        run.recordRevisions[id] = revision
                        if (record.optString("translation_status") == "cancelled" || record.optString("playback_status") == "interrupted")
                            run.terminalRecords.add(id)
                        record.put("session_id", run.owner.id)
                        outputControl(run, record.getLong("output_generation"), record.getString("playback_status"))
                    } else if (event.has("output_generation")) outputControl(run, event.getLong("output_generation"), event.getString("status"))
                    event.put("session_id", run.owner.id)
                    run.owner.emit(event)
                }
                "error" -> if (event.optBoolean("recovered")) {
                    run.owner.emit(JSONObject().put("type", "notice").put("status", "recovering")
                        .put("text", text(run, "The voice service recovered from a temporary interruption.")))
                } else terminate(run, "error", text(run, "Translation service failed. Please start a new conversation or contact reception."))
                "session.ended" -> terminate(run, "stopped", text(run, "Conversation ended."))
            }
        } catch (_: Exception) {
            run.owner.emit(JSONObject().put("type", "notice").put("status", "unavailable")
                .put("text", text(run, "Captions were unavailable for one utterance. Audio may continue.")))
        }
    }

    private fun terminate(run: Run, phase: String, text: String) =
        detach(run, JSONObject().put("type", "reception_event").put("phase", phase).put("text", text))

    private fun startupFailed(run: Run, stage: FishStartupFailure.Stage, error: Throwable, managed: Boolean) {
        if (!run.owner.active()) return
        val notice = FishStartupFailure.describe(stage, error, managed) { message, values ->
            if (BuildConfig.DISTRIBUTION == "hotel" && message != "{message} Reference: {reference}")
                text(run, "Translation is unavailable. Please ask staff.")
            else text(run, message, values)
        }
        // Never pass the exception, request URL, response body or credential to logcat.
        Log.w("TranslationStartup", notice.reference)
        terminate(run, "error", notice.text)
    }

    private fun detach(run: Run, terminal: JSONObject?, onStopped: () -> Unit = {}) {
        if (!run.owner.close(onStopped)) return
        run.playback?.speaker?.flush()
        run.timing.interrupted()
        run.timing.marker("local_release_started")
        run.creation.coroutineContext[Job]?.cancel()
        run.calls.forEach { if (it !== run.sessionCall) it.cancel() }
        run.endJob = cleanup.launch {
            var off = true
            // Only local producers: never join an in-flight provider HTTP creation here.
            run.scope.coroutineContext[Job]?.cancelAndJoin()
            run.personal?.let { if (!it.close()) off = false }
            run.playback?.watcher?.cancel()
            run.playback = null
            run.speakers.forEach { try { it.stop() } catch (_: Exception) { off = false } }
            run.speakers.clear()
            try { run.audioEffects?.close() } catch (_: Exception) { off = false }
            val live = run.room
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

    private fun directLookup(reservation: String): String = FishCleanupCredential.lookup(reservation)

    private fun retainCreationRecovery(run: Run, request: Request) {
        val direct = request.url.host == "api.fish.audio"
        val url = if (direct) directLookup(run.reservation) else
            request.url.toString().removeSuffix("/fish-session") + "/fish-session-requests/${URLEncoder.encode(run.reservation, "UTF-8")}/end"
        val authorization = request.header("Authorization").orEmpty()
        val builder = Request.Builder().url(url).header("Authorization", authorization)
        run.creationRecovery = if (direct) builder.get().build()
            else builder.post("{}".toRequestBody("application/json".toMediaType())).build()
        if (meta.getString("outstanding_session", null) == run.reservation) {
            if (direct) {
                val credential = authorization.removePrefix("Bearer ")
                val record = FishCleanupCredential.encode(run.reservation, credentialHash(credential), credential)
                check(record != null && SecretStore(ctx, FishCleanupCredential.STORE).save(record)) {
                    "Could not retain cleanup credential"
                }
            }
            check(meta.edit().putString("pending_recovery_url", url).putBoolean("pending_recovery_lookup", direct)
                .putString("pending_end_credential_hash", credentialHash(authorization.removePrefix("Bearer "))).commit())
        }
    }

    private fun managedSession(run: Run, base: String, token: String, guest: String, staff: String): Session {
        run.startupStage = FishStartupFailure.Stage.MANAGED_SESSION
        val body = JSONObject().put("guest_language", guest).put("staff_language", staff).put("mode", "translation")
            .put("request_id", run.reservation).put("protocol", "translation-v1")
        val request = Request.Builder().url("$base/api/device/fish-session").header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return parseSession(run, execute(run, request, createsSession = true), "$base/api/device/fish-session", token)
    }
    private fun parseSession(run: Run, json: JSONObject, sessionBase: String, credential: String): Session {
        run.startupStage = FishStartupFailure.Stage.TICKET
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
        if (json.optString("transport") != "livekit") throw FishStartupFailure.response(FishStartupFailure.Stage.TICKET)
        val url = json.getString("livekit_url")
        val token = json.getString("token")
        if (!url.startsWith("wss://") || token.isBlank()) throw FishStartupFailure.response(FishStartupFailure.Stage.TICKET)
        return Session(url, token)
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
                if (!response.isSuccessful) {
                    val managedFailure = if (request.url.host != "api.fish.audio")
                        FishStartupFailure.managedHeader(response.header("X-Manzanilla-Voice-Failure")) else null
                    throw managedFailure ?: FishStartupFailure.http(run.startupStage, response.code)
                }
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
        private fun credentialHash(value: String): String = FishCleanupCredential.hash(value)
        internal fun needsCleanupCredential(ctx: Context): Boolean {
            val meta = ctx.getSharedPreferences("fishmeta", Context.MODE_PRIVATE)
            val reservation = meta.getString("outstanding_session", null) ?: return false
            val url = meta.getString("pending_end_url", null) ?: meta.getString("pending_recovery_url", null)
            return FishCleanupCredential.isDirect(reservation, url,
                !meta.contains("pending_end_url") && meta.getBoolean("pending_recovery_lookup", false)) &&
                FishCleanupCredential.resolve(SecretStore(ctx, FishCleanupCredential.STORE).load(),
                    reservation, meta.getString("pending_end_credential_hash", null)) == null
        }

        internal fun recoverCleanupCredential(ctx: Context, credential: String): Boolean {
            if (!needsCleanupCredential(ctx)) return false
            val meta = ctx.getSharedPreferences("fishmeta", Context.MODE_PRIVATE)
            val reservation = meta.getString("outstanding_session", null) ?: return false
            val record = FishCleanupCredential.encode(reservation,
                meta.getString("pending_end_credential_hash", null), credential) ?: return false
            return SecretStore(ctx, FishCleanupCredential.STORE).save(record)
        }
        @Synchronized private fun admission(ctx: Context): FishSessionAdmission =
            sharedAdmission ?: run {
                val meta = ctx.getSharedPreferences("fishmeta", Context.MODE_PRIVATE)
                FishSessionAdmission(meta.getString("outstanding_session", null)) { id ->
                    val saved = meta.edit().putString("outstanding_session", id)
                        .remove("pending_end_url").remove("pending_end_credential_hash")
                        .remove("pending_recovery_url").remove("pending_recovery_lookup").commit()
                    if (saved && id == null) SecretStore(ctx, FishCleanupCredential.STORE).clear()
                    saved
                }.also { sharedAdmission = it }
            }
    }
}
