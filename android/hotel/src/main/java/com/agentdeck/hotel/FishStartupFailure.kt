package com.agentdeck.hotel

import org.json.JSONException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Safe startup diagnostics: never retain a response body, URL, credential or exception message. */
class FishStartupFailure private constructor(
    val stage: Stage,
    val category: Category,
    val httpStatus: Int? = null
) : RuntimeException("Translation startup failed") {
    enum class Stage(val code: String) {
        CONFIGURATION("configuration"),
        ADMISSION("admission"),
        AGENT_LOOKUP("agent_lookup"),
        CAPTION_LOOKUP("caption_lookup"),
        CAPTION_READ("caption_read"),
        CAPTION_CREATE("caption_create"),
        AGENT_CREATE("agent_create"),
        AGENT_PUBLISH("agent_publish"),
        DIRECT_SESSION("session_create"),
        MANAGED_SESSION("managed_session"),
        TICKET("ticket"),
        TRANSPORT("transport")
    }

    enum class Category(val code: String) {
        AUTHENTICATION("authentication"), ACCESS("access"), BILLING("billing"),
        LIMIT("limit"), CONFIGURATION("configuration"), MISSING("missing"),
        CONFLICT("conflict"), UNAVAILABLE("unavailable"), HTTP("http"),
        DNS("dns"), TLS("tls"), TIMEOUT("timeout"), NETWORK("network"),
        RESPONSE("response"), UNCONFIGURED("unconfigured"), BUDGET("budget"), UNKNOWN("unknown")
    }

    data class Notice(val text: String, val reference: String)

    companion object {
        fun http(stage: Stage, status: Int): FishStartupFailure {
            val category = when (status) {
                401 -> Category.AUTHENTICATION
                403 -> Category.ACCESS
                402 -> Category.BILLING
                429 -> Category.LIMIT
                400, 422 -> Category.CONFIGURATION
                404 -> Category.MISSING
                409 -> Category.CONFLICT
                in 500..599 -> Category.UNAVAILABLE
                else -> Category.HTTP
            }
            return FishStartupFailure(stage, category, status.takeIf { it in 100..599 })
        }

        fun configuration(stage: Stage) = FishStartupFailure(stage, Category.CONFIGURATION)
        fun response(stage: Stage) = FishStartupFailure(stage, Category.RESPONSE)

        /** An allowlisted managed-service diagnostic may describe an upstream provider rejection.
         * Never interpret this header as proof that session creation did not occur.
         */
        fun managedHeader(value: String?): FishStartupFailure? {
            if (value == null || value.length !in 5..100) return null
            val parts = value.split(':')
            if (parts.size != 3) return null
            val stage = when (parts[0]) {
                "configuration" -> Stage.CONFIGURATION
                "admission" -> Stage.ADMISSION
                "tool_lookup" -> Stage.CAPTION_LOOKUP
                "tool_fetch" -> Stage.CAPTION_READ
                "tool_create" -> Stage.CAPTION_CREATE
                "agent_create" -> Stage.AGENT_CREATE
                "agent_publish" -> Stage.AGENT_PUBLISH
                "session_create" -> Stage.DIRECT_SESSION
                "session_ticket" -> Stage.TICKET
                else -> return null
            }
            if (parts[1] == "http") {
                if (!parts[2].matches(Regex("[45][0-9]{2}"))) return null
                return http(stage, parts[2].toInt())
            }
            if (parts[2] != "0") return null
            val category = when (parts[1]) {
                "timeout" -> Category.TIMEOUT
                "network" -> Category.NETWORK
                "response" -> Category.RESPONSE
                "contract" -> Category.CONFIGURATION
                "unconfigured" -> Category.UNCONFIGURED
                "budget" -> Category.BUDGET
                "conflict" -> Category.CONFLICT
                "unauthorized" -> Category.AUTHENTICATION
                else -> return null
            }
            return FishStartupFailure(stage, category)
        }

        /** Classify types only. The bounded cause walk also handles wrapped TLS/network failures. */
        fun describe(stage: Stage, error: Throwable, managed: Boolean): Notice {
            val causes = ArrayList<Throwable>()
            var cursor: Throwable? = error
            repeat(8) {
                val next = cursor ?: return@repeat
                if (causes.any { it === next }) return@repeat
                causes.add(next)
                cursor = next.cause
            }
            val failure = causes.filterIsInstance<FishStartupFailure>().firstOrNull()
                ?: FishStartupFailure(stage, when {
                    causes.any { it is SSLException } -> Category.TLS
                    causes.any { it is UnknownHostException } -> Category.DNS
                    causes.any { it is InterruptedIOException } -> Category.TIMEOUT
                    causes.any { it is IOException } -> Category.NETWORK
                    causes.any { it is JSONException } -> Category.RESPONSE
                    causes.any { it is IllegalArgumentException || it is IllegalStateException } -> Category.CONFIGURATION
                    else -> Category.UNKNOWN
                })
            val service = if (managed) "hotel translation service" else "translation service"
            val upstream = managed && failure.stage !in setOf(Stage.MANAGED_SESSION, Stage.ADMISSION, Stage.CONFIGURATION)
            val message = when (failure.category) {
                Category.AUTHENTICATION -> when {
                    upstream -> "The translation provider did not accept the hotel service credential. Ask staff to update the provider credential on the hotel service."
                    managed -> "The hotel service did not accept this device. Ask staff to check the hotel service connection in Settings."
                    else -> "The translation credential was not accepted. Ask staff to update it in Settings."
                }
                Category.ACCESS -> "Translation service access was denied. Ask staff to check service permissions."
                Category.BILLING -> "Translation account credit or billing needs attention. Ask staff to check the translation account."
                Category.LIMIT -> "Translation is busy or its usage limit has been reached. Wait a moment and try again, or ask staff to check service limits."
                Category.CONFIGURATION -> if (managed && failure.stage == Stage.MANAGED_SESSION)
                    "The hotel service rejected this app's translation request. Ask staff to check the hotel service version and configuration."
                else "Translation setup was rejected or is incompatible. Ask staff to check the app and service configuration."
                Category.MISSING -> when {
                    upstream -> "Required setup is missing at the translation provider. Ask staff to repair translation setup on the hotel service."
                    managed -> "Translation is unavailable at the configured hotel service. Ask staff to check the hotel service URL and version."
                    else -> "Required translation setup is missing. Ask staff to check for an app update or repair translation setup."
                }
                Category.CONFLICT -> "Translation setup conflicts with the current service state. Ask staff to check for an unfinished conversation."
                Category.UNAVAILABLE -> "The $service is temporarily unavailable. Try again shortly or ask staff to check service status."
                Category.DNS -> "The $service address could not be reached. Check internet access or ask staff to check the service address."
                Category.TLS -> "A secure connection to the $service could not be established. Ask staff to check the device date and service certificate."
                Category.TIMEOUT -> "The $service did not respond in time. Check internet access and try again shortly."
                Category.NETWORK -> "The connection to the $service was interrupted. Check internet access and try again shortly."
                Category.RESPONSE -> "The $service returned an incomplete response. Ask staff to check the service and app versions."
                Category.UNCONFIGURED -> "Translation is not configured on the hotel service. Ask staff to configure translation access on that service."
                Category.BUDGET -> "The hotel's translation allowance has been reached. Ask staff to check the hotel's translation limits."
                Category.HTTP, Category.UNKNOWN -> "Translation could not start. Ask staff to check service access using the reference below."
            }
            val reference = "TR-${failure.stage.code}-${failure.category.code}" +
                (failure.httpStatus?.let { "-HTTP$it" } ?: "")
            return Notice("$message Reference: $reference", reference)
        }
    }
}
