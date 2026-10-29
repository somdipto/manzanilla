package com.agentdeck.hotel

import org.json.JSONException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class FishStartupFailureTest {
    private val stage = FishStartupFailure.Stage.CAPTION_CREATE
    private val english: (String, Map<String, String>) -> String = { message, values ->
        values.entries.fold(message) { result, entry -> result.replace("{${entry.key}}", entry.value) }
    }

    @Test fun serviceRejectionsKeepTheFailedStageAndActionableStatus() {
        val cases = mapOf(401 to "authentication", 403 to "access", 402 to "billing", 429 to "limit",
            400 to "configuration", 404 to "missing", 409 to "conflict", 422 to "configuration",
            500 to "unavailable", 503 to "unavailable")
        for ((status, category) in cases) {
            val failure = FishStartupFailure.http(stage, status)
            // An outer caller's ticket stage must not overwrite the actual failed request stage.
            val notice = FishStartupFailure.describe(FishStartupFailure.Stage.TICKET, failure, false, english)
            assertEquals("TR-caption_create-$category-HTTP$status", notice.reference)
            assertTrue(notice.text.contains(notice.reference))
        }
    }

    @Test fun directAndManagedCredentialsHaveDifferentRepairInstructions() {
        val failure = FishStartupFailure.http(FishStartupFailure.Stage.MANAGED_SESSION, 401)
        val direct = FishStartupFailure.describe(stage, failure, false, english).text
        val managed = FishStartupFailure.describe(stage, failure, true, english).text
        assertTrue(direct.contains("translation credential"))
        assertTrue(managed.contains("hotel service connection"))
        assertFalse(managed.contains("update it"))
        assertFalse(direct.contains("internet"))
        assertFalse(managed.contains("internet"))
    }

    @Test fun wrappedNetworkFailuresAreClassifiedWithoutCopyingSensitiveMessages() {
        val sensitive = "Bearer fixture-secret https://provider.invalid/path?token=fixture-token body=guest-speech"
        val cases = listOf(
            UnknownHostException(sensitive) to "dns",
            SSLHandshakeException(sensitive) to "tls",
            SocketTimeoutException(sensitive) to "timeout",
            InterruptedIOException(sensitive) to "timeout",
            IOException(sensitive) to "network",
            JSONException(sensitive) to "response",
            IllegalStateException(sensitive) to "configuration",
            AssertionError(sensitive) to "unknown"
        )
        for ((error, category) in cases) {
            val notice = FishStartupFailure.describe(stage, RuntimeException(sensitive, error), true, english)
            assertEquals("TR-caption_create-$category", notice.reference)
            for (value in listOf("fixture-secret", "fixture-token", "provider.invalid", "guest-speech", "Bearer")) {
                assertFalse("exposed $value for $category", notice.text.contains(value))
                assertFalse(notice.reference.contains(value))
            }
        }
    }

    @Test fun explicitConfigurationAndMalformedTicketsDoNotBlameInternetAccess() {
        val config = FishStartupFailure.describe(stage, FishStartupFailure.configuration(stage), false, english)
        val ticket = FishStartupFailure.describe(stage, FishStartupFailure.response(FishStartupFailure.Stage.TICKET), false, english)
        assertTrue(config.reference.endsWith("configuration"))
        assertEquals("TR-ticket-response", ticket.reference)
        assertFalse(config.text.contains("internet"))
        assertFalse(ticket.text.contains("internet"))
    }

    @Test fun unknownHttpFailureRetainsOnlyAValidNumericStatus() {
        val valid = FishStartupFailure.describe(stage, FishStartupFailure.http(stage, 418), false, english)
        val invalid = FishStartupFailure.describe(stage, FishStartupFailure.http(stage, -123), false, english)
        assertEquals("TR-caption_create-http-HTTP418", valid.reference)
        assertEquals("TR-caption_create-http", invalid.reference)
    }

    @Test fun managedHeadersPreserveUpstreamFailureAndDoNotConfuseConfigurationWithInternet() {
        val upstream = FishStartupFailure.managedHeader("tool_create:http:402")!!
        val notice = FishStartupFailure.describe(FishStartupFailure.Stage.MANAGED_SESSION, upstream, true, english)
        assertEquals("TR-caption_create-billing-HTTP402", notice.reference)
        assertTrue(notice.text.contains("billing"))
        for ((header, category) in mapOf("configuration:unconfigured:0" to "unconfigured",
            "admission:budget:0" to "budget", "session_create:timeout:0" to "timeout",
            "session_ticket:response:0" to "response", "tool_fetch:contract:0" to "configuration")) {
            val failure = FishStartupFailure.managedHeader(header)!!
            assertEquals(category, failure.category.code)
        }
    }

    @Test fun untrustedManagedHeadersRequireTheExactBoundedGrammar() {
        val invalid = listOf(null, "", "agent_create:http:200", "agent_create:http:999",
            "agent_create:http:+402", "agent_create:http:0402", "agent_create:timeout:402",
            "agent_create:timeout:00", "tool_fetch:unknown:0", "unknown:http:402",
            "session_create:http:402:secret", "session_create:http:402\nsecret",
            "session_create:http:402 ", " session_create:http:402", "x".repeat(101))
        invalid.forEach { assertNull("accepted an invalid diagnostic header", FishStartupFailure.managedHeader(it)) }
    }

    @Test fun managedUpstreamCredentialsAndResourcesHaveProviderRepairInstructions() {
        val outer = FishStartupFailure.Stage.MANAGED_SESSION
        for (stageName in listOf("tool_lookup", "agent_create", "session_create")) {
            val failure = FishStartupFailure.managedHeader("$stageName:http:401")!!
            val text = FishStartupFailure.describe(outer, failure, true, english).text
            assertTrue(text.contains("provider credential on the hotel service"))
            assertFalse(text.contains("did not accept this device"))
        }
        val missing = FishStartupFailure.describe(outer, FishStartupFailure.managedHeader("session_create:http:404")!!, true, english)
        assertTrue(missing.text.contains("translation provider"))
        assertFalse(missing.text.contains("service URL"))
        for (status in listOf(400, 422)) {
            val legacy = FishStartupFailure.describe(outer, FishStartupFailure.http(outer, status), true, english)
            assertTrue(legacy.text.contains("hotel service version"))
        }
    }
}
