package com.agentdeck.hotel

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateIdentityTest {
    private val goodDigest = UpdateRequest.hash("intact signed APK fixture".toByteArray())
    private val a = UpdateRequest("v17.0.5", "https://github.com/example/hotel/releases/download/v17.0.5/app.apk",
        "dev.orange.hotel", "17.0.5", 35, goodDigest, "personal-test")
    private val b = UpdateRequest("v17.0.6", "https://github.com/example/hotel/releases/download/v17.0.6/app.apk",
        "dev.orange.hotel", "17.0.6", 36, goodDigest, "personal-test")
    private val installed = ApkIdentity("dev.orange.hotel", "17.0.4", 34, setOf("trusted signing certificate"), "personal-test")
    private val apkA = installed.copy(versionName = "17.0.5", versionCode = 35)

    @Test fun resumedDownloadStaysAWhenLatestFeedAdvancesToB() {
        val stored = JSONObject(a.json().toString()).put("download_id", 101L)
        val resumed = UpdateRequest.fromJson(stored)
        val ledger = UpdateOwnership()
        val attemptA = ledger.begin(resumed)!!
        assertTrue(ledger.attach(attemptA, stored.getLong("download_id")))
        assertNull(ledger.begin(b))
        assertEquals(a, ledger.active!!.request)
        assertEquals(a.key, resumed.key)
        assertTrue(ledger.ownsDownload(attemptA, 101, a.url))
        assertFalse(ledger.ownsDownload(attemptA, 101, b.url))
        assertFalse(ledger.ownsDownload(attemptA, 102, a.url))
        assertNull(resumed.rejects(apkA, installed, goodDigest))
        assertNotNull(b.rejects(apkA, installed, goodDigest))
    }

    @Test fun cancelAndRetryRejectOldCompletionEvenForSameRequestAndReusedDownloadId() {
        val ledger = UpdateOwnership()
        val first = ledger.begin(a)!!
        ledger.attach(first, 101)
        ledger.cancel()
        val retry = ledger.begin(a)!!
        ledger.attach(retry, 101)
        assertFalse(ledger.owns(first))
        assertFalse(ledger.ownsDownload(first, 101, a.url))
        assertFalse(ledger.attach(first, 202))
        ledger.detach(first)
        assertTrue(ledger.ownsDownload(retry, 101, a.url))
        assertNotEquals(first.revision, retry.revision)
        ledger.cancel()
        assertNotNull(ledger.begin(b))
    }

    @Test fun cancellationAlsoInvalidatesAnOutstandingFeedRefresh() {
        val ledger = UpdateOwnership()
        val before = ledger.generation
        ledger.cancel()
        assertNotEquals(before, ledger.generation)
        assertNull(ledger.active)
    }

    @Test fun failedPinnedAttemptAllowsNewerReleaseWithoutAcceptingStaleCallbacks() {
        val ledger = UpdateOwnership()
        val failed = ledger.begin(a)!!
        ledger.attach(failed, 101)
        // An active download remains pinned even when the feed advances.
        assertNull(ledger.begin(b))
        // Terminal download, digest and identity failures all invalidate their lease.
        assertTrue(ledger.invalidate(failed))
        assertNull(ledger.active)
        assertEquals(-1L, ledger.downloadId)
        val newer = ledger.begin(b)!!
        ledger.attach(newer, 102)
        assertFalse(ledger.invalidate(failed))
        assertFalse(ledger.attach(failed, 101))
        assertFalse(ledger.ownsDownload(failed, 101, a.url))
        assertTrue(ledger.ownsDownload(newer, 102, b.url))
        assertNull(b.rejects(apkA.copy(versionName = "17.0.6", versionCode = 36), installed, goodDigest))
    }

    @Test fun corruptBytesWrongIdentityAndDowngradesAreRejectedBeforeOffer() {
        assertNotNull(a.rejects(apkA, installed, UpdateRequest.hash("truncated or replaced bytes".toByteArray())))
        assertNotNull(a.rejects(apkA.copy(packageName = "other.package"), installed, goodDigest))
        assertNotNull(a.rejects(apkA.copy(versionCode = 36), installed, goodDigest))
        assertNotNull(a.rejects(apkA.copy(versionName = "17.0.6"), installed, goodDigest))
        assertNotNull(a.rejects(apkA.copy(signers = setOf("attacker")), installed, goodDigest))
        assertNotNull(a.rejects(apkA.copy(signers = emptySet()), installed, goodDigest))
        assertNotNull(a.rejects(apkA, installed.copy(versionCode = 35), goodDigest))
        assertNotNull(a.rejects(apkA, installed.copy(versionName = "17.0.6"), goodDigest))
        // A feed lacking an optional API digest still requires actual package, version and signer identity.
        assertNull(a.copy(sha256 = null).rejects(apkA, installed, "unavailable"))
        assertNotNull(a.copy(sha256 = null).rejects(apkA.copy(signers = setOf("attacker")), installed, "unavailable"))
    }

    @Test fun changingAnyImmutableExpectationSeparatesItsCacheIdentity() {
        assertNotEquals(a.key, b.key)
        assertNotEquals(a.key, a.copy(url = a.url.replace("app.apk", "replacement.apk")).key)
        assertNotEquals(a.key, a.copy(sha256 = UpdateRequest.hash("different".toByteArray())).key)
        assertEquals(a, UpdateRequest.fromJson(a.json()))
        assertNotEquals(a.key, a.copy(distribution = "hotel").key)
    }

    @Test fun legacyPersistenceCannotBecomeAnUnverifiedRequest() {
        try {
            UpdateRequest.fromJson(JSONObject().put("pendingVersion", "17.0.5").put("pendingUrl", a.url))
            fail("Legacy state must be discarded rather than guessed")
        } catch (_: org.json.JSONException) { }
        try {
            a.copy(tag = "v17.0.6")
            fail("A cannot be relabelled B")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun distributionCannotBeChangedByCachedRequestsArchivesOrInstallerResults() {
        val hotel = a.copy(distribution = "hotel")
        val hotelApk = apkA.copy(distribution = "hotel")
        val hotelInstalled = installed.copy(distribution = "hotel")
        assertNotNull(a.rejects(hotelApk, installed, goodDigest))
        assertNotNull(hotel.rejects(hotelApk, installed, goodDigest))
        assertNotNull(a.rejects(apkA, hotelInstalled, goodDigest))
        assertNull(hotel.rejects(hotelApk, hotelInstalled, goodDigest))
        assertFalse(UpdateInstallerSecurity.installedCandidate(a, hotelApk))
        assertFalse(UpdateInstallerSecurity.installedCandidate(hotel, apkA))
        assertEquals(hotel, UpdateRequest.fromJson(hotel.json()))
        assertNotEquals(a.key, hotel.key)
    }

    @Test fun onlyHistoricalInstalledManifestMayOmitDistribution() {
        assertEquals("personal-test", UpdateDistribution.fromManifest(null, historical = true))
        for (historical in listOf(false, true)) {
            for (value in listOf("personal-test", "hotel"))
                assertEquals(value, UpdateDistribution.fromManifest(value, historical))
            for (value in listOf("", "HOTEL", "unknown", 1)) {
                try {
                    UpdateDistribution.fromManifest(value, historical)
                    fail("Invalid manifest distribution was accepted")
                } catch (_: IllegalArgumentException) { }
            }
        }
        try {
            UpdateDistribution.fromManifest(null, historical = false)
            fail("A new archive without distribution was accepted")
        } catch (_: IllegalArgumentException) { }
        for (document in listOf(a.json().apply { remove("distribution") },
                a.json().put("distribution", "unknown"))) {
            try {
                UpdateRequest.fromJson(document)
                fail("Missing or invalid persisted distribution was accepted")
            } catch (_: org.json.JSONException) { }
            catch (_: IllegalArgumentException) { }
        }
        try {
            apkA.copy(distribution = "")
            fail("An archive identity without distribution was accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun installerCallbacksRequireCurrentCapabilitySessionOwnershipAndPhase() {
        val uri = UpdateInstallerSecurity.newCallbackUri()
        assertTrue(uri.matches(Regex("manzanilla-update://callback/[0-9a-f]{64}")))
        val next = UpdateInstallerSecurity.newCallbackUri()
        assertNotEquals(uri, next)
        fun accepts(expected: String? = uri, actual: String? = uri, session: Int = 41,
                    reported: Int = 41, owned: Boolean = true, phase: String = "installing") =
            UpdateInstallerSecurity.acceptsCallback(expected, actual, session, reported, owned, phase)
        assertTrue(accepts())
        assertTrue(accepts(phase = "confirming"))
        assertFalse(accepts(actual = null))
        assertFalse(accepts(actual = "manzanilla-update://callback/"))
        assertFalse(accepts(actual = next))
        assertFalse(accepts(expected = next)) // A previous attempt's callback is not reusable.
        assertFalse(accepts(expected = null)) // Terminal cleanup/process death destroys authority.
        assertFalse(accepts(actual = uri + "?nonce=ignored"))
        assertFalse(accepts(actual = uri + "#ignored"))
        assertFalse(accepts(actual = uri.replace("callback/", "other/")))
        assertFalse(accepts(actual = uri.uppercase()))
        assertFalse(accepts(session = -1, reported = -1))
        assertFalse(accepts(reported = -1))
        assertFalse(accepts(reported = 42))
        assertFalse(accepts(owned = false))
        for (phase in listOf("idle", "ready", "validating", "permission", "installed"))
            assertFalse(accepts(phase = phase))
    }

    @Test fun installerSuccessRequiresTheActuallyInstalledCandidateNotJustAnAuthenticatedStatus() {
        assertTrue(UpdateInstallerSecurity.installedCandidate(a, apkA))
        assertFalse(UpdateInstallerSecurity.installedCandidate(a, installed))
        assertFalse(UpdateInstallerSecurity.installedCandidate(a, apkA.copy(packageName = "other.package")))
        assertFalse(UpdateInstallerSecurity.installedCandidate(a, apkA.copy(versionName = "17.0.6")))
        assertFalse(UpdateInstallerSecurity.installedCandidate(a, apkA.copy(versionCode = 36)))
    }
}
