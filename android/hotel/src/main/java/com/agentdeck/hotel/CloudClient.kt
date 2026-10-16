package com.agentdeck.hotel

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A single ticket/socket lifetime. A stopped ticket can never create a replacement socket. */
class CloudClient(private val base: String, private val token: String,
                  private val event: (JSONObject) -> Unit, private val audio: (ByteArray) -> Unit) {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS).build()
    private val lock = Any()
    private var socket: WebSocket? = null
    private var ticketCall: Call? = null
    private var started = false
    private var stopped = false
    private val ticketDetached = CountDownLatch(1)
    private val socketDetached = CountDownLatch(1)

    init { require(base.startsWith("https://") && !base.endsWith("/")); require(token.isNotBlank()) }

    fun start(mode: String, language: String, staffLanguage: String, sessionId: String, generation: Int) = synchronized(lock) {
        if (started || stopped) return@synchronized
        if (!AudioPolicy.allowStart {
            stopped = true
            event(JSONObject().put("type", "error").put("message", it))
        }) return@synchronized
        started = true
        val body = JSONObject().put("mode", mode).put("language", language).put("staff_language", staffLanguage)
            .put("session_id", sessionId).put("generation", generation)
        val request = Request.Builder().url("$base/api/device/session").header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val call = client.newCall(request)
        ticketCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                try { deliver(JSONObject().put("type", "error").put("message", "Voice service unavailable. Retry or contact reception.")) }
                finally { ticketDetached.countDown() }
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) error("Ticket rejected")
                        val ticket = JSONObject(it.body?.string().orEmpty()).getString("ticket")
                        synchronized(lock) {
                            if (stopped) return
                            val request = Request.Builder().url(base.replaceFirst("https://", "wss://") + "/ws/device")
                                .header("X-Orange-Ticket", ticket).build()
                            socket = client.newWebSocket(request, listener)
                        }
                    }
                } catch (_: Exception) {
                    deliver(JSONObject().put("type", "error").put("message", "Device authorization or voice setup failed. Contact reception."))
                } finally { ticketDetached.countDown() }
            }
        })
    }

    private fun deliver(value: JSONObject) {
        if (synchronized(lock) { !stopped }) event(value)
    }
    private val listener = object : WebSocketListener() {
        override fun onMessage(ws: WebSocket, text: String) {
            val value = try { JSONObject(text) } catch (_: Exception) { return }
            deliver(value)
        }
        override fun onMessage(ws: WebSocket, bytes: ByteString) {
            if (synchronized(lock) { !stopped }) audio(bytes.toByteArray())
        }
        override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(code, null) }
        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            try { deliver(JSONObject().put("type", "error").put("message", "Voice connection lost. Retry or contact reception.")) }
            finally { socketDetached.countDown() }
        }
        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            try { deliver(JSONObject().put("type", "ended")) }
            finally { socketDetached.countDown() }
        }
    }

    fun sendAudio(bytes: ByteArray, size: Int = bytes.size): Boolean = synchronized(lock) {
        val ws = socket ?: return@synchronized false
        if (stopped || size <= 0 || size > bytes.size || ws.queueSize() + size > 48000) return@synchronized false
        ws.send(bytes.toByteString(0, size))
    }
    fun send(value: JSONObject): Boolean = synchronized(lock) { !stopped && socket?.send(value.toString()) == true }

    /** Blocking detach barrier; engine teardown owns its worker, never the Android main thread. */
    fun stop() {
        val wasStarted: Boolean
        synchronized(lock) {
            stopped = true
            wasStarted = started
            ticketCall?.cancel()
            socket?.cancel()
        }
        if (wasStarted) ticketDetached.await()
        val ws = synchronized(lock) { socket }
        ws?.cancel()
        if (ws != null) socketDetached.await()
        synchronized(lock) { socket = null; ticketCall = null }
    }
    fun dispose() {
        stop()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
