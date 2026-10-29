package com.agentdeck.hotel

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** HTTP operations use their own server session; no tenant or provider secret crosses the UI. */
class HotelGateway(
    private val base: String,
    private val token: String,
    private val deliver: (SessionOwner.Lease, JSONObject) -> Unit,
    private val dial: (SessionOwner.Lease, JSONObject) -> Unit,
    private val translate: (SessionOwner.Lease, String, Map<String, String>) -> String,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).build()
) {
    private class Context(val lease: SessionOwner.Lease) {
        val id = UUID.randomUUID().toString()
        val calls = mutableSetOf<Call>()
        var startCall: Call? = null
        var startAttempted = false
        var ready = false
        var busy = false
        var closed = false
    }
    private var current: Context? = null
    private val pendingStarts = mutableSetOf<Call>()
    private var disposed = false
    val configured get() = base.startsWith("https://") && token.isNotBlank()
    private fun text(context: Context, message: String, values: Map<String, String> = emptyMap()) =
        translate(context.lease, message, values)

    @Synchronized fun begin(lease: SessionOwner.Lease) {
        if (disposed) return
        end()
        current = Context(lease)
    }

    @Synchronized fun action(lease: SessionOwner.Lease, action: String, arguments: JSONObject) {
        val context = current?.takeIf { it.lease == lease && !it.closed } ?: return
        if (!configured) {
            emit(context, JSONObject().put("type", "operation_result").put("status", "unavailable")
                .put("text", text(context, "Hotel services are not connected. Ask staff to configure the hotel's secure backend.")))
            return
        }
        if (context.busy) {
            emit(context, JSONObject().put("type", "notice").put("text", text(context, "Please wait for the current hotel request.")))
            return
        }
        context.busy = true
        fun execute() {
            post(context, "/api/device/operations/${context.id}/action", JSONObject()
                .put("action", action).put("call_id", UUID.randomUUID().toString()).put("arguments", arguments)) { response ->
                synchronized(this) { context.busy = false }
                result(context, action, response)
            }
        }
        if (context.ready) execute()
        else post(context, "/api/device/operations/start", JSONObject().put("session_id", context.id)
            .put("mode", "reception").put("language", lease.request.guest).put("staff_language", lease.request.staff)) {
            if (it.optString("status") == "unavailable" || it.optString("status") == "error") {
                synchronized(this) { context.busy = false }
                result(context, action, it)
            } else {
                synchronized(this) { context.ready = true }
                execute()
            }
        }
    }

    @Synchronized fun confirm(lease: SessionOwner.Lease, draftId: String, accepted: Boolean) {
        val context = current?.takeIf { it.lease == lease && !it.closed } ?: return
        if (!draftId.startsWith("operation:") || context.busy) return
        context.busy = true
        post(context, "/api/device/operations/${context.id}/confirm", JSONObject()
            .put("confirmation_id", draftId.removePrefix("operation:")).put("accepted", accepted)) {
            synchronized(this) { context.busy = false }
            result(context, "", it)
        }
    }

    private fun result(context: Context, action: String, response: JSONObject) {
        val confirmation = response.optString("confirmation_id")
        val fields = response.optJSONArray("fields")
        when {
            confirmation.isNotBlank() -> emit(context, JSONObject().put("type", "reception_event")
                .put("phase", "confirming").put("draft_id", "operation:$confirmation")
                .put("message", response.optString("summary", response.optString("text"))))
            fields != null -> emit(context, JSONObject().put("type", "operation_form")
                .put("action", action).put("fields", fields).put("text", response.optString("text")))
            response.optString("status") == "dialer_required" -> dial(context.lease, response)
            else -> emit(context, JSONObject(response.toString()).put("type", "operation_result"))
        }
    }

    @Synchronized private fun emit(context: Context, message: JSONObject) {
        if (current === context && !context.closed) deliver(context.lease, message)
    }

    private fun post(context: Context, path: String, body: JSONObject, callback: (JSONObject) -> Unit) {
        val request = Request.Builder().url(base.trimEnd('/') + path).header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val call = client.newCall(request)
        synchronized(this) {
            if (context.closed || disposed) return
            context.calls.add(call)
            if (path == "/api/device/operations/start") {
                context.startAttempted = true
                context.startCall = call
                pendingStarts.add(call)
            }
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = finish(call, JSONObject().put("status", "unavailable")
                .put("text", text(context, "The hotel service could not be reached. Retry or ask reception for help.")))
            override fun onResponse(call: Call, response: Response) {
                val result = response.use {
                    if (!it.isSuccessful) JSONObject().put("status", "error")
                        .put("text", text(context, "Hotel request was not accepted (HTTP {status}). Ask staff to check the connection and authorization.", mapOf("status" to it.code.toString())))
                    else try { JSONObject(it.body?.string().orEmpty()) } catch (_: Exception) {
                        JSONObject().put("status", "error").put("text", text(context, "The hotel service returned an unreadable receipt. No success is confirmed."))
                    }
                }
                finish(call, result)
            }
            private fun finish(call: Call, value: JSONObject) {
                synchronized(this@HotelGateway) {
                    context.calls.remove(call)
                    val wasStart = context.startCall === call
                    if (wasStart) {
                        context.startCall = null
                        pendingStarts.remove(call)
                    }
                    if (wasStart && context.closed) endRemote(context.id)
                    if (disposed && pendingStarts.isEmpty()) client.dispatcher.executorService.shutdown()
                    if (current !== context || context.closed) return
                    callback(value)
                }
            }
        })
    }

    @Synchronized fun end() {
        val context = current ?: return
        context.closed = true
        // A start may already have committed remotely even if its response has
        // not reached this device. Let it settle, then close this exact ID.
        context.calls.filter { it !== context.startCall }.forEach { it.cancel() }
        context.calls.retainAll(listOfNotNull(context.startCall).toSet())
        current = null
        if (configured && context.startAttempted && context.startCall == null) endRemote(context.id)
    }

    /** Best-effort closure has its own dispatcher so Activity disposal cannot cancel it.
     * A network-uncertain start or process death still needs server-side reconciliation.
     */
    private fun endRemote(id: String) {
        val request = Request.Builder().url(base.trimEnd('/') + "/api/device/operations/$id/end")
            .header("Authorization", "Bearer $token").post("{}".toRequestBody("application/json".toMediaType())).build()
        val cleanup = client.newBuilder().dispatcher(Dispatcher()).callTimeout(30, TimeUnit.SECONDS).build()
        cleanup.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { cleanup.dispatcher.executorService.shutdown() }
            override fun onResponse(call: Call, response: Response) {
                response.close()
                cleanup.dispatcher.executorService.shutdown()
            }
        })
    }

    @Synchronized fun dispose() {
        end()
        disposed = true
        client.connectionPool.evictAll()
        if (pendingStarts.isEmpty()) client.dispatcher.executorService.shutdown()
    }
}
