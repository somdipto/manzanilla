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
    data class Request(val path: String, val authorization: String?, val body: JSONObject)
    data class Reply(val code: Int, val body: String, val release: CountDownLatch? = null)
    private lateinit var server: ServerSocket
    private lateinit var serving: Thread
    @Volatile private var running = true
    private lateinit var gateway: HotelGateway
    private val requests = LinkedBlockingQueue<Request>()
    private val replies = LinkedBlockingQueue<Reply>()
    private val events = LinkedBlockingQueue<JSONObject>()
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
                        val path = line().split(" ")[1]
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val header = line()
                            if (header.isEmpty()) break
                            headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                        }
                        val body = ByteArray(headers.getValue("content-length").toInt())
                        input.readFully(body)
                        requests.add(Request(path, headers["authorization"], JSONObject(body.toString(Charsets.UTF_8))))
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
    }, { _, _ -> fail("A note must never open the dialer") }, OkHttpClient.Builder()
        .sslSocketFactory(tls.socketFactory, trust).connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS).build())

    @After fun stopServer() { gateway.dispose(); running = false; server.close(); serving.join(2000) }
    private fun reply(body: JSONObject, code: Int = 200) { replies.add(Reply(code, body.toString())) }
    private fun request(): Request = requests.poll(5, TimeUnit.SECONDS) ?: error("Native HTTP request missing")
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
        reply(JSONObject().put("status", "saved").put("text", "Original synthetic receipt"))
        gateway.confirm(lease, "operation:fixture-note", true)
        val retry = request()
        assertEquals(failed.path, retry.path)
        assertEquals(failed.body.toString(), retry.body.toString())
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
