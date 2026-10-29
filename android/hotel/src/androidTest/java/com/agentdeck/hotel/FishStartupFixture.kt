package com.agentdeck.hotel

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList

/** Actual FishVoice provisioning and teardown, with every HTTP call intercepted before networking.
 * This fixture never joins LiveKit, requests microphone permission or uses a provider credential.
 */
internal object FishStartupFixture {
    fun verifyHotelPreparation(instrumentation: Instrumentation) {
        check(BuildConfig.DISTRIBUTION == "hotel" && BuildConfig.FISH_API_KEY.isBlank())
        Harness(instrumentation.targetContext, preparing = true).use { h ->
            h.prepare("fr", "de")
            check(h.wire.requests.isEmpty()) { "Hotel preparation contacted a personal provider" }
            check(h.events.isEmpty())
            h.noAudio()
        }
    }

    fun verify(instrumentation: Instrumentation) {
        check(BuildConfig.FISH_API_KEY.isBlank()) { "Use a key-free verification APK" }
        Harness(instrumentation.targetContext, preparing = true).use { h ->
            h.prepare("fr", "de")
            check(h.wire.agentCreates == 1 && h.wire.publishes == 1)
            check(h.wire.sessionCalls == 0 && h.events.isEmpty()) { "preparation opened a conversation" }
            h.noAudio()
            val beforeStart = h.wire.requests.size
            h.activate()
            h.direct(FishPlan.DEFAULT_VOICE)
            check(h.wire.requests.drop(beforeStart) == listOf("POST /v1/agent/sessions")) {
                "prepared Start repeated configuration requests"
            }
        }
        Harness(instrumentation.targetContext, preparing = true).use { h ->
            h.wire.existingTools(incompatible = true, mode = "blocking")
            h.prepare("fr", "de")
            check("GET /v1/agent/tools/incompatible-tool" in h.wire.requests)
            check("GET /v1/agent/tools/fixture-tool" in h.wire.requests)
            check("POST /v1/agent/tools" !in h.wire.requests) { "preparation duplicated a compatible client tool" }
            check(h.wire.sessionCalls == 0 && h.events.isEmpty())
            h.noAudio()
            val beforeStart = h.wire.requests.size
            h.activate()
            h.direct(FishPlan.DEFAULT_VOICE)
            check(h.wire.requests.drop(beforeStart) == listOf("POST /v1/agent/sessions"))
        }
        Harness(instrumentation.targetContext, preparing = true).use { h ->
            h.prepare("fr", "de")
            h.prepare("en", "es")
            val beforeReturn = h.wire.requests.size
            h.prepare("en", "de")
            check(h.wire.requests.size == beforeReturn) { "returning to a configured language repeated setup" }
            check(h.wire.sessionCalls == 0 && h.events.isEmpty())
            h.noAudio()
        }
        Harness(instrumentation.targetContext, preparing = true).use { h ->
            h.cancelPreparation()
            check(h.wire.requests.isEmpty() && h.events.isEmpty()) { "cancelled preparation reached the provider" }
        }
        Harness(instrumentation.targetContext).use { h ->
            h.direct()
            check(h.wire.requests == listOf("GET /v1/agent/agents", "GET /v1/agent/tools",
                "POST /v1/agent/tools", "POST /v1/agent/agents", "POST /v1/agent/agents/fixture-agent-1/publish",
                "POST /v1/agent/sessions")) { "incomplete direct provisioning chain" }
            check(h.wire.sessionCalls == 1)
            check(h.field("endRequest") != null)
            h.noAudio()
        }
        for (mode in listOf(null, "blocking", "fire_and_forget")) Harness(instrumentation.targetContext).use { h ->
            h.wire.existingTools(incompatible = true, mode = mode)
            h.direct()
            check("GET /v1/agent/tools/incompatible-tool" in h.wire.requests)
            check("GET /v1/agent/tools/fixture-tool" in h.wire.requests)
            check("POST /v1/agent/tools" !in h.wire.requests) { "compatible client tool was duplicated" }
            check(h.wire.agentCreates == 1 && h.wire.sessionCalls == 1)
            h.noAudio()
        }
        Harness(instrumentation.targetContext).use { h ->
            h.wire.existingTools(incompatible = true, compatible = false)
            val error = h.failed { h.direct() }
            check(error is FishStartupFailure && error.stage == FishStartupFailure.Stage.CAPTION_READ)
            h.terminal(error, false)
            check("POST /v1/agent/tools" !in h.wire.requests)
            check(h.wire.agentCreates == 0 && h.wire.sessionCalls == 0)
            check(h.events.last().getString("text").contains("TR-caption_read-configuration"))
        }
        for (compatible in listOf(false, true)) for (hasMore in listOf(null, true, "false")) {
            Harness(instrumentation.targetContext).use { h ->
                h.wire.existingTools(compatible = compatible)
                h.wire.toolListing!!.put("has_more", hasMore)
                val error = h.failed { h.direct() }
                check(error is FishStartupFailure && error.stage == FishStartupFailure.Stage.CAPTION_LOOKUP)
                h.terminal(error, false)
                check("POST /v1/agent/tools" !in h.wire.requests)
                check(h.wire.agentCreates == 0 && h.wire.sessionCalls == 0)
            }
        }
        Harness(instrumentation.targetContext).use { h ->
            h.wire.toolLookupTimeout = true
            h.terminal(h.failed { h.direct() }, false)
            check("POST /v1/agent/tools" !in h.wire.requests)
            check(h.wire.agentCreates == 0 && h.wire.sessionCalls == 0)
        }
        val rejections = listOf(
            Triple("GET /v1/agent/agents", 401, FishStartupFailure.Stage.AGENT_LOOKUP),
            Triple("POST /v1/agent/tools", 402, FishStartupFailure.Stage.CAPTION_CREATE),
            Triple("POST /v1/agent/agents", 403, FishStartupFailure.Stage.AGENT_CREATE),
            Triple("POST /v1/agent/agents/fixture-agent-1/publish", 422, FishStartupFailure.Stage.AGENT_PUBLISH),
            Triple("POST /v1/agent/sessions", 429, FishStartupFailure.Stage.DIRECT_SESSION)
        )
        for ((request, status, stage) in rejections) Harness(instrumentation.targetContext).use { h ->
            h.wire.rejectRequest = request
            h.wire.rejectStatus = status
            val error = h.failed { h.direct() }
            check(error is FishStartupFailure && error.stage == stage && error.httpStatus == status)
            h.terminal(error, managed = false)
            check(h.events.last().getString("text").contains("HTTP$status"))
            check(h.gate.reason() == null) { "definitive refusal retained an unsettled reservation" }
        }
        for (status in listOf(404, 409)) Harness(instrumentation.targetContext).use { h ->
            h.cacheAgent()
            h.wire.sessionStatuses.add(status)
            h.direct()
            check(h.wire.sessionCalls == 2) { "stale agent repair did not retry exactly once" }
            check(h.wire.publishes == 2)
            check(h.wire.agentCreates == 2)
            check(h.wire.requests.count { it == "POST /v1/agent/agents/fixture-agent-1/publish" } == 1) {
                "repair republished the rejected agent"
            }
            h.noAudio()
        }
        Harness(instrumentation.targetContext).use { h ->
            h.cacheAgent()
            h.wire.sessionStatuses.addAll(listOf(404, 404, 200))
            val error = h.failed { h.direct() }
            check(h.wire.sessionCalls == 2) { "stale agent repair retried more than once" }
            h.terminal(error, false)
            check(h.gate.reason() == null)
        }
        for (timeout in listOf(false, true)) Harness(instrumentation.targetContext).use { h ->
            h.cacheAgent()
            if (timeout) h.wire.sessionTimeout = true else h.wire.sessionStatuses.add(500)
            val error = h.failed { h.direct() }
            check(h.wire.sessionCalls == 1) { "uncertain creation was retried" }
            h.terminal(error, false)
            check(h.gate.reason() != null && !h.gate.acquire("competing")) { "uncertain creation admitted a competing session" }
            check(h.wire.sessionCalls == 1)
        }
        Harness(instrumentation.targetContext).use { h ->
            h.wire.malformedLookup = true
            val error = h.failed { h.direct() }
            h.terminal(error, false)
            check(h.wire.agentCreates == 0 && h.wire.sessionCalls == 0)
            check(h.events.last().getString("text").contains("TR-agent_lookup-response"))
        }
        Harness(instrumentation.targetContext).use { h ->
            h.wire.malformedTicket = true
            val error = h.failed { h.direct() }
            check(h.field("endRequest") != null) { "malformed ticket lost provider cleanup ownership" }
            h.terminal(error, false)
            check(h.wire.ends == 1 && h.gate.reason() == null)
            check(h.events.last().getString("text").contains("TR-ticket-response"))
        }
        Harness(instrumentation.targetContext).use { h ->
            h.wire.managedStatus = 503
            h.wire.managedFailure = "tool_create:http:402"
            val error = h.failed { h.managed() }
            h.terminal(error, true)
            check(h.events.last().getString("text").contains("TR-caption_create-billing-HTTP402"))
            check(h.gate.reason() == null) { "managed proof was ignored" }
            check(h.wire.sessionCalls == 0 && h.wire.managedCalls == 1)
        }
        Harness(instrumentation.targetContext).use { h ->
            h.wire.managedStatus = 422
            val error = h.failed { h.managed() }
            h.terminal(error, true)
            check(h.gate.reason() == null && h.wire.managedCalls == 1)
        }
    }

    private class Harness(base: Context, preparing: Boolean = false) : Closeable {
        private val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("fish-startup-fixture-$name", mode)
        }
        private val meta = context.getSharedPreferences("fishmeta", Context.MODE_PRIVATE)
        private val shared = FishVoice::class.java.getDeclaredField("sharedAdmission").apply { isAccessible = true }
        private val previous = shared.get(null)
        val events = CopyOnWriteArrayList<JSONObject>()
        val wire = Wire(context)
        val gate = FishSessionAdmission(null) { id ->
            meta.edit().putString("outstanding_session", id).remove("pending_end_url")
                .remove("pending_end_credential_hash").remove("pending_recovery_url")
                .remove("pending_recovery_lookup").commit()
        }
        private val fish: FishVoice
        private val runClass = FishVoice::class.java.declaredClasses.single { it.simpleName == "Run" }
        private val owner = VoiceSession("fixture-startup", 61, "fr", "de", "translation", events::add)
        private val run: Any

        init {
            check(meta.edit().clear().commit())
            shared.set(null, gate)
            fish = FishVoice(context, events::add) { "fixture-only-not-a-provider-credential" }
            val client = OkHttpClient.Builder().addInterceptor(wire).build()
            for (name in listOf("http", "sessionHttp")) {
                FishVoice::class.java.getDeclaredField(name).apply { isAccessible = true }.set(fish, client)
            }
            run = runClass.declaredConstructors.single().apply { isAccessible = true }
                .newInstance(fish, owner, "Synthetic greeting")
            if (!preparing) activate()
        }

        fun activate() {
            FishVoice::class.java.getDeclaredField("current").apply { isAccessible = true }.set(fish, run)
            check(gate.acquire(field("reservation") as String))
        }
        fun prepare(guest: String, staff: String) {
            fish.prepare(guest, staff)
            val job = FishVoice::class.java.getDeclaredField("preparation").apply { isAccessible = true }.get(fish) as? Job
            if (job != null) {
                fish.prepare(guest, staff) // The same in-flight configuration must be shared.
                check(FishVoice::class.java.getDeclaredField("preparation").apply { isAccessible = true }.get(fish) === job)
                runBlocking { withTimeout(5000) { job.join() } }
            }
        }
        fun cancelPreparation() {
            fish.prepare("fr", "de")
            val job = FishVoice::class.java.getDeclaredField("preparation").apply { isAccessible = true }.get(fish) as Job
            fish.cancelPreparation()
            runBlocking { withTimeout(5000) { job.join() } }
        }

        fun field(name: String): Any? = runClass.getDeclaredField(name).apply { isAccessible = true }.get(run)
        private fun invoke(name: String, vararg args: Any): Any? {
            val method = FishVoice::class.java.declaredMethods.single { it.name == name && it.parameterTypes.size == args.size }
                .apply { isAccessible = true }
            return try { method.invoke(fish, *args) }
            catch (error: InvocationTargetException) { throw error.targetException }
        }
        fun direct(voice: String = "fixture-voice") = invoke("directSession", run, "fixture-only-not-a-provider-credential", voice, "fr", "de")
        fun managed() = invoke("managedSession", run, "https://fixture.hotel.invalid", "fixture-device-credential", "fr", "de")
        fun cacheAgent() { invoke("ensureAgent", run, "fixture-only-not-a-provider-credential", "fixture-voice", "") }
        fun failed(action: () -> Any?): Throwable {
            try { action() } catch (error: Throwable) { return error }
            error("fixture expected startup to fail")
        }
        fun terminal(error: Throwable, managed: Boolean) {
            invoke("startupFailed", run, field("startupStage") as FishStartupFailure.Stage, error, managed)
            waitForEnd()
            noAudio()
            check(!owner.active())
            check(events.size == 2 && events[0].optString("type") == "audio_state" &&
                events[0].optString("state") == "off" && events[1].optString("phase") == "error") {
                "failed startup did not acknowledge audio release and its terminal error once"
            }
            check(events.all { it.optString("session_id") == "fixture-startup" && it.optInt("generation") == 61 })
            val text = events.last().getString("text")
            check(!text.contains("fixture-only") && !text.contains("fixture-response-secret") && !text.contains("fixture.hotel.invalid"))
        }
        fun noAudio() {
            check(field("room") == null && field("recording") == false)
            check(events.none { it.optString("type") == "audio_state" && it.optString("state") == "recording" })
        }
        private fun waitForEnd() {
            (field("endJob") as? Job)?.let { job -> runBlocking { withTimeout(5000) { job.join() } } }
        }
        override fun close() {
            try {
                fish.release()
                waitForEnd()
                noAudio()
            } finally {
                shared.set(null, previous)
                check(meta.edit().clear().commit())
            }
        }
    }

    private class Wire(context: Context) : Interceptor {
        val requests = CopyOnWriteArrayList<String>()
        val sessionStatuses = java.util.ArrayDeque<Int>()
        var sessionCalls = 0
        var managedCalls = 0
        var agentCreates = 0
        var publishes = 0
        var ends = 0
        var rejectRequest: String? = null
        var rejectStatus = 0
        var sessionTimeout = false
        var malformedLookup = false
        var malformedTicket = false
        var managedStatus = 200
        var managedFailure: String? = null
        var toolLookupTimeout = false
        var toolListing: JSONObject? = null
        private val toolReadbacks = HashMap<String, JSONObject>()
        private var agentName = ""
        private var toolCreated = false
        private val definition = context.assets.open("fish-caption-tool.json").bufferedReader().use { JSONObject(it.readText()) }

        fun existingTools(incompatible: Boolean = false, compatible: Boolean = true, mode: String? = null) {
            val tools = JSONArray()
            if (incompatible) {
                tools.put(JSONObject().put("tool_id", "incompatible-tool").put("name", FishPlan.CAPTION_TOOL))
                val changed = JSONObject(definition.toString())
                changed.getJSONArray("arguments").getJSONObject(0).put("description", "Changed caption policy")
                toolReadbacks["incompatible-tool"] = changed
            }
            if (compatible) {
                tools.put(JSONObject().put("tool_id", "fixture-tool").put("name", FishPlan.CAPTION_TOOL))
                val readback = JSONObject(definition.toString()).put("tool_id", "fixture-tool")
                    .put("execution_mode", mode).put("timeout", 30)
                readback.getJSONArray("arguments").getJSONObject(0).put("required", false)
                toolReadbacks["fixture-tool"] = readback
            }
            toolListing = JSONObject().put("has_more", false).put("tools", tools)
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val path = request.url.encodedPath
            val label = "${request.method} $path"
            requests.add(label)
            check(request.url.host in setOf("api.fish.audio", "fixture.hotel.invalid")) { "fixture attempted an unexpected destination" }
            if (request.url.host == "fixture.hotel.invalid") {
                check(label == "POST /api/device/fish-session")
                managedCalls++
                check(body(request).getString("caption_protocol") == FishPlan.CAPTION_PROTOCOL)
                return reply(request, managedStatus, JSONObject().put("detail", "fixture-response-secret"),
                    mapOf("X-Manzanilla-Session-Creation" to "not-created").let { headers ->
                        managedFailure?.let { headers + ("X-Manzanilla-Voice-Failure" to it) } ?: headers
                    })
            }
            if (label == rejectRequest) {
                if (path == "/v1/agent/sessions") sessionCalls++
                return reply(request, rejectStatus, JSONObject().put("detail", "fixture-response-secret"))
            }
            return when {
                label == "GET /v1/agent/agents" -> if (malformedLookup) reply(request, 200, JSONObject()) else reply(request, 200, JSONObject().put("agents", JSONArray().apply {
                    if (agentCreates > 0) put(JSONObject().put("agent_id", "fixture-agent-$agentCreates")
                        .put("name", agentName).put("status", "published"))
                }))
                label == "GET /v1/agent/tools" -> {
                    if (toolLookupTimeout) throw SocketTimeoutException("fixture-response-secret")
                    reply(request, 200, toolListing ?: JSONObject().put("has_more", false)
                        .put("tools", JSONArray().apply { if (toolCreated) put(JSONObject().put("tool_id", "fixture-tool").put("name", FishPlan.CAPTION_TOOL)) }))
                }
                label == "GET /v1/agent/tools/incompatible-tool" -> reply(request, 200, toolReadbacks.getValue("incompatible-tool"))
                label == "GET /v1/agent/tools/fixture-tool" -> reply(request, 200, toolReadbacks["fixture-tool"] ?: definition)
                label == "POST /v1/agent/tools" -> {
                    check(FishPlan.captionToolMatches(definition, body(request)))
                    toolCreated = true
                    reply(request, 201, JSONObject().put("tool_id", "fixture-tool"))
                }
                label == "POST /v1/agent/agents" -> {
                    val data = body(request)
                    agentName = data.getString("name")
                    check(data.getJSONObject("config").getJSONObject("tools").getJSONArray("tool_ids").getString(0) == "fixture-tool")
                    agentCreates++
                    reply(request, 201, JSONObject().put("agent_id", "fixture-agent-$agentCreates"))
                }
                path.matches(Regex("/v1/agent/agents/fixture-agent-[0-9]+/publish")) -> {
                    check(request.method == "POST")
                    publishes++
                    reply(request, 200, JSONObject())
                }
                label == "POST /v1/agent/sessions" -> {
                    sessionCalls++
                    if (sessionTimeout) throw SocketTimeoutException("fixture-response-secret")
                    val status = if (sessionStatuses.isEmpty()) 200 else sessionStatuses.removeFirst()
                    if (status != 200) reply(request, status, JSONObject().put("detail", "fixture-response-secret"))
                    else reply(request, 200, JSONObject().put("session_id", "fixture-session-$sessionCalls")
                        .put("transport", "livekit").put("livekit_url", "wss://fixture.invalid")
                        .apply { if (!malformedTicket) put("token", "fixture-transport-token") })
                }
                path.matches(Regex("/v1/agent/sessions/fixture-session-[0-9]+/end")) -> {
                    check(request.method == "POST")
                    ends++
                    reply(request, 204, JSONObject())
                }
                label == "GET /v1/agent/sessions" -> reply(request, 200, JSONObject().put("has_more", false).put("sessions", JSONArray()))
                else -> error("fixture received an unexpected request")
            }
        }
        private fun body(request: Request): JSONObject {
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            return JSONObject(buffer.readUtf8())
        }
        private fun reply(request: Request, status: Int, json: JSONObject, headers: Map<String, String> = emptyMap()): Response =
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                .body((if (status == 204) "" else json.toString()).toResponseBody("application/json".toMediaType()))
                .apply { headers.forEach { (name, value) -> header(name, value) } }.build()
    }
}
