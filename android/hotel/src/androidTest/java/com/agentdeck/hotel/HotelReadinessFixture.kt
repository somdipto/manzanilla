package com.agentdeck.hotel

import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.webkit.WebView
import com.agentdeck.ui.ReceptionView
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Runs only on a disposable key-free emulator. Intercepts the native probe, never TLS or provider traffic. */
internal object HotelReadinessFixture {
    private class Reply(val code: Int = 200, val body: String = payload(), val failure: Boolean = false,
                        val release: CountDownLatch? = null) {
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
    }
    private fun payload(services: Boolean = true, name: String = "Synthetic Hotel"): String = JSONObject()
        .put("protocol", 1).put("hotel", JSONObject().put("name", name).put("config_version", 7)
            .put("knowledge", JSONObject().put("private", "must-not-reach-page"))
            .put("operator_primary", "+15551234567").put("provider_key", "must-not-reach-page"))
        .apply { if (services) put("services", JSONObject().put("checkin", true).put("question", false)
            .put("taxi", true).put("operator", false).put("message", true)) }.toString()

    fun verify(test: Instrumentation) {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(BuildConfig.FISH_API_KEY.isEmpty())
        val context = test.targetContext
        val connection = HotelConnection(context)
        check(connection.load() == null) { "Readiness requires a fresh disposable emulator" }
        check(SecretStore(context, "fish").status() == SecretStore.Status.ABSENT)
        if (BuildConfig.DISTRIBUTION == "hotel") FishStartupFixture.verifyHotelPreparation(test)
        val deck = context.getSharedPreferences("deck", 0)
        val oldGuest = deck.getString("reception_guest_language", null)
        val oldStaff = deck.getString("reception_staff_language", null)
        val oldDirect = TranslationSettings(context).directFishSelected
        deck.edit().putString("reception_guest_language", "es").putString("reception_staff_language", "en").commit()
        val activity = test.startActivitySync(Intent(context, HotelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HotelActivity
        fun field(name: String) = HotelActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun invoke(name: String) = HotelActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
        val handle = HotelActivity::class.java.getDeclaredMethod("handle", JSONObject::class.java).apply { isAccessible = true }
        val owner = field("owner").get(activity) as SessionOwner
        val browser = (field("reception").get(activity) as ReceptionView).getChildAt(0) as WebView
        fun js(script: String): String {
            val completed = CountDownLatch(1)
            var value = ""
            test.runOnMainSync { browser.evaluateJavascript(script) { value = it; completed.countDown() } }
            check(completed.await(15, TimeUnit.SECONDS))
            return value
        }
        fun awaitState(expected: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            val observed = "(function(){var events=window.readinessEvents.filter(function(e){return e.type==='connection'});" +
                "var last=events[events.length-1];return !!last&&last.state===" + JSONObject.quote(expected) +
                "&&last.connected===" + (expected == "ready") + "})()"
            while (true) {
                var state = ""
                var idle = false
                test.runOnMainSync { state = field("hotelStatus").get(activity).toString(); idle = field("hotelProbe").get(activity) == null }
                if (state == expected && idle && js(observed) == "true") return
                check(System.nanoTime() < deadline) { "Readiness state did not settle" }
                Thread.sleep(20)
            }
        }
        fun send(message: JSONObject) = test.runOnMainSync { handle.invoke(activity, message) }
        val replies = LinkedBlockingQueue<Reply>()
        val requests = LinkedBlockingQueue<JSONObject>()
        val client = (field("readinessClient").get(activity) as OkHttpClient).newBuilder()
            .dispatcher(okhttp3.Dispatcher()).addInterceptor { chain ->
            check(chain.request().url.encodedPath == "/api/device/heartbeat") { "Probe opened a non-heartbeat path" }
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            requests.add(JSONObject(buffer.readUtf8()))
            val reply = replies.poll(5, TimeUnit.SECONDS) ?: error("Unexpected readiness probe")
            reply.entered.countDown()
            try {
                check(reply.release?.await(5, TimeUnit.SECONDS) != false)
                if (reply.failure) throw IOException("Synthetic offline")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(reply.code)
                    .message("Fixture").body(reply.body.toResponseBody()).build()
            } finally { reply.finished.countDown() }
        }.build()
        fun probe(reply: Reply) {
            replies.add(reply)
            test.runOnMainSync { invoke("refreshHotelStatus") }
            check(reply.entered.await(5, TimeUnit.SECONDS))
        }
        var actionClient: OkHttpClient? = null
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (js("typeof window.manzania === 'object' && typeof window.manzania.receive === 'function'") != "true") {
                check(System.nanoTime() < deadline)
                Thread.sleep(100)
            }
            js("window.readinessEvents=[];var readinessReceive=window.manzania.receive;window.manzania=Object.freeze({receive:function(e){window.readinessEvents.push(e);return readinessReceive(e)}})")
            test.runOnMainSync {
                (field("readinessClient").get(activity) as OkHttpClient).dispatcher.executorService.shutdown()
                field("readinessClient").set(activity, client)
                invoke("refreshHotelStatus")
                (field("ui").get(activity) as android.os.Handler).removeCallbacks(field("hotelPoll").get(activity) as Runnable)
                check(field("hotelStatus").get(activity).toString() == "unconfigured")
            }
            check(requests.isEmpty())
            send(JSONObject().put("action", "start").put("session_id", UUID.randomUUID().toString())
                .put("mode", "reception").put("language", "es").put("staff_language", "en"))
            check(owner.current == null)
            check(connection.save("https://readiness.invalid", "a".repeat(40)))
            probe(Reply())
            awaitState("ready")
            check(requests.poll(1, TimeUnit.SECONDS)!!.getString("language") == "es")
            check(js("JSON.stringify(window.readinessEvents).indexOf('must-not-reach-page')") == "-1")
            check(js("JSON.stringify(window.readinessEvents).indexOf('operator_primary')") == "-1")
            probe(Reply(401)); awaitState("unauthorized")
            probe(Reply(403)); awaitState("unauthorized")
            probe(Reply(failure = true)); awaitState("offline")
            val timedOut = Reply(release = CountDownLatch(1))
            probe(timedOut)
            Thread.sleep(3_200)
            timedOut.release!!.countDown()
            awaitState("offline") // Inherits the actual activity client's overall deadline.
            probe(Reply(body = "not-json")); awaitState("offline")
            probe(Reply(body = payload(false))); awaitState("ready")
            test.runOnMainSync {
                val reason = HotelActivity::class.java.getDeclaredMethod("hotelBlockReason", String::class.java).apply { isAccessible = true }
                check(reason.invoke(activity, "taxi") == "The hotel service needs an update. Please ask staff.")
                check(!(invoke("safeServices") as JSONObject).getBoolean("taxi"))
            }
            probe(Reply()); awaitState("ready")
            requests.clear()
            val stale = Reply(release = CountDownLatch(1))
            replies.add(stale)
            test.runOnMainSync {
                field("hotelStatusAt").setLong(activity, SystemClock.elapsedRealtime() - 31_000L)
                val reason = HotelActivity::class.java.getDeclaredMethod("hotelBlockReason", String::class.java).apply { isAccessible = true }
                check(reason.invoke(activity, "taxi") == "Checking the hotel connection. Please wait.")
                invoke("refreshHotelStatus") // One outstanding call, even after repeated requests.
                check(field("hotelStatus").get(activity).toString() == "checking")
            }
            check(stale.entered.await(5, TimeUnit.SECONDS))
            check(requests.size == 1)
            stale.release!!.countDown(); awaitState("ready")
            val delayed = Reply(body = payload(name = "Old Hotel"), release = CountDownLatch(1))
            probe(delayed)
            check(connection.save("https://replacement.invalid", "b".repeat(40)))
            val replacement = Reply(403)
            probe(replacement); awaitState("unauthorized")
            delayed.release!!.countDown()
            check(delayed.finished.await(5, TimeUnit.SECONDS))
            test.waitForIdleSync()
            test.runOnMainSync { check(field("hotelStatus").get(activity).toString() == "unauthorized"); check(field("hotelName").get(activity) != "Old Hotel") }
            val oldLocale = Reply(release = CountDownLatch(1))
            probe(oldLocale)
            val locale = Reply(401)
            replies.add(locale)
            send(JSONObject().put("action", "languages").put("language", "ar").put("staff_language", "en"))
            check(locale.entered.await(5, TimeUnit.SECONDS)); awaitState("unauthorized")
            oldLocale.release!!.countDown()
            check(oldLocale.finished.await(5, TimeUnit.SECONDS)); test.waitForIdleSync()
            test.runOnMainSync {
                check(field("hotelStatus").get(activity).toString() == "unauthorized")
                check(owner.current == null && field("gateway").get(activity) == null)
                check(field("audioState").get(activity) == "off")
            }
            if (BuildConfig.DISTRIBUTION == "personal-test") {
                check(SecretStore(context, "fish").save("synthetic-personal-key"))
                check(TranslationSettings(context).selectDirectFish())
                test.runOnMainSync { invoke("pushState") }
                check(js("window.readinessEvents.filter(function(e){return e.type==='reception_info'}).slice(-1)[0].audio_available") == "true")
                check(js("window.readinessEvents.filter(function(e){return e.type==='connection'}).slice(-1)[0].connected") == "false")
                SecretStore(context, "fish").clear()
                TranslationSettings(context).selectHotelConnection()
            }
            // A form may change UI locale; its immutable operation language must not change.
            probe(Reply()); awaitState("ready")
            send(JSONObject().put("action", "start").put("session_id", UUID.randomUUID().toString())
                .put("mode", "reception").put("language", "ar").put("staff_language", "en"))
            val lease = checkNotNull(owner.current)
            val gateway = checkNotNull(field("gateway").get(activity) as? HotelGateway)
            val operationRequests = LinkedBlockingQueue<JSONObject>()
            val gatewayClientField = HotelGateway::class.java.getDeclaredField("client").apply { isAccessible = true }
            val originalGatewayClient = gatewayClientField.get(gateway) as OkHttpClient
            val interceptedActions = originalGatewayClient.newBuilder().dispatcher(okhttp3.Dispatcher()).addInterceptor { chain ->
                val buffer = Buffer()
                chain.request().body!!.writeTo(buffer)
                operationRequests.add(JSONObject(buffer.readUtf8()))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403)
                    .message("Fixture").body("{}".toResponseBody()).build()
            }.build()
            actionClient = interceptedActions
            test.runOnMainSync { gatewayClientField.set(gateway, interceptedActions) }
            originalGatewayClient.dispatcher.executorService.shutdown()
            send(JSONObject().put("action", "question").put("session_id", lease.request.id)
                .put("generation", lease.generation).put("arguments", JSONObject().put("query", "synthetic question")))
            check(js("window.readinessEvents.filter(function(e){return e.type==='operation_result'}).slice(-1)[0].status") == "\"unavailable\"")
            check(owner.current == lease)
            check(operationRequests.isEmpty()) // The absent service was denied before even starting a remote session.
            replies.add(Reply())
            send(JSONObject().put("action", "languages").put("language", "fr").put("staff_language", "es"))
            awaitState("ready")
            check(owner.current == lease && lease.request.guest == "ar" && lease.request.staff == "en")
            val olderHeartbeat = Reply(release = CountDownLatch(1))
            probe(olderHeartbeat)
            send(JSONObject().put("action", "taxi").put("session_id", lease.request.id)
                .put("generation", lease.generation).put("arguments", JSONObject()))
            awaitState("unauthorized")
            check(operationRequests.poll(1, TimeUnit.SECONDS)!!.getString("language") == "ar")
            olderHeartbeat.release!!.countDown()
            check(olderHeartbeat.finished.await(5, TimeUnit.SECONDS))
            test.waitForIdleSync()
            test.runOnMainSync { check(field("hotelStatus").get(activity).toString() == "unauthorized") }
            send(JSONObject().put("action", "stop").put("session_id", lease.request.id).put("generation", lease.generation))
            if (BuildConfig.DISTRIBUTION == "hotel") {
                val appearance = deck.getString("reception_appearance", "dark")
                val night = deck.getBoolean("reception_night", false)
                for (action in listOf("appearance", "night", "check_updates", "apply_update"))
                    send(JSONObject().put("action", action).put("value", if (appearance == "dark") "light" else "dark").put("enabled", !night))
                check(deck.getString("reception_appearance", "dark") == appearance)
                check(deck.getBoolean("reception_night", false) == night)
                test.runOnMainSync {
                    val result = HotelActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
                    result.invoke(activity, 42, android.app.Activity.RESULT_OK, Intent().putExtra("maintenance_action", "erase_credentials"))
                    result.invoke(activity, 42, android.app.Activity.RESULT_OK, Intent().putExtra("maintenance_action", "appearance_light"))
                    check(deck.getString("reception_appearance", "dark") == appearance)
                    field("awaitingStaffSetup").setBoolean(activity, true)
                    result.invoke(activity, 42, android.app.Activity.RESULT_OK, Intent().putExtra("maintenance_action", "erase_credentials"))
                    check(field("pendingMaintenance").get(activity) == null)
                }
            }
            val paused = Reply(release = CountDownLatch(1))
            probe(paused)
            test.runOnMainSync { test.callActivityOnPause(activity) }
            paused.release!!.countDown()
            check(paused.finished.await(5, TimeUnit.SECONDS))
            test.waitForIdleSync()
            test.runOnMainSync { check(field("hotelProbe").get(activity) == null) }
            replies.add(Reply())
            test.runOnMainSync { test.callActivityOnResume(activity) }
            awaitState("ready")
            val dying = Reply(release = CountDownLatch(1))
            probe(dying)
            test.runOnMainSync { activity.finish() }
            val destroyedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            var destroyed = false
            while (!destroyed) {
                test.runOnMainSync { destroyed = activity.isDestroyed }
                check(destroyed || System.nanoTime() < destroyedBy) { "Activity destruction did not settle" }
                if (!destroyed) Thread.sleep(20)
            }
            dying.release!!.countDown()
            check(dying.finished.await(5, TimeUnit.SECONDS))
            test.waitForIdleSync()
            test.runOnMainSync { check(field("hotelProbe").get(activity) == null) }
        } finally {
            replies.forEach { it.release?.countDown() }
            test.runOnMainSync { activity.finish() }
            test.waitForIdleSync()
            SecretStore(context, "hotel_connection").clear()
            SecretStore(context, "fish").clear()
            if (oldDirect) TranslationSettings(context).selectDirectFish() else TranslationSettings(context).selectHotelConnection()
            deck.edit().putString("reception_guest_language", oldGuest).putString("reception_staff_language", oldStaff).commit()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            actionClient?.dispatcher?.executorService?.shutdownNow()
            actionClient?.connectionPool?.evictAll()
        }
    }
}
