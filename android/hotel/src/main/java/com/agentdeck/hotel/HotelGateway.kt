package com.agentdeck.hotel

import okhttp3.Call
import okhttp3.Callback
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
    private val dial: (SessionOwner.Lease, JSONObject) -> Unit
) {
    private class Context(val lease: SessionOwner.Lease) {
        val id = UUID.randomUUID().toString()
        val calls = mutableSetOf<Call>()
        var ready = false
        var busy = false
        var closed = false
    }
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).build()
    private var current: Context? = null
    val configured get() = base.startsWith("https://") && token.isNotBlank()

    @Synchronized fun begin(lease: SessionOwner.Lease) {
        end()
        current = Context(lease)
    }

    @Synchronized fun action(lease: SessionOwner.Lease, action: String, arguments: JSONObject) {
        val context = current?.takeIf { it.lease == lease && !it.closed } ?: return
        if (!configured) {
            emit(context, JSONObject().put("type", "operation_result").put("status", "unavailable")
                .put("text", "Hotel services are not connected. Ask staff to configure the hotel's secure backend."))
            return
        }
        if (context.busy) {
            emit(context, JSONObject().put("type", "notice").put("text", "Please wait for the current hotel request."))
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
        synchronized(this) { if (context.closed) return; context.calls.add(call) }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = finish(call, JSONObject().put("status", "unavailable")
                .put("text", "The hotel service could not be reached. Retry or ask reception for help."))
            override fun onResponse(call: Call, response: Response) {
                val result = response.use {
                    if (!it.isSuccessful) JSONObject().put("status", "error")
                        .put("text", "Hotel request was not accepted (HTTP ${it.code}). Ask staff to check the connection and authorization.")
                    else try { JSONObject(it.body?.string().orEmpty()) } catch (_: Exception) {
                        JSONObject().put("status", "error").put("text", "The hotel service returned an unreadable receipt. No success is confirmed.")
                    }
                }
                finish(call, result)
            }
            private fun finish(call: Call, value: JSONObject) {
                synchronized(this@HotelGateway) {
                    context.calls.remove(call)
                    if (current !== context || context.closed) return
                    callback(value)
                }
            }
        })
    }

    @Synchronized fun end() {
        val context = current ?: return
        context.closed = true
        context.calls.forEach { it.cancel() }
        context.calls.clear()
        current = null
        if (configured && context.ready) {
            val request = Request.Builder().url(base.trimEnd('/') + "/api/device/operations/${context.id}/end")
                .header("Authorization", "Bearer $token").post("{}".toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = Unit
                override fun onResponse(call: Call, response: Response) { response.close() }
            })
        }
    }

    fun dispose() { end(); client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
}
