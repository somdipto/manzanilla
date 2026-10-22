package com.agentdeck.hotel

import android.app.Instrumentation
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONObject
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Run only on an isolated AVD with fixture credentials. No provider or guest data. */
class VoiceLifecycleFixture : Instrumentation() {
    private var args: Bundle? = null
    override fun onCreate(arguments: Bundle?) { args = arguments; super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        val late = args?.getString("late") == "1"
        val status = args?.getString("status")?.toIntOrNull() ?: 200
        val case = args?.getString("case") ?: "pending-end"
        try {
            when (case) {
                "reception-bridge" -> ReceptionWebViewFixture.verify(this)
                "microphone-permission" -> ReceptionWebViewFixture.verifyMicrophonePermission(this)
                "microphone-permission-denied" -> ReceptionWebViewFixture.verifyMicrophonePermission(this, "deny")
                "microphone-permission-background" -> ReceptionWebViewFixture.verifyMicrophonePermission(this, "background")
                "pending-end" -> pendingEnd(late, status)
                "failed-start" -> failedStart(uncertainAudio = false)
                "failed-start-uncertain" -> failedStart(uncertainAudio = true)
                "predispatch-recovery" -> recoveredReservation(dispatched = false, storageFails = false)
                "predispatch-storage-failure" -> recoveredReservation(dispatched = false, storageFails = true)
                "dispatched-recovery" -> recoveredReservation(dispatched = true, storageFails = false)
                "settled-storage-retry" -> settledStorageRetry()
                "managed-rejection" -> managedRejection(status, args?.getString("notCreated") == "1")
                else -> error("Unknown fixture case: $case")
            }
            result.putString("stream", "PASS case=$case late=$late status=$status\n")
            finish(-1, result)
        } catch (e: Throwable) {
            result.putString("stream", "FAIL: ${e.javaClass.simpleName}: ${e.message}\n")
            finish(0, result)
        }
    }

    private fun failedStart(uncertainAudio: Boolean) {
        val gate = FishSessionAdmission(null) { uncertainAudio }
        if (uncertainAudio) {
            check(gate.acquire("previous:1"))
            gate.requested("previous:1")
            gate.localReleased("previous:1", false)
            gate.remoteEnded("previous:1", false)
        }
        val shared = FishVoice::class.java.getDeclaredField("sharedAdmission").apply { isAccessible = true }
        val previous = shared.get(null)
        shared.set(null, gate)
        try {
            val events = ArrayList<JSONObject>()
            val fish = FishVoice(targetContext, { events.add(it) }) { error("failed admission read provider credentials") }
            runOnMainSync { fish.start("es", "en", sessionId = "failed-start", epoch = 31) }
            check(events.size == 2) { "failed start did not acknowledge exactly once: $events" }
            check(events.all { it.optString("session_id") == "failed-start" && it.optInt("generation") == 31 }) {
                "failed start escaped its proposed generation"
            }
            check(events[0].optString("type") == "audio_state" &&
                events[0].optString("state") == (if (uncertainAudio) "unknown" else "off")) { "incorrect failed-start audio state: $events" }
            check(events[1].optString("type") == "reception_event" && events[1].optString("phase") == "error" &&
                events[1].optString("text").isNotBlank()) { "failed start left Connecting without a terminal error: $events" }
            check(FishVoice::class.java.getDeclaredField("current").apply { isAccessible = true }.get(fish) == null) {
                "failed admission installed an active run"
            }
            var stopped = false
            runOnMainSync { fish.stop { stopped = true } }
            check(stopped && events.size == 2) { "failed-start terminal handling did not release the caller" }
            if (uncertainAudio) {
                check(gate.localAudioState() == "unknown" && gate.reason() != null && !gate.acquire("competing"))
            } else check(gate.reason() == null)
        } finally { shared.set(null, previous) }
    }

    private fun recoveredReservation(dispatched: Boolean, storageFails: Boolean) {
        val meta = targetContext.getSharedPreferences("fishmeta", android.content.Context.MODE_PRIVATE)
        val keys = listOf("outstanding_session", "pending_end_url", "pending_recovery_url",
            "pending_recovery_lookup", "pending_end_credential_hash")
        check(keys.none { meta.contains(it) }) { "run recovery fixtures only on a clean isolated AVD" }
        val shared = FishVoice::class.java.getDeclaredField("sharedAdmission").apply { isAccessible = true }
        val previous = shared.get(null)
        val reservation = "fixture-restored:41"
        try {
            val editor = meta.edit().putString("outstanding_session", reservation)
            if (dispatched) editor.putString("pending_recovery_url",
                "https://api.fish.audio/v1/agent/sessions?end_user_id=fixture-restored%3A41&status=pending,active,unknown,completed,failed&page_size=2")
                .putBoolean("pending_recovery_lookup", true).putString("pending_end_credential_hash", "fixture-only")
            check(editor.commit()) { "could not persist fixture's restored reservation" }
            shared.set(null, if (storageFails) FishSessionAdmission(reservation) { false } else null)
            val events = ArrayList<JSONObject>()
            // Blank fixture credentials prevent a dispatched recovery from ever opening HTTP.
            val fish = FishVoice(targetContext, { events.add(it) }) { "" }
            check(fish.admissionIssue() != null && fish.localAudioState() == "off")
            fish.retryCleanup()
            val blocked = dispatched || storageFails
            check((fish.admissionIssue() != null) == blocked) { "incorrect restored admission: ${fish.admissionIssue()}" }
            check(meta.getString("outstanding_session", null) == (if (blocked) reservation else null)) {
                "recovery did not preserve its dispatch/storage boundary"
            }
            val gate = shared.get(null) as FishSessionAdmission
            check(gate.acquire("new:42") == !blocked) { "restored reservation admitted the wrong next owner" }
            if (!blocked) {
                gate.remoteEnded(reservation, true)
                check(!gate.releaseRestoredBeforeDispatch(reservation))
                check(meta.getString("outstanding_session", null) == "new:42") { "old recovery cleared the new reservation" }
            }
            check(events.isEmpty()) { "recovery opened a voice run or emitted unowned events: $events" }
        } finally {
            shared.set(null, previous)
            val editor = meta.edit()
            keys.forEach { editor.remove(it) }
            check(editor.commit()) { "could not clear fixture metadata" }
        }
    }

    private fun settledStorageRetry() {
        var writable = true
        var disk: String? = null
        val gate = FishSessionAdmission(null) { id -> if (writable) { disk = id; true } else false }
        check(gate.acquire("settled:1"))
        gate.requested("settled:1")
        gate.localReleased("settled:1", true)
        writable = false
        gate.remoteEnded("settled:1", true)
        val shared = FishVoice::class.java.getDeclaredField("sharedAdmission").apply { isAccessible = true }
        val previous = shared.get(null)
        shared.set(null, gate)
        try {
            val fish = FishVoice(targetContext, { error("settled cleanup emitted unowned events") }) {
                error("settled cleanup read provider credentials")
            }
            fish.retryCleanup()
            check(disk == "settled:1" && fish.admissionIssue() != null)
            writable = true
            fish.retryCleanup()
            check(disk == null && fish.admissionIssue() == null)
            check(gate.acquire("new:2"))
            gate.remoteEnded("settled:1", true)
            check(disk == "new:2" && !gate.acquire("competing"))
        } finally { shared.set(null, previous) }
    }

    private fun managedRejection(status: Int, notCreated: Boolean) {
        check(status !in 200..299) { "managed-rejection requires a failed HTTP status" }
        val server = ServerSocket(0)
        val replies = AtomicInteger()
        val worker = Thread {
            try {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* consume creation headers */ }
                    val body = """{"error":"fixture rejection"}""".toByteArray()
                    val proof = if (notCreated) "X-Manzanilla-Session-Creation: not-created\r\n" else ""
                    socket.getOutputStream().write(
                        "HTTP/1.1 $status Fixture\r\n${proof}Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)
                    replies.incrementAndGet()
                }
            } catch (_: Exception) { /* closed by fixture teardown */ }
        }.apply { isDaemon = true; start() }
        val shared = FishVoice::class.java.getDeclaredField("sharedAdmission").apply { isAccessible = true }
        val previous = shared.get(null)
        var disk: String? = null
        val gate = FishSessionAdmission(null) { disk = it; true }
        shared.set(null, gate)
        try {
            val events = ArrayList<JSONObject>()
            val fish = FishVoice(targetContext, { events.add(it) }) { error("managed fixture read direct credentials") }
            val owner = VoiceSession("fixture-rejection-${server.localPort}", 51, "es", "en", "translation", events::add)
            val runClass = FishVoice::class.java.declaredClasses.single { it.simpleName == "Run" }
            val run = runClass.declaredConstructors.single().apply { isAccessible = true }.newInstance(fish, owner, "")
            val reservation = runClass.getDeclaredField("reservation").apply { isAccessible = true }.get(run) as String
            check(gate.acquire(reservation))
            val create = FishVoice::class.java.getDeclaredMethod("managedSession", runClass,
                String::class.java, String::class.java, String::class.java, String::class.java).apply { isAccessible = true }
            var rejected = false
            try { create.invoke(fish, run, "http://127.0.0.1:${server.localPort}", "fixture-only", "es", "en") }
            catch (e: java.lang.reflect.InvocationTargetException) {
                check(e.cause is IllegalStateException && e.cause?.message == "Service request rejected") {
                    "creation did not receive the fixture rejection: ${e.cause}"
                }
                rejected = true
            }
            worker.join(3000)
            check(rejected && replies.get() == 1) { "creation did not observe exactly one failed HTTP response" }
            check(disk == reservation && !gate.acquire("too-early")) { "received rejection bypassed local release" }
            gate.localReleased(reservation, true)
            val safe = notCreated || status == 400 || status == 422 || status == 429
            check((disk == null) == safe && gate.acquire("next") == safe) {
                "received rejection crossed the authoritative-header boundary: status=$status proof=$notCreated disk=$disk"
            }
            check(events.isEmpty()) { "rejected creation opened audio or emitted captions" }
        } finally {
            shared.set(null, previous)
            server.close()
            worker.interrupt()
            worker.join(3000)
        }
    }

    private fun pendingEnd(lateCreation: Boolean, status: Int) {
        val server = ServerSocket(0)
        val remoteStarted = CountDownLatch(1)
        val remoteReply = CountDownLatch(1)
        val responses = AtomicInteger()
        val creationStarted = CountDownLatch(1)
        val creationReply = CountDownLatch(1)
        val worker = Thread {
            try {
                if (lateCreation) server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* creation headers */ }
                    creationStarted.countDown()
                    creationReply.await(5, TimeUnit.SECONDS)
                    val body = """{"session_id":"fixture","transport":"livekit","livekit_url":"wss://fixture.invalid","token":"fixture-only"}""".toByteArray()
                    socket.getOutputStream().write("HTTP/1.1 200 Fixture\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)
                }
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* consume HTTP headers */ }
                    remoteStarted.countDown()
                    remoteReply.await(15, TimeUnit.SECONDS)
                    if (status == 0) Thread.sleep(12_000) else {
                        val body = """{"status":"ended"}""".toByteArray()
                        socket.getOutputStream().write("HTTP/1.1 $status Fixture\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)
                        responses.incrementAndGet()
                    }
                }
            } catch (_: Exception) { /* closed by fixture teardown */ }
        }.apply { isDaemon = true; start() }
        val released = CountDownLatch(2)
        val events = ArrayList<JSONObject>()
        val fish = FishVoice(targetContext, { synchronized(events) { events.add(it) } }) { "fixture-disabled-not-a-real-key" }
        val owner = VoiceSession("fixture-${server.localPort}", 11, "es", "en", "translation") {
            synchronized(events) { events.add(it) }
        }
        val runClass = FishVoice::class.java.declaredClasses.single { it.simpleName == "Run" }
        val run = runClass.declaredConstructors.single().apply { isAccessible = true }.newInstance(fish, owner, "")
        val current = FishVoice::class.java.getDeclaredField("current").apply { isAccessible = true }
        current.set(fish, run)
        val admission = FishVoice::class.java.getDeclaredField("admission").apply { isAccessible = true }.get(fish)
        val reservation = runClass.getDeclaredField("reservation").apply { isAccessible = true }.get(run) as String
        check(admission.javaClass.getDeclaredMethod("acquire", String::class.java).apply { isAccessible = true }
            .invoke(admission, reservation) as Boolean) { "fixture could not record the reservation" }
        admission.javaClass.getDeclaredMethod("requested", String::class.java).apply { isAccessible = true }.invoke(admission, reservation)
        val end = Request.Builder().url("http://127.0.0.1:${server.localPort}/sessions/fixture/end")
            .post(okhttp3.RequestBody.create(null, ByteArray(0))).build()
        val endField = runClass.getDeclaredField("endRequest").apply { isAccessible = true }
        // This field is absent in the pre-repair artifact, intentionally supported for the red replay.
        runClass.declaredFields.firstOrNull { it.name == "sessionRequested" }?.apply { isAccessible = true }?.setBoolean(run, true)
        if (lateCreation) {
            val scopeField = runClass.declaredFields.firstOrNull { it.name == "creation" } ?: runClass.getDeclaredField("scope")
            val scope = scopeField.apply { isAccessible = true }.get(run) as CoroutineScope
            val create = FishVoice::class.java.getDeclaredMethod("managedSession", runClass,
                String::class.java, String::class.java, String::class.java, String::class.java).apply { isAccessible = true }
            scope.launch(Dispatchers.IO) {
                create.invoke(fish, run, "http://127.0.0.1:${server.localPort}", "fixture-only", "es", "en")
            }
            check(creationStarted.await(3, TimeUnit.SECONDS)) { "real creation HTTP request did not enter" }
        } else endField.set(run, end)
        try {
            val start = android.os.SystemClock.elapsedRealtime()
            runOnMainSync { fish.stop { released.countDown() }; fish.stop { released.countDown() } }
            check(released.await(1200, TimeUnit.MILLISECONDS)) { "local stop still waits for pending provider end" }
            val stopMs = android.os.SystemClock.elapsedRealtime() - start
            creationReply.countDown()
            check(remoteStarted.await(3, TimeUnit.SECONDS)) { "owned remote termination was lost" }
            check(responses.get() == 0) { "fixture replied before local-stop proof" }
            val audio = synchronized(events) { events.filter { it.optString("type") == "audio_state" }.map { it.optString("state") } }
            check(audio == listOf("off")) { "incorrect local release events: $audio" }
            check(!owner.active()) { "old producers remain admitted" }
            val count = synchronized(events) { events.size }
            owner.ready { error("late callback opened capture") }
            owner.emit(JSONObject().put("type", "caption").put("text", "must remain fenced"))
            check(synchronized(events) { events.size } == count) { "stale callback escaped closing generation" }
            runOnMainSync { fish.start("fr", "de", sessionId = "must-not-start", epoch = 12) }
            // While the end is still pending the reservation must block a competing paid session.
            run {
                val gate = FishVoice::class.java.getDeclaredField("admission").apply { isAccessible = true }.get(fish)
                check(gate.javaClass.getDeclaredMethod("reason").apply { isAccessible = true }.invoke(gate) != null) { "pending end released the reservation" }
            }
            remoteReply.countDown()
            val endJob = runClass.getDeclaredField("endJob").apply { isAccessible = true }.get(run) as Job
            runBlocking { withTimeout(12_000) { endJob.join() } }
            if (status == 200) check(current.get(fish) == null) { "acknowledged cleanup did not release old run" }
            else check(runClass.getDeclaredField("endUncertain").apply { isAccessible = true }.getBoolean(run)) { "failed end not marked uncertain" }
            run {
                val gate = FishVoice::class.java.getDeclaredField("admission").apply { isAccessible = true }.get(fish)
                val issue = gate.javaClass.getDeclaredMethod("reason").apply { isAccessible = true }.invoke(gate) as String?
                if (status == 200) check(issue == null) { "acknowledged end still blocks: $issue" }
                else check(issue != null) { "uncertain end released the reservation" }
            }
            check(responses.get() == if (status == 0) 0 else 1) { "end request not completed exactly once" }
            sendStatus(0, Bundle().apply { putString("stream", "local_stop_ms=$stopMs late_creation=$lateCreation remote_status=$status remote_pending_at_stop=true\n") })
        } finally {
            remoteReply.countDown()
            creationReply.countDown()
            server.close()
            worker.interrupt()
            worker.join(3000)
        }
    }
}
