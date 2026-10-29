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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture

/** HTTP operations use their own server session; no tenant or provider secret crosses the UI. */
class HotelGateway(
    private val base: String,
    private val token: String,
    private val deliver: (SessionOwner.Lease, JSONObject) -> Unit,
    private val dial: (SessionOwner.Lease, JSONObject) -> Unit,
    private val translate: (SessionOwner.Lease, String, Map<String, String>) -> String,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).build(),
    private val connectionFailure: (String) -> Unit = {}
) {
    private class Context(val lease: SessionOwner.Lease) {
        var pendingAction: PendingAction? = null
        var confirmationId = ""
        var confirmationAction = ""
        var pendingConfirmation: Boolean? = null
        val id = UUID.randomUUID().toString()
        val calls = mutableSetOf<Call>()
        var startCall: Call? = null
        var startAttempted = false
        var ready = false
        var busy = false
        var closed = false
        var receiptId = ""
        var watchedItem = ""
        var watchRevision = 0L
        var statusCall: Call? = null
        var statusTimer: ScheduledFuture<*>? = null
        var publicStatus: JSONObject? = null
        var watchDenied = false
    }
    private class PendingAction(val action: String, val arguments: String, val id: String = UUID.randomUUID().toString())
    private var current: Context? = null
    private val pendingStarts = mutableSetOf<Call>()
    private var disposed = false
    private val statusSchedulerDelegate = lazy {
        Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "hotel-request-status").apply { isDaemon = true } }
    }
    private val statusScheduler by statusSchedulerDelegate
    val configured get() = base.startsWith("https://") && token.isNotBlank()
    private fun text(context: Context, message: String, values: Map<String, String> = emptyMap()) =
        translate(context.lease, message, values)

    @Synchronized fun begin(lease: SessionOwner.Lease) {
        if (disposed) return
        end()
        current = Context(lease)
    }

    @Synchronized fun canReconcile(lease: SessionOwner.Lease, action: String, arguments: JSONObject): Boolean {
        val context = current?.takeIf { it.lease == lease && !it.closed } ?: return false
        return context.pendingAction?.let { it.action == action && it.arguments == arguments.toString() } == true
    }

    @Synchronized fun action(lease: SessionOwner.Lease, action: String, arguments: JSONObject) {
        val context = current?.takeIf { it.lease == lease && !it.closed } ?: return
        if (!configured) {
            emit(context, JSONObject().put("type", "operation_result").put("status", "unavailable")
                .put("text", text(context, "Hotel services are not connected. Ask staff to configure the hotel's secure backend.")))
            return
        }
        if (context.busy || context.pendingConfirmation != null) {
            emit(context, JSONObject().put("type", "notice").put("text", text(context, "Please wait for the current hotel request.")))
            return
        }
        val pending = context.pendingAction
        if (pending != null && (pending.action != action || pending.arguments != arguments.toString())) {
            emit(context, JSONObject().put("type", "notice").put("text", text(context, "Please wait for the current hotel request.")))
            return
        }
        val submission = pending ?: PendingAction(action, arguments.toString()).also { context.pendingAction = it }
        context.busy = true
        fun execute() {
            post(context, "/api/device/operations/${context.id}/action", JSONObject()
                .put("action", submission.action).put("call_id", submission.id).put("arguments", JSONObject(submission.arguments))) { response ->
                synchronized(this) {
                    context.busy = false
                    if (!response.optBoolean("_uncertain") && response.optString("status") != "pending") context.pendingAction = null
                }
                response.remove("_uncertain")
                result(context, action, response)
            }
        }
        if (context.ready) execute()
        else post(context, "/api/device/operations/start", JSONObject().put("session_id", context.id)
            .put("mode", "reception").put("language", lease.request.guest).put("staff_language", lease.request.staff)) {
            if (it.optString("status") == "unavailable" || it.optString("status") == "error") {
                synchronized(this) {
                    context.busy = false
                    if (!it.optBoolean("_uncertain")) context.pendingAction = null
                }
                it.remove("_uncertain")
                result(context, action, it)
            } else {
                synchronized(this) { context.ready = true }
                execute()
            }
        }
    }

    @Synchronized fun confirmationAction(lease: SessionOwner.Lease, draftId: String): String? =
        current?.takeIf { it.lease == lease && !it.closed && it.confirmationId.isNotEmpty() && it.confirmationId == draftId }?.confirmationAction

    @Synchronized fun canReconcileConfirmation(lease: SessionOwner.Lease, draftId: String, accepted: Boolean): Boolean =
        current?.let { it.lease == lease && !it.closed && it.confirmationId == draftId && it.pendingConfirmation == accepted } == true

    @Synchronized fun confirm(lease: SessionOwner.Lease, draftId: String, accepted: Boolean) {
        val context = current?.takeIf { it.lease == lease && !it.closed } ?: return
        if (!draftId.startsWith("operation:") || draftId != context.confirmationId || context.busy) return
        context.pendingConfirmation = accepted
        context.busy = true
        post(context, "/api/device/operations/${context.id}/confirm", JSONObject()
            .put("confirmation_id", draftId.removePrefix("operation:")).put("accepted", accepted)) {
            synchronized(this) {
                context.busy = false
                if (!it.optBoolean("_uncertain") && it.optString("status") != "pending") context.pendingConfirmation = null
            }
            result(context, "", it)
        }
    }

    @Synchronized private fun result(context: Context, action: String, response: JSONObject) {
        if (current !== context || context.closed) return
        response.remove("_uncertain")
        response.put("reconciliation_pending", context.pendingAction != null || context.pendingConfirmation != null)
        val confirmation = response.optString("confirmation_id")
        val fields = response.optJSONArray("fields")
        var receiptId = ""
        if (confirmation.isBlank() && fields == null && response.optString("status") !in
            setOf("pending", "error", "unavailable", "cancelled")) {
            val result = response.optJSONObject("result")
            val id = sequenceOf("item_id", "request_id", "escalation_id")
                .map { result?.optString(it).orEmpty() }.firstOrNull { it.isNotBlank() }.orEmpty()
            if (id.matches(Regex("[A-Za-z0-9_-]{1,200}"))) receiptId = id
        }
        if (receiptId != context.receiptId) {
            stopWatch(context)
            context.receiptId = receiptId
            context.publicStatus = null
        }
        when {
            confirmation.isNotBlank() -> {
                context.confirmationId = "operation:$confirmation"
                context.confirmationAction = action
                context.pendingConfirmation = null
                emit(context, JSONObject().put("type", "reception_event")
                    .put("phase", "confirming").put("draft_id", context.confirmationId)
                    .put("message", response.optString("summary", response.optString("text"))))
            }
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
                .put("_uncertain", true).put("text", text(context, "The hotel service could not be reached. Retry or ask reception for help.")), "offline")
            override fun onResponse(call: Call, response: Response) {
                var failedState: String? = null
                val result = response.use {
                    if (!it.isSuccessful) {
                        failedState = when {
                            it.code == 401 || it.code == 403 -> "unauthorized"
                            it.code >= 500 || it.code == 408 || it.code == 426 -> "offline"
                            else -> null
                        }
                        JSONObject().put("status", "error").put("_uncertain", it.code >= 500 || it.code == 408)
                            .put("text", text(context, "Hotel request was not accepted (HTTP {status}). Ask staff to check the connection and authorization.", mapOf("status" to it.code.toString())))
                    } else try { JSONObject(it.body?.string().orEmpty()) } catch (_: Exception) {
                        failedState = "offline"
                        JSONObject().put("status", "error").put("_uncertain", true).put("text", text(context, "The hotel service returned an unreadable receipt. No success is confirmed."))
                    }
                }
                finish(call, result, failedState)
            }
            private fun finish(call: Call, value: JSONObject, failedState: String? = null) {
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
                    if (failedState == "unauthorized") { context.watchDenied = true; stopWatch(context) }
                    failedState?.let(connectionFailure)
                    callback(value)
                }
            }
        })
    }

    /** Only the most recently delivered durable receipt is eligible; this never starts a session. */
    @Synchronized fun watch(lease: SessionOwner.Lease, itemId: String) {
        val context = current?.takeIf { it.lease == lease && !it.closed && it.ready && !it.watchDenied &&
            it.lease.request.mode == "reception" } ?: return
        if (itemId.isEmpty() || itemId != context.receiptId || context.watchedItem == itemId ||
            context.publicStatus?.optString("status") == "resolved") return
        stopWatch(context)
        context.watchedItem = itemId
        readStatus(context, context.watchRevision, itemId)
    }

    @Synchronized fun unwatch(lease: SessionOwner.Lease) {
        current?.takeIf { it.lease == lease }?.let { stopWatch(it) }
    }

    private fun stopWatch(context: Context) {
        context.watchRevision++
        context.watchedItem = ""
        context.statusTimer?.cancel(false)
        context.statusTimer = null
        context.statusCall?.cancel()
        context.statusCall = null
    }

    private fun watching(context: Context, revision: Long, item: String) =
        current === context && !context.closed && !disposed &&
            context.watchRevision == revision && context.watchedItem == item

    private fun readStatus(context: Context, revision: Long, item: String) {
        if (!watching(context, revision, item) || context.statusCall != null) return
        context.statusTimer = null
        val request = Request.Builder().url(base.trimEnd('/') + "/api/device/operations/${context.id}/items/$item")
            .header("Authorization", "Bearer $token").get().build()
        val call = client.newCall(request)
        context.statusCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = finish(null, false)
            override fun onResponse(call: Call, response: Response) {
                var denied = false
                val public = response.use {
                    denied = it.code == 401 || it.code == 403 || it.code == 404
                    if (!it.isSuccessful) null else try {
                        val value = JSONObject(it.body?.string().orEmpty())
                        val status = value.getString("status")
                        val version = value.getInt("version")
                        require(value.getString("item_id") == item && status in setOf("open", "acknowledged", "resolved") && version >= 0)
                        // Explicit construction: private endpoint additions can never cross this boundary.
                        JSONObject().put("item_id", item).put("status", status).put("version", version)
                            .put("created", value.getDouble("created"))
                            .put("acknowledged_at", if (value.isNull("acknowledged_at")) JSONObject.NULL else value.getDouble("acknowledged_at"))
                            .put("resolved_at", if (value.isNull("resolved_at")) JSONObject.NULL else value.getDouble("resolved_at"))
                    } catch (_: Exception) { null }
                }
                finish(public, denied)
            }
            private fun finish(value: JSONObject?, denied: Boolean) {
                synchronized(this@HotelGateway) {
                    if (!watching(context, revision, item) || context.statusCall !== call) return
                    context.statusCall = null
                    if (denied) context.watchDenied = true
                    val previous = context.publicStatus
                    if (value != null && (previous == null || value.getInt("version") >= previous.getInt("version"))) {
                        context.publicStatus = value
                    }
                    val event = context.publicStatus?.let { JSONObject(it.toString()) } ?: JSONObject().put("item_id", item)
                    event.put("type", "request_status").put("status_available", value != null)
                    if (value == null) event.put("text", text(context, "Request status is unavailable. Showing the last known state."))
                    emit(context, event)
                    if (denied || context.publicStatus?.optString("status") == "resolved") {
                        stopWatch(context)
                    } else if (watching(context, revision, item)) {
                        context.statusTimer = statusScheduler.schedule({
                            synchronized(this@HotelGateway) { readStatus(context, revision, item) }
                        }, 5, TimeUnit.SECONDS)
                    }
                }
            }
        })
    }

    @Synchronized fun end() {
        val context = current ?: return
        stopWatch(context)
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
        if (statusSchedulerDelegate.isInitialized()) statusScheduler.shutdownNow()
        client.connectionPool.evictAll()
        if (pendingStarts.isEmpty()) client.dispatcher.executorService.shutdown()
    }
}
