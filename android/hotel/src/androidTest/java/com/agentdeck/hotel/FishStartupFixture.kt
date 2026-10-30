package com.agentdeck.hotel

import android.app.Instrumentation
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import com.agentdeck.audio.LiveSpeaker
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the shipped startup boundary without any provider access or microphone capture. */
internal object FishStartupFixture {
    fun verify(instrumentation: Instrumentation) {
        check(BuildConfig.DEBUG)
        for (status in listOf(401, 402, 429, 503)) {
            val requests = AtomicInteger()
            val failed = CountDownLatch(1)
            val events = mutableListOf<JSONObject>()
            val owner = VoiceSession("startup-fixture", 1, "en", "es", "translation") { synchronized(events) { events.add(it) } }
            val pipeline = NativeTranslation(instrumentation.targetContext, owner,
                TranslationCredentials.Configuration("synthetic-fish-not-a-key", "synthetic-gemini-not-a-key", "synthetic-voice")) { failed.countDown() }
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                check(request.method == "GET")
                check(request.url.toString() == "https://generativelanguage.googleapis.com/v1beta/openai/models/gemini-3.8-flash")
                check(request.header("Authorization") == "Bearer synthetic-gemini-not-a-key")
                requests.incrementAndGet()
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                    .body("{}".toResponseBody()).build()
            }.build()
            NativeTranslation::class.java.getDeclaredField("http").apply { isAccessible = true }.set(pipeline, client)
            try {
                pipeline.start()
                check(failed.await(5, TimeUnit.SECONDS)) { "Model denial did not terminate startup" }
                check(requests.get() == 1) { "Denied model access retried or opened ASR" }
                synchronized(events) {
                    check(events.none { it.optString("type") in setOf("translation.ready", "translation.welcome", "audio_state") })
                }
                check(NativeTranslation::class.java.getDeclaredField("recorder").apply { isAccessible = true }.get(pipeline) == null)
            } finally { check(pipeline.close()); owner.close(); owner.detached(true) }
        }
        val records = TranslationRecords("fixture", "en", "es")
        records.accept(JSONObject().put("type", "session_started").put("session_id", "asr"))
        val original = "  Can I leave my bag here?\n"
        val record = records.accept(JSONObject().put("type", "committed").put("turn_index", 0).put("text", original).put("language_code", "en"))!!
        check(record.getString("original") == original && record.getString("role") == "guest" && record.getString("target_language") == "es")
        val binary = FishWire.encode(JSONObject().put("type", "audio").put("data", byteArrayOf(0, 1, -2, 3)))
        check((FishWire.decode(binary).get("data") as ByteArray).contentEquals(byteArrayOf(0, 1, -2, 3)))
        verifyRecognitionRestart(instrumentation)
        verifyPlayback()
        verifyManagedPlayback()
    }

    /** Hardware renderer proof only: silent synthetic PCM, no microphone/provider/acoustic claim. */
    private fun verifyPlayback() {
        val failures = AtomicInteger()
        val speaker = LiveSpeaker { failures.incrementAndGet() }
        speaker.start()
        try {
            check(speaker.append(ByteArray(96000))) // Previously rejected solely for message size.
            repeat(20) { check(speaker.append(ByteArray(2880))) } // Ordinary producer burst.
            val total = (96000 + 20 * 2880) / 2L
            check(speaker.submittedSamples() == total)
            check(speaker.finishInput())
            val deadline = SystemClock.elapsedRealtime() + 10000
            while (speaker.playedSamples() < total && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
            check(speaker.playedSamples() >= total && failures.get() == 0) { "Final PCM was not rendered" }
        } finally { speaker.stop() }

        val cancelled = LiveSpeaker { failures.incrementAndGet() }
        cancelled.start()
        val started = CountDownLatch(1)
        val returned = CountDownLatch(1)
        var accepted = true
        val producer = Thread {
            started.countDown()
            try { accepted = cancelled.append(ByteArray(960000)) } finally { returned.countDown() }
        }.apply { start() }
        check(started.await(1, TimeUnit.SECONDS))
        val queued = SystemClock.elapsedRealtime() + 2000
        while (cancelled.submittedSamples() < 24000 && SystemClock.elapsedRealtime() < queued) Thread.sleep(10)
        cancelled.flush()
        check(returned.await(1, TimeUnit.SECONDS)) { "Cancellation did not unblock the WebSocket-style producer" }
        producer.join()
        cancelled.stop()
        check(!accepted && failures.get() == 0)
    }

    private fun pcm(sequence: Int, samples: Int): ByteArray =
        ByteBuffer.allocate(16 + samples * 2).put(byteArrayOf(77, 90, 80, 49))
            .putInt(1).putInt(sequence).putInt(24000).array()

    private fun player(speaker: LiveSpeaker): AudioTrack =
        LiveSpeaker::class.java.getDeclaredField("track").apply { isAccessible = true }.get(speaker) as AudioTrack

    private fun awaitPlayback(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Actual renderer did not advance" }
            Thread.sleep(10)
        }
    }

    private fun rejected(action: () -> Unit) {
        var rejected = false
        try { action() } catch (_: IllegalArgumentException) { rejected = true }
        check(rejected) { "Malformed PCM was accepted" }
    }

    /** Exercises the managed consumer, real playback head and bounded sender credit, not a timer. */
    private fun verifyManagedPlayback() {
        val failures = AtomicInteger()
        val short = LiveSpeaker { failures.incrementAndGet() }
        short.start()
        try {
            val track = player(short)
            if (Build.VERSION.SDK_INT >= 31) check(track.startThresholdInFrames == 1)
            else check(track.bufferSizeInFrames in 1..4800)
            // Force a nontrivial startup threshold on API31 too, exercising the legacy finite
            // tail behavior independent of the device's default minimum buffer.
            track.pause()
            if (Build.VERSION.SDK_INT >= 31) track.setStartThresholdInFrames(minOf(4800, track.bufferSizeInFrames))
            val output = FishVoice.ManagedPlayback(TranslationPcm(1), short)
            output.accept(pcm(0, 1))
            rejected { output.accept(pcm(2, 0)) } // Missing sequence cannot seal the renderer.
            rejected { output.accept(pcm(1, 0).copyOf(17)) }
            rejected { output.accept(pcm(1, 960)) } // A short chunk is the final data chunk.
            output.accept(pcm(1, 0))
            rejected { output.accept(pcm(2, 0)) }
            rejected { output.accept(pcm(2, 1)) }
            check(output.rendered() == null && !output.pcm.complete)
            check(short.submittedSamples() == 1L && short.playedSamples() == 0L)
            check(!short.finishInput() && !short.offer(ByteArray(2), 0, 2))
            track.play()
            var acknowledged: Long? = null
            awaitPlayback {
                output.rendered()?.let { acknowledged = it }
                output.pcm.complete
            }
            check(acknowledged == 1L && short.playedSamples() == 1L)
            check(short.submittedSamples() == 1L && output.rendered() == null)
        } finally { short.stop() }

        val streaming = LiveSpeaker { failures.incrementAndGet() }
        streaming.start()
        try {
            if (Build.VERSION.SDK_INT >= 31) check(player(streaming).startThresholdInFrames == 1)
            else check(player(streaming).bufferSizeInFrames in 1..4800)
            val output = FishVoice.ManagedPlayback(TranslationPcm(1), streaming)
            var sent = 0
            var acknowledged = -1L
            awaitPlayback {
                output.rendered()?.let { acknowledged = it }
                while (sent < 24 && sent - acknowledged - 1 < 6) output.accept(pcm(sent++, 960))
                acknowledged == 23L
            }
            check(!output.pcm.complete && streaming.playedSamples() == 24 * 960L)
            // Re-prime a one-sample finite tail after the previous burst has fully drained.
            output.accept(pcm(24, 1))
            output.accept(pcm(25, 0))
            awaitPlayback {
                output.rendered()?.let { acknowledged = it }
                output.pcm.complete
            }
            check(acknowledged == 25L && streaming.playedSamples() == 24 * 960L + 1)
            check(streaming.submittedSamples() == streaming.playedSamples())
        } finally { streaming.stop() }

        val creditTail = LiveSpeaker { failures.incrementAndGet() }
        creditTail.start()
        try {
            val track = player(creditTail)
            // Exercise the largest supported threshold, with all six credits consumed before EOF.
            val buffer = track.setBufferSizeInFrames(4800)
            if (Build.VERSION.SDK_INT >= 31) track.setStartThresholdInFrames(minOf(4800, buffer))
            else check(buffer in 1..4800)
            val output = FishVoice.ManagedPlayback(TranslationPcm(1), creditTail)
            repeat(5) { output.accept(pcm(it, 960)) }
            output.accept(pcm(5, 1))
            var acknowledged = -1L
            awaitPlayback {
                output.rendered()?.let { acknowledged = it }
                acknowledged >= 0
            }
            check(!output.pcm.complete)
            output.accept(pcm(6, 0))
            awaitPlayback {
                output.rendered()?.let { acknowledged = it }
                output.pcm.complete
            }
            check(acknowledged == 6L && creditTail.playedSamples() == 4801L)
        } finally { creditTail.stop() }

        val priming = LiveSpeaker { failures.incrementAndGet() }
        priming.start()
        try {
            val track = player(priming)
            track.pause()
            // Pausing prevents the real head from advancing while the finite zero tail fills
            // the buffer. Cancellation must interrupt that worker without granting EOF credit.
            val frames = track.bufferSizeInFrames
            check(priming.append(ByteArray(frames * 2)))
            check(priming.finishInput())
            val worker = LiveSpeaker::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(priming) as Thread
            awaitPlayback {
                worker.state == Thread.State.TIMED_WAITING &&
                    worker.stackTrace.any { it.className == LiveSpeaker::class.java.name && it.methodName == "write" }
            }
            priming.flush()
            check(!priming.finishInput() && !priming.append(ByteArray(2)))
            priming.stop()
            check(!worker.isAlive && priming.playedSamples() == 0L)
        } finally { priming.stop() }
        check(failures.get() == 0)
    }

    private fun verifyRecognitionRestart(instrumentation: Instrumentation) {
        val failures = AtomicInteger()
        val events = mutableListOf<JSONObject>()
        val owner = VoiceSession("restart-fixture", 1, "en", "es", "translation") { events.add(it) }
        val pipeline = NativeTranslation(instrumentation.targetContext, owner,
            TranslationCredentials.Configuration("synthetic-fish", "synthetic-gemini", "synthetic-voice")) { failures.incrementAndGet() }
        val recognize = NativeTranslation::class.java.getDeclaredMethod("onRecognition", JSONObject::class.java).apply { isAccessible = true }
        try {
            recognize.invoke(pipeline, JSONObject().put("type", "error").put("error_type", "engine_error").put("code", 503).put("recoverable", true))
            check(failures.get() == 0 && events.any { it.optString("type") == "notice" })
            recognize.invoke(pipeline, JSONObject().put("type", "error").put("error_type", "server_restarting").put("code", 503).put("recoverable", true))
            check(failures.get() == 1)
            recognize.invoke(pipeline, JSONObject().put("type", "session_started"))
            check(failures.get() == 1 && events.none { it.optString("type") == "translation.ready" })
            check(NativeTranslation::class.java.getDeclaredField("recorder").apply { isAccessible = true }.get(pipeline) == null)
        } finally { check(pipeline.close()); owner.close(); owner.detached(true) }
    }
}
