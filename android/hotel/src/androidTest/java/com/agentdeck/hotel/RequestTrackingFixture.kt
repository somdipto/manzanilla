package com.agentdeck.hotel

import android.app.Instrumentation
import android.content.Intent
import android.webkit.WebView
import com.agentdeck.ui.ReceptionView
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Shipped WebView, bridge authorization and gateway with a synthetic HTTP interceptor.
 * No backend, TLS, provider or physical-fulfilment acceptance is implied by this fixture.
 */
internal object RequestTrackingFixture {
    fun verify(instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        check(!SecretStore(context, "fish").has() && !SecretStore(context, "translation_credentials").has() && HotelConnection(context).load() == null) {
            "Use an isolated, unconfigured key-free emulator"
        }
        val activity = instrumentation.startActivitySync(Intent(context, HotelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HotelActivity
        val owner = HotelActivity::class.java.getDeclaredField("owner").apply { isAccessible = true }.get(activity) as SessionOwner
        val handle = HotelActivity::class.java.getDeclaredMethod("handle", JSONObject::class.java).apply { isAccessible = true }
        val receipt = AtomicInteger()
        val reads = AtomicInteger()
        val desired = AtomicReference("acknowledged")
        val blocked = AtomicReference<CountDownLatch?>(null)
        val readPaths = LinkedBlockingQueue<String>()
        val statuses = LinkedBlockingQueue<JSONObject>()
        val actions = LinkedBlockingQueue<JSONObject>()
        lateinit var page: ReceptionView
        lateinit var browser: WebView
        lateinit var gateway: HotelGateway
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host == "fixture.invalid")
            check(request.header("Authorization") == "Bearer fixture-device")
            val path = request.url.encodedPath
            val value = when {
                path.endsWith("/start") -> JSONObject().put("status", "ready")
                path.endsWith("/action") -> JSONObject().put("status", "saved")
                    .put("text", "Saved in the staff inbox. No acknowledgement or delivery is confirmed.")
                    .put("result", JSONObject().put("item_id", "fixture-receipt-${receipt.incrementAndGet()}").put("pms_delivered", false))
                path.contains("/items/") -> {
                    reads.incrementAndGet()
                    readPaths.offer(path)
                    val status = desired.get()
                    val barrier = blocked.get()
                    check(barrier == null || barrier.await(20, TimeUnit.SECONDS))
                    JSONObject().put("item_id", path.substringAfterLast('/')).put("status", status)
                        .put("version", if (status == "resolved") 2 else 1).put("created", 100.0)
                        .put("acknowledged_at", 110.0).put("resolved_at", if (status == "resolved") 120.0 else JSONObject.NULL)
                        .put("resolution_note", "PRIVATE NOTE").put("responsible_name", "PRIVATE NAME").put("acknowledged_by", "PRIVATE ACTOR")
                }
                path.endsWith("/end") -> JSONObject().put("status", "ended")
                else -> error("Unexpected fixture operation")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(value.toString().toResponseBody()).build()
        }.build()
        instrumentation.runOnMainSync {
            // Fixture readiness is synthetic; do not replace it with the unconfigured hotel's periodic probe.
            val handler = HotelActivity::class.java.getDeclaredField("ui").apply { isAccessible = true }.get(activity) as android.os.Handler
            val hotelPoll = HotelActivity::class.java.getDeclaredField("hotelPoll").apply { isAccessible = true }.get(activity) as Runnable
            handler.removeCallbacks(hotelPoll)
            val receptionField = HotelActivity::class.java.getDeclaredField("reception").apply { isAccessible = true }
            (receptionField.get(activity) as ReceptionView).dispose()
            gateway = HotelGateway("https://fixture.invalid", "fixture-device", { lease, event ->
                activity.runOnUiThread {
                    if (owner.accepts(lease.request.id, lease.generation)) {
                        if (event.optString("type") == "request_status") statuses.offer(JSONObject(event.toString()))
                        page.push(event.put("session_id", lease.request.id).put("generation", lease.generation))
                    }
                }
            }, { _, _ -> error("This fixture must not dial") }, { lease, message, values -> UiText.text(context, lease.request.guest, message, values) }, client)
            HotelActivity::class.java.getDeclaredField("gateway").apply { isAccessible = true }.set(activity, gateway)
            page = ReceptionView(activity, { message ->
                actions.offer(JSONObject(message.toString()))
                when (message.optString("action")) {
                    "start" -> {
                        val lease = owner.acquire(SessionOwner.Request(message.getString("session_id"), "reception", "en", "es")) ?: error("Lease unavailable")
                        gateway.begin(lease)
                        page.push(JSONObject().put("type", "audio_state").put("state", "off").put("session_id", lease.request.id).put("generation", lease.generation))
                        page.push(JSONObject().put("type", "reception_event").put("phase", "ready").put("session_id", lease.request.id).put("generation", lease.generation))
                    }
                    "message" -> owner.current?.let { gateway.action(it, "message", JSONObject().put("original_request", "Synthetic request")) }
                    "watch_request", "unwatch_request", "stop", "activity" -> handle.invoke(activity, message)
                }
            }) { error("Packaged Reception failed") }
            browser = page.getChildAt(0) as WebView
            receptionField.set(activity, page)
            activity.setContentView(page)
            page.push(JSONObject().put("type", "reception_info").put("distribution", BuildConfig.DISTRIBUTION).put("configured", true).put("state", "ready")
                .put("audio_available", false).put("local_audio_state", "off").put("services", JSONObject()
                    .put("message", true).put("checkin", false).put("question", false).put("operator", false).put("taxi", false)))
            page.push(JSONObject().put("type", "language_preferences").put("language", "en").put("staff_language", "es"))
            page.setShown(true)
        }
        fun evaluate(script: String): String {
            val done = CountDownLatch(1)
            var value = ""
            instrumentation.runOnMainSync { browser.evaluateJavascript(script) { value = it; done.countDown() } }
            check(done.await(10, TimeUnit.SECONDS))
            return value
        }
        fun until(script: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (evaluate(script) != "true") {
                check(System.nanoTime() < deadline) { "Receipt UI condition timed out" }
                Thread.sleep(50)
            }
        }
        fun click(action: String) { check(evaluate("(function(){var b=document.querySelector('[data-action=\"$action\"]');if(!b||b.disabled)return false;b.click();return true})()") == "true") }
        try {
            until("!!document.querySelector('[data-action=\"request:message\"]') && !document.querySelector('[data-action=\"request:message\"]').disabled")
            click("request:message"); click("assist-go")
            until("!!document.querySelector('.request-status') && document.querySelector('.request-status').textContent.indexOf('Acknowledged')>=0")
            val lease = owner.current ?: error("Missing reception lease")
            val read = readPaths.poll(5, TimeUnit.SECONDS) ?: error("Missing status read")
            check(!read.contains(lease.request.id)) { "Guest lease leaked into private server URL" }
            val public = statuses.poll(5, TimeUnit.SECONDS) ?: error("Missing status event")
            check(public.keys().asSequence().toSet() == setOf("type", "status_available", "item_id", "status", "version", "created", "acknowledged_at", "resolved_at"))
            check(evaluate("document.body.textContent.indexOf('PRIVATE')<0") == "true")
            val immutable = evaluate("document.querySelector('.request-receipt').textContent")
            // Guessed ID and stale generation must be denied by the actual Activity branch.
            instrumentation.runOnMainSync {
                handle.invoke(activity, JSONObject().put("action", "watch_request").put("item_id", "guessed").put("session_id", lease.request.id).put("generation", lease.generation))
                handle.invoke(activity, JSONObject().put("action", "watch_request").put("item_id", "fixture-receipt-1").put("session_id", lease.request.id).put("generation", lease.generation + 1))
            }
            desired.set("resolved")
            until("document.querySelector('.request-status').textContent.indexOf('Staff marked the request resolved')>=0")
            check(evaluate("document.querySelector('.request-receipt').textContent") == immutable)
            val resolvedReads = reads.get()
            Thread.sleep(5500)
            check(reads.get() == resolvedReads) { "Resolved receipt kept polling" }
            click("continue")
            desired.set("acknowledged")
            val release = CountDownLatch(1)
            blocked.set(release)
            click("message")
            until("!!document.querySelector('.receipt') && document.querySelector('.receipt').textContent.indexOf('fixture-receipt-2')>=0")
            val nextDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (reads.get() == resolvedReads) { check(System.nanoTime() < nextDeadline); Thread.sleep(20) }
            click("continue")
            until("!document.querySelector('.request-status')")
            release.countDown(); blocked.set(null)
            val departedReads = reads.get()
            Thread.sleep(5500)
            check(reads.get() == departedReads && evaluate("!document.querySelector('.request-status')") == "true") { "Departure allowed a late callback or another read" }
            check(actions.toList().count { it.optString("action") == "watch_request" } == 2)
            check(actions.toList().count { it.optString("action") == "unwatch_request" } == 2)
            // Pause while a third read is blocked: actual Activity lifecycle must end the watch.
            val pauseRelease = CountDownLatch(1)
            blocked.set(pauseRelease)
            click("message")
            until("!!document.querySelector('.receipt') && document.querySelector('.receipt').textContent.indexOf('fixture-receipt-3')>=0")
            val pauseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (reads.get() == departedReads) { check(System.nanoTime() < pauseDeadline); Thread.sleep(20) }
            instrumentation.runOnMainSync { instrumentation.callActivityOnPause(activity) }
            pauseRelease.countDown(); blocked.set(null)
            val pausedReads = reads.get()
            Thread.sleep(5500)
            check(reads.get() == pausedReads && owner.current == null) { "Paused Activity retained tracking" }
        } finally {
            blocked.get()?.countDown()
            instrumentation.runOnMainSync { gateway.dispose(); activity.finish() }
        }
    }
}
