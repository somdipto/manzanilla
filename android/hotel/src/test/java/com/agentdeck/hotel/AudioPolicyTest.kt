package com.agentdeck.hotel

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

class AudioPolicyTest {
    @Test fun ineligibleStartReportsStaffLedGuidanceWithoutOpeningTransportAndDisposes() {
        // Local-only sentinel: any attempted TLS connection would be visible as an accepted socket.
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { sentinel ->
            sentinel.soTimeout = 200
            val events = ArrayList<JSONObject>()
            val client = CloudClient("https://localhost:${sentinel.localPort}", "isolated-device-token", events::add) {
                fail("Blocked audio must not deliver PCM")
            }
            try {
                client.start("reception", "en", "es", "isolated-session", 1)
                client.start("reception", "en", "es", "isolated-session", 1)
                assertEquals(1, events.size)
                assertEquals("error", events.single().getString("type"))
                try {
                    sentinel.accept().use { fail("Ineligible audio opened a transport") }
                } catch (_: SocketTimeoutException) { /* Expected: no connection attempt. */ }
            } finally { client.dispose() }
        }
    }
}
