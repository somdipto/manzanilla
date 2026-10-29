package com.agentdeck.hotel

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.io.DataInputStream
import java.io.IOException
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Real HTTPS/JSON requests through the shipped native gateway, with no hotel/provider access. */
class HotelGatewayTest {
    data class Request(val path: String, val authorization: String?, val body: JSONObject, val method: String)
    data class Reply(val code: Int, val body: String, val release: CountDownLatch? = null)
    private lateinit var server: ServerSocket
    private lateinit var serving: Thread
    @Volatile private var running = true
    private lateinit var gateway: HotelGateway
    private val requests = LinkedBlockingQueue<Request>()
    private val replies = LinkedBlockingQueue<Reply>()
    private val events = LinkedBlockingQueue<JSONObject>()
    private val failures = LinkedBlockingQueue<String>()
    private val lease = SessionOwner.Lease(SessionOwner.Request("fixture-ui-session", "reception", "es", "en"), 7)
    private val note = JSONObject().put("original_request", "Exact synthetic note.\nKeep this wording: café 日本語.")
        .put("guest_name", "Synthetic Guest").put("room_number", "204")

    @Before fun startServer() {
        server = tls.serverSocketFactory.createServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serving = Thread {
            while (running) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = DataInputStream(socket.getInputStream().buffered())
                        fun line(): String {
                            val bytes = ArrayList<Byte>()
                            while (true) {
                                val next = input.read()
                                if (next < 0) throw java.io.EOFException("Truncated fixture request")
                                if (next == 10) break
                                if (next != 13) bytes.add(next.toByte())
                            }
                            return bytes.toByteArray().toString(Charsets.US_ASCII)
                        }
                        val requestLine = line().split(" ")
                        val path = requestLine[1]
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val header = line()
                            if (header.isEmpty()) break
                            headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                        }
                        val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
                        input.readFully(body)
                        requests.add(Request(path, headers["authorization"], if (body.isEmpty()) JSONObject() else JSONObject(body.toString(Charsets.UTF_8)), requestLine[0]))
                        val reply = if (path.endsWith("/end")) Reply(200, "{\"status\":\"ended\"}")
                            else replies.poll(5, TimeUnit.SECONDS) ?: error("Missing fixture response")
                        check(reply.release?.await(5, TimeUnit.SECONDS) != false)
                        val bytes = reply.body.toByteArray(Charsets.UTF_8)
                        val head = "HTTP/1.1 ${reply.code} Fixture\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
                    }
                } catch (_: IOException) { /* Ending a native session can cancel its in-flight socket. */ }
            }
        }.apply { isDaemon = true; start() }
        gateway = newGateway("https://localhost:${server.localPort}")
        gateway.begin(lease)
    }

    private fun newGateway(base: String) = HotelGateway(base, "fixture-only-device-credential", { actual, event ->
        assertEquals(lease, actual)
        events.add(event)
    }, { _, _ -> fail("A note must never open the dialer") },
        { _, message, values -> values.entries.fold(message) { result, entry -> result.replace("{${entry.key}}", entry.value) } }, OkHttpClient.Builder()
        .sslSocketFactory(tls.socketFactory, trust).connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS).build(), { failures.add(it) })

    @After fun stopServer() { gateway.dispose(); running = false; server.close(); serving.join(2000) }
    private fun reply(body: JSONObject, code: Int = 200) { replies.add(Reply(code, body.toString())) }
    private fun request(): Request = requests.poll(7, TimeUnit.SECONDS) ?: error("Native HTTP request missing")
    private fun event(): JSONObject = events.poll(5, TimeUnit.SECONDS) ?: error("Native UI event missing")
    private fun draft() = JSONObject().put("status", "confirmation_required").put("confirmation_id", "fixture-note")
        .put("summary", "Message to staff:\n${note.getString("original_request")}")
    private fun prepareDraft() {
        reply(JSONObject().put("status", "ready"))
        reply(draft())
        gateway.action(lease, "message", note)
        assertEquals("/api/device/operations/start", request().path)
        assertEquals("confirming", event().getString("phase"))
        assertEquals("message", request().body.getString("action"))
    }

    @Test fun noteFormReviewAndConfirmationUseRealAuthenticatedHttpAndExactText() {
        reply(JSONObject().put("status", "ready"))
        val fields = JSONArray().put(JSONObject().put("name", "original_request").put("label", "Exact note")
            .put("required", true).put("max_length", 4000))
        reply(JSONObject().put("status", "needs_arguments").put("fields", fields))
        gateway.action(lease, "message", JSONObject())
        val start = request()
        assertEquals("Bearer fixture-only-device-credential", start.authorization)
        assertEquals("reception", start.body.getString("mode"))
        assertEquals("es", start.body.getString("language"))
        assertEquals("en", start.body.getString("staff_language"))
        val serverSession = start.body.getString("session_id")
        assertNotEquals(lease.request.id, serverSession)
        val firstAction = request()
        assertEquals("/api/device/operations/$serverSession/action", firstAction.path)
        val form = event()
        assertEquals("operation_form", form.getString("type"))
        assertEquals("message", form.getString("action"))
        assertEquals(fields.toString(), form.getJSONArray("fields").toString())

        reply(draft())
        gateway.action(lease, "message", note)
        val review = request()
        assertEquals(firstAction.path, review.path) // One operation session, not another start request.
        assertEquals(note.toString(), review.body.getJSONObject("arguments").toString())
        assertNotEquals(firstAction.body.getString("call_id"), review.body.getString("call_id"))
        val confirm = event()
        assertEquals("operation:fixture-note", confirm.getString("draft_id"))
        assertTrue(confirm.getString("message").contains(note.getString("original_request")))

        reply(JSONObject().put("status", "saved").put("text", "Synthetic saved receipt")
            .put("result", JSONObject().put("item_id", "fixture-receipt")))
        gateway.confirm(lease, confirm.getString("draft_id"), true)
        val write = request()
        assertEquals("/api/device/operations/$serverSession/confirm", write.path)
        assertEquals("fixture-note", write.body.getString("confirmation_id"))
        assertTrue(write.body.getBoolean("accepted"))
        val saved = event()
        assertEquals("operation_result", saved.getString("type"))
        assertEquals("saved", saved.getString("status"))
        assertEquals("fixture-receipt", saved.getJSONObject("result").getString("item_id"))
    }

    @Test fun cancellationSendsFalseForTheExactReviewedDraft() {
        prepareDraft()
        reply(JSONObject().put("status", "cancelled").put("text", "Cancelled. Nothing was sent."))
        gateway.confirm(lease, "operation:fixture-note", false)
        val cancel = request()
        assertFalse(cancel.body.getBoolean("accepted"))
        assertEquals("fixture-note", cancel.body.getString("confirmation_id"))
        assertEquals("cancelled", event().getString("status"))
    }

    @Test fun failedConfirmationCanRetryTheSameDraftWithoutAnotherAction() {
        prepareDraft()
        reply(JSONObject().put("detail", "Synthetic failure"), 503)
        gateway.confirm(lease, "operation:fixture-note", true)
        val failed = request()
        assertEquals("error", event().getString("status"))
        assertTrue(gateway.canReconcileConfirmation(lease, "operation:fixture-note", true))
        assertEquals("message", gateway.confirmationAction(lease, "operation:fixture-note"))
        reply(JSONObject().put("status", "saved").put("text", "Original synthetic receipt"))
        gateway.confirm(lease, "operation:fixture-note", true)
        val retry = request()
        assertEquals(failed.path, retry.path)
        assertEquals(failed.body.toString(), retry.body.toString())
        assertEquals("saved", event().getString("status"))
    }

    @Test fun uncertainApprovalMayStillCancelTheSameDraftAndCannotStartAnotherAction() {
        prepareDraft()
        reply(JSONObject(), 503)
        gateway.confirm(lease, "operation:fixture-note", true)
        val attempt = request()
        assertTrue(event().getBoolean("reconciliation_pending"))
        gateway.action(lease, "message", note)
        assertEquals("notice", event().getString("type"))
        assertNull(requests.poll(200, TimeUnit.MILLISECONDS))
        reply(JSONObject().put("status", "saved").put("text", "Earlier approval already saved this request"))
        gateway.confirm(lease, "operation:fixture-note", false)
        val cancellation = request()
        assertEquals(attempt.path, cancellation.path)
        assertEquals(attempt.body.getString("confirmation_id"), cancellation.body.getString("confirmation_id"))
        assertFalse(cancellation.body.getBoolean("accepted"))
        val settled = event()
        assertEquals("saved", settled.getString("status"))
        assertFalse(settled.getBoolean("reconciliation_pending"))
    }

    @Test fun pendingServerResultsKeepBothActionAndConfirmationReplayIdentity() {
        reply(JSONObject().put("status", "ready"))
        reply(JSONObject().put("status", "pending"))
        gateway.action(lease, "message", note)
        request()
        val action = request()
        assertTrue(event().getBoolean("reconciliation_pending"))
        reply(draft())
        gateway.action(lease, "message", note)
        assertEquals(action.body.toString(), request().body.toString())
        assertEquals("confirming", event().getString("phase"))
        reply(JSONObject().put("status", "pending"))
        gateway.confirm(lease, "operation:fixture-note", true)
        val confirm = request()
        assertTrue(event().getBoolean("reconciliation_pending"))
        assertTrue(gateway.canReconcileConfirmation(lease, "operation:fixture-note", true))
        reply(JSONObject().put("status", "saved"))
        gateway.confirm(lease, "operation:fixture-note", true)
        assertEquals(confirm.body.toString(), request().body.toString())
        assertEquals("saved", event().getString("status"))
    }

    @Test fun malformedReceiptReleasesBusyStateAndNeverClaimsSuccess() {
        reply(JSONObject().put("status", "ready"))
        replies.add(Reply(200, "not-json"))
        gateway.action(lease, "message", note)
        request(); request()
        val error = event()
        assertEquals("error", error.getString("status"))
        assertTrue(error.getString("text").contains("No success is confirmed"))
        reply(draft())
        gateway.action(lease, "message", note)
        assertTrue(request().path.endsWith("/action"))
        assertEquals("confirming", event().getString("phase"))
    }

    @Test fun uncertainActionRetryRetainsItsExactCallIdentityAndCannotBecomeAnotherRequest() {
        reply(JSONObject().put("status", "ready"))
        replies.add(Reply(200, "truncated receipt"))
        gateway.action(lease, "message", note)
        request()
        val original = request()
        val failed = event()
        assertEquals("error", failed.getString("status"))
        assertEquals("offline", failures.poll(1, TimeUnit.SECONDS))
        assertFalse(failed.has("_uncertain"))
        assertTrue(failed.getBoolean("reconciliation_pending"))
        assertTrue(gateway.canReconcile(lease, "message", note))
        val changed = JSONObject(note.toString()).put("room_number", "205")
        assertFalse(gateway.canReconcile(lease, "message", changed))
        gateway.action(lease, "message", changed)
        assertEquals("notice", event().getString("type"))
        assertNull(requests.poll(200, TimeUnit.MILLISECONDS))
        reply(draft())
        gateway.action(lease, "message", note)
        val retry = request()
        assertEquals(original.path, retry.path)
        assertEquals(original.body.toString(), retry.body.toString())
        assertEquals("confirming", event().getString("phase"))
        assertFalse(gateway.canReconcile(lease, "message", note))
    }

    @Test fun serverFailureRetainsUncertainIdentityButDefinitiveRefusalReleasesIt() {
        reply(JSONObject().put("status", "ready"))
        reply(JSONObject(), 503)
        gateway.action(lease, "message", note)
        request()
        val original = request()
        assertEquals("error", event().getString("status"))
        assertTrue(gateway.canReconcile(lease, "message", note))
        reply(JSONObject(), 403)
        gateway.action(lease, "message", note)
        assertEquals(original.body.toString(), request().body.toString())
        assertEquals("error", event().getString("status"))
        assertFalse(gateway.canReconcile(lease, "message", note))
        assertEquals("offline", failures.poll(1, TimeUnit.SECONDS))
        assertEquals("unauthorized", failures.poll(1, TimeUnit.SECONDS))
    }

    @Test fun endedSessionCannotDeliverALateFormOrSubmitAnotherNote() {
        reply(JSONObject().put("status", "ready"))
        val release = CountDownLatch(1)
        replies.add(Reply(200, draft().toString(), release))
        gateway.action(lease, "message", note)
        request(); request()
        gateway.end()
        release.countDown()
        assertTrue(request().path.endsWith("/end"))
        gateway.action(lease, "message", note)
        gateway.confirm(lease, "operation:fixture-note", true)
        assertNull(events.poll(200, TimeUnit.MILLISECONDS))
        assertNull(requests.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test fun disposeDoesNotCancelTheExactSessionEnd() {
        prepareDraft()
        gateway.dispose()
        val end = request()
        assertTrue(end.path.matches(Regex("/api/device/operations/[0-9a-f-]+/end")))
        assertEquals("Bearer fixture-only-device-credential", end.authorization)
    }

    @Test fun disposeWaitsForInFlightStartThenClosesItsSessionWithoutDelivering() {
        val release = CountDownLatch(1)
        replies.add(Reply(200, JSONObject().put("status", "ready").toString(), release))
        gateway.action(lease, "message", note)
        val start = request()
        val serverSession = start.body.getString("session_id")
        gateway.dispose()
        release.countDown()
        assertEquals("/api/device/operations/$serverSession/end", request().path)
        assertNull(events.poll(200, TimeUnit.MILLISECONDS))
        assertNull(requests.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test fun disposedUncertainStartStillAttemptsScopedCleanup() {
        val release = CountDownLatch(1)
        replies.add(Reply(503, "{}", release))
        gateway.action(lease, "message", note)
        val start = request()
        val serverSession = start.body.getString("session_id")
        gateway.dispose()
        release.countDown()
        assertEquals("/api/device/operations/$serverSession/end", request().path)
        assertNull(events.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test fun plaintextConfigurationCannotReachHotelTransport() {
        gateway.dispose()
        gateway = newGateway("http://localhost:${server.localPort}")
        gateway.begin(lease)
        gateway.action(lease, "message", note)
        assertEquals("unavailable", event().getString("status"))
        assertNull(requests.poll(200, TimeUnit.MILLISECONDS))
    }

    private fun savedReceipt(item: String = "fixture-receipt"): String {
        reply(JSONObject().put("status", "ready"))
        reply(JSONObject().put("status", "saved").put("text", "Saved, not acknowledged")
            .put("result", JSONObject().put("item_id", item).put("pms_delivered", false)))
        gateway.action(lease, "message", note)
        val sid = request().body.getString("session_id")
        assertEquals("/api/device/operations/$sid/action", request().path)
        assertEquals("saved", event().getString("status"))
        return sid
    }

    private fun publicStatus(status: String = "open", version: Int = 0, item: String = "fixture-receipt") =
        JSONObject().put("item_id", item).put("status", status).put("version", version).put("created", 100.0)
            .put("acknowledged_at", if (version > 0) 110.0 else JSONObject.NULL)
            .put("resolved_at", if (status == "resolved") 120.0 else JSONObject.NULL)

    @Test fun watchRequiresTheLastDeliveredReceiptAndPrivateServerSessionAndWhitelistsStatus() {
        gateway.watch(lease, "guessed")
        assertNull(requests.poll(150, TimeUnit.MILLISECONDS))
        val sid = savedReceipt()
        gateway.watch(lease.copy(generation = lease.generation + 1), "fixture-receipt")
        gateway.watch(lease, "some-other-receipt")
        assertNull(requests.poll(150, TimeUnit.MILLISECONDS))
        reply(publicStatus().put("resolution_note", "private note").put("responsible_name", "private name")
            .put("acknowledged_by", "private actor").put("payload", note))
        gateway.watch(lease, "fixture-receipt")
        gateway.watch(lease, "fixture-receipt")
        val read = request()
        assertEquals("/api/device/operations/$sid/items/fixture-receipt", read.path)
        assertEquals("GET", read.method)
        assertEquals(0, read.body.length())
        assertFalse(read.path.contains(lease.request.id))
        assertEquals("Bearer fixture-only-device-credential", read.authorization)
        val status = event()
        assertEquals(setOf("type", "status_available", "item_id", "status", "version", "created", "acknowledged_at", "resolved_at"),
            status.keys().asSequence().toSet())
        assertEquals("open", status.getString("status"))
        gateway.unwatch(lease)
        assertNull(requests.poll(5500, TimeUnit.MILLISECONDS))
    }

    @Test fun periodicWatchRetainsLastKnownStateOnFailureThenStopsAtStaffResolution() {
        savedReceipt()
        reply(publicStatus("acknowledged", 1))
        gateway.watch(lease, "fixture-receipt")
        request()
        assertEquals("acknowledged", event().getString("status"))
        reply(JSONObject().put("resolution_note", "never forwarded"), 500)
        request()
        val unavailable = event()
        assertEquals("acknowledged", unavailable.getString("status"))
        assertEquals(1, unavailable.getInt("version"))
        assertFalse(unavailable.getBoolean("status_available"))
        assertTrue(unavailable.getString("text").contains("last known state"))
        assertFalse(unavailable.toString().contains("never forwarded"))
        reply(publicStatus("resolved", 2))
        request()
        val resolved = event()
        assertEquals("resolved", resolved.getString("status"))
        assertFalse(resolved.has("booked"))
        gateway.watch(lease, "fixture-receipt")
        assertNull(requests.poll(5500, TimeUnit.MILLISECONDS))
    }

    @Test fun cancelledReadCannotDeliverAcrossUnwatchOrGuestReplacement() {
        savedReceipt()
        val release = CountDownLatch(1)
        replies.add(Reply(200, publicStatus("resolved", 2).toString(), release))
        gateway.watch(lease, "fixture-receipt")
        request()
        gateway.unwatch(lease)
        gateway.begin(lease.copy(generation = lease.generation + 1))
        release.countDown()
        assertTrue(request().path.endsWith("/end"))
        gateway.watch(lease, "fixture-receipt")
        gateway.watch(lease.copy(generation = lease.generation + 1), "fixture-receipt")
        assertNull(events.poll(300, TimeUnit.MILLISECONDS))
        assertNull(requests.poll(5500, TimeUnit.MILLISECONDS))
    }

    @Test fun authorizationDenialStopsStatusWithoutCreatingOrExtendingASession() {
        savedReceipt()
        reply(JSONObject(), 403)
        gateway.watch(lease, "fixture-receipt")
        assertTrue(request().path.endsWith("/items/fixture-receipt"))
        assertFalse(event().getBoolean("status_available"))
        gateway.watch(lease, "fixture-receipt") // Denial is terminal even if a bridge repeats the request.
        assertNull(requests.poll(5500, TimeUnit.MILLISECONDS))
    }

    @Test fun olderStatusCannotRegressTheLastKnownVersionAndForeignUnwatchHasNoEffect() {
        savedReceipt()
        reply(publicStatus("acknowledged", 1))
        gateway.watch(lease, "fixture-receipt")
        request(); event()
        gateway.unwatch(lease.copy(generation = lease.generation + 1))
        reply(publicStatus("open", 0))
        request()
        val status = event()
        assertEquals("acknowledged", status.getString("status"))
        assertEquals(1, status.getInt("version"))
        gateway.unwatch(lease)
    }

    @Test fun unwatchNeverCancelsAnUncertainSubmittedWrite() {
        savedReceipt()
        val release = CountDownLatch(1)
        replies.add(Reply(200, draft().toString(), release))
        gateway.action(lease, "message", note)
        assertTrue(request().path.endsWith("/action"))
        gateway.unwatch(lease)
        release.countDown()
        assertEquals("confirming", event().getString("phase"))
        assertEquals("message", gateway.confirmationAction(lease, "operation:fixture-note"))
    }

    @Test fun aNewWatchRevisionDropsTheCancelledReadEvenForTheSameLeaseAndItem() {
        savedReceipt()
        val release = CountDownLatch(1)
        replies.add(Reply(200, publicStatus("resolved", 2).toString(), release))
        gateway.watch(lease, "fixture-receipt")
        request()
        gateway.unwatch(lease)
        reply(publicStatus("open", 0))
        gateway.watch(lease, "fixture-receipt")
        release.countDown()
        assertTrue(request().path.endsWith("/items/fixture-receipt"))
        val status = event()
        assertEquals("open", status.getString("status"))
        assertEquals(0, status.getInt("version"))
        assertNull(events.poll(200, TimeUnit.MILLISECONDS))
        gateway.unwatch(lease)
    }

    @Test fun aLaterDurableReceiptRevokesThePreviousReceiptEligibility() {
        savedReceipt()
        reply(JSONObject().put("status", "queued").put("text", "Queued, not connected")
            .put("result", JSONObject().put("escalation_id", "second-receipt").put("connected", false)))
        gateway.action(lease, "operator", JSONObject())
        assertTrue(request().path.endsWith("/action"))
        assertFalse(event().getJSONObject("result").getBoolean("connected"))
        gateway.watch(lease, "fixture-receipt")
        assertNull(requests.poll(200, TimeUnit.MILLISECONDS))
        reply(publicStatus(item = "second-receipt"))
        gateway.watch(lease, "second-receipt")
        assertTrue(request().path.endsWith("/items/second-receipt"))
        assertEquals("second-receipt", event().getString("item_id"))
        gateway.unwatch(lease)
    }

    companion object {
        private lateinit var tls: SSLContext
        private lateinit var trust: X509TrustManager
        @JvmStatic @BeforeClass fun fixtureTls() {
            // This ephemeral certificate exists only in a temporary test directory.
            // Production trust managers and HTTPS requirements are never overridden.
            val directory = Files.createTempDirectory("hotel-gateway-fixture-")
            val file = directory.resolve("localhost.p12")
            val log = directory.resolve("keytool.log")
            try {
                val process = ProcessBuilder(System.getProperty("java.home") + "/bin/keytool", "-genkeypair",
                    "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                    "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                    "-keystore", file.toString(), "-storepass", "fixture-password", "-keypass", "fixture-password", "-noprompt")
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start()
                check(process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0) { "Fixture TLS key generation failed" }
                val keys = KeyStore.getInstance("PKCS12").apply {
                    Files.newInputStream(file).use { load(it, "fixture-password".toCharArray()) }
                }
                val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "fixture-password".toCharArray()) }
                val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
                trust = tm.trustManagers.single() as X509TrustManager
                tls = SSLContext.getInstance("TLS").apply { init(km.keyManagers, tm.trustManagers, null) }
            } finally { Files.deleteIfExists(file); Files.deleteIfExists(log); Files.deleteIfExists(directory) }
        }
    }
}
