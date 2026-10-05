package com.agentdeck.hotel

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CloudClient(private val base: String, private val token: String,
                  private val event: (JSONObject) -> Unit, private val audio: (ByteArray) -> Unit) {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).pingInterval(25, TimeUnit.SECONDS).build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var stopped = false
    @Volatile private var sessionEpoch = 0
    init { require(base.startsWith("https://") && !base.endsWith("/")); require(token.isNotBlank()) }
    private fun post(path: String, body: JSONObject, callback: (JSONObject) -> Unit) {
        val req = Request.Builder().url(base + path).header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { if (!stopped) event(JSONObject().put("type", "error").put("message", "Cloud unavailable. Please retry or use the operator.")) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!it.isSuccessful) error("HTTP failure")
                        callback(JSONObject(it.body!!.string()))
                    } catch (_: Exception) { event(JSONObject().put("type", "error").put("message", "Device authentication or cloud request failed.")) }
                }
            }
        })
    }
    fun heartbeat(version: Int) {
        post("/api/device/heartbeat", JSONObject().put("app_version", "0.1.0").put("config_version", version).put("protocol", 1)) {
            event(JSONObject().put("type", "config").put("hotel", it.getJSONObject("hotel")).put("device_id", it.getString("device_id")))
        }
    }
    fun start() {
        stopped = false
        val epoch = ++sessionEpoch
        post("/api/device/session", JSONObject()) { ticket ->
            if (stopped || epoch != sessionEpoch) return@post
            val req = Request.Builder().url(base.replaceFirst("https://", "wss://") + "/ws/device")
                .header("X-Orange-Ticket", ticket.getString("ticket")).build()
            socket = client.newWebSocket(req, object : WebSocketListener() {
                override fun onMessage(ws: WebSocket, text: String) {
                    if (!stopped && epoch == sessionEpoch) try { event(JSONObject(text)) } catch (_: Exception) {}
                }
                override fun onMessage(ws: WebSocket, bytes: ByteString) { if (!stopped && epoch == sessionEpoch) audio(bytes.toByteArray()) }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { if (!stopped && epoch == sessionEpoch) event(JSONObject().put("type", "error").put("message", "Voice connection lost. Press green to retry.")) }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) { if (!stopped && epoch == sessionEpoch) event(JSONObject().put("type", "ended")) }
            })
        }
    }
    fun sendAudio(bytes: ByteArray): Boolean {
        val ws = socket ?: return false
        if (ws.queueSize() > 48000) return false
        return ws.send(bytes.toByteString())
    }
    fun send(value: JSONObject) = socket?.send(value.toString())
    fun stop() {
        stopped = true
        sessionEpoch++
        socket?.send(JSONObject().put("type", "stop").toString())
        socket?.close(1000, "Guest ended call"); socket = null
    }
    fun dispose() { stop(); client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
}
