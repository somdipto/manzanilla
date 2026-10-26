package com.agentdeck.hotel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class UpdateFailureTest {
    @Test fun httpFailureNamesStageAndStatus() {
        assertEquals("Could not check for updates (update feed, HTTP 403). Tap to retry.",
            UpdateFailure.describe(IllegalStateException("update feed HTTP 403")))
    }

    @Test fun nestedHttpFailureIsFound() {
        val wrapped = IllegalStateException("parse failed", IllegalStateException("release manifest HTTP 404"))
        assertEquals("Could not check for updates (release manifest, HTTP 404). Tap to retry.",
            UpdateFailure.describe(wrapped))
    }

    @Test fun networkFailuresAreNamed() {
        assertEquals("Could not check for updates (network). Tap to retry.",
            UpdateFailure.describe(UnknownHostException("unresolved host")))
        assertEquals("Could not check for updates (network). Tap to retry.",
            UpdateFailure.describe(IllegalStateException("wrapper", IOException("connection reset"))))
    }

    @Test fun metadataMismatchesStayGeneric() {
        assertEquals("Could not check for updates (release data). Tap to retry.",
            UpdateFailure.describe(IllegalStateException("Release APK/metadata mismatch")))
    }

    @Test fun failureTextNeverCarriesUrls() {
        for (error in listOf(IllegalStateException("update feed HTTP 403"), UnknownHostException("x"),
            IllegalStateException("Release digests disagree"))) {
            val text = UpdateFailure.describe(error)
            assertFalse(text.contains("http:"))
            assertFalse(text.contains("github"))
        }
    }
}
