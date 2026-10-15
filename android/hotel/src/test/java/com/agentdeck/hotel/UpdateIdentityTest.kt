package com.agentdeck.hotel

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateIdentityTest {
    private val goodDigest = UpdateRequest.hash("intact signed APK fixture".toByteArray())
    private val a = UpdateRequest("v17.0.5", "https://github.com/example/hotel/releases/download/v17.0.5/app.apk",
        "dev.orange.hotel", "17.0.5", 35, goodDigest)
    private val b = UpdateRequest("v17.0.6", "https://github.com/example/hotel/releases/download/v17.0.6/app.apk",
        "dev.orange.hotel", "17.0.6", 36, goodDigest)
    private val installed = ApkIdentity("dev.orange.hotel", "17.0.4", 34, setOf("trusted signing certificate"))
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
}
