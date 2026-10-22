package com.agentdeck.hotel

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateFeedTest {
    private val digest = "a".repeat(64)
    private val request = UpdateRequest("v17.0.29",
        "https://github.com/example/hotel/releases/download/v17.0.29/Manzanilla-Reception.apk",
        "dev.orange.hotel", "17.0.29", 59, digest)
    private val manifestUrl = "https://github.com/example/hotel/releases/download/v17.0.29/release-metadata.json"

    private fun release(): JSONObject = JSONObject().put("tag_name", request.tag)
        .put("draft", false).put("prerelease", false).put("assets", JSONArray()
            .put(JSONObject().put("name", "release-metadata.json").put("browser_download_url", manifestUrl))
            .put(JSONObject().put("name", "Manzanilla-Reception.apk").put("browser_download_url", request.url)
                .put("digest", "sha256:$digest")))

    private fun rejected(document: JSONObject, manifest: JSONObject = request.json()) {
        try {
            UpdateFeed.parse(document) { manifest }
            fail("Malformed update metadata was accepted")
        } catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
        catch (_: org.json.JSONException) { }
    }

    @Test fun staticManifestNeedsNoGithubApiOrAdditionalMetadataFetch() {
        val actual = UpdateFeed.parse(request.json()) { error("Static metadata attempted an API request") }
        assertEquals(request, actual)
        val cdn = request.copy(url = request.url.replace("github.com/example/hotel", "updates.example.org/hotel"))
        assertEquals(cdn, UpdateFeed.parse(cdn.json()) { error("Unexpected fetch") })
    }

    @Test fun staticManifestRequiresDigestAndAnImmutableMatchingReleaseUrl() {
        rejected(request.json().apply { remove("sha256") })
        rejected(request.json().put("sha256", ""))
        rejected(request.json().put("sha256", "not-a-digest"))
        rejected(request.json().put("tag", "v17.0.30"))
        rejected(request.json().put("url", request.url.replace("download/v17.0.29", "latest/download")))
        rejected(request.json().put("url", request.url.replace("v17.0.29/Manzanilla", "v17.0.29/../latest/Manzanilla")))
        rejected(request.json().put("url", request.url.replace("https:", "http:")))
    }

    @Test fun legacyReleaseFetchesOneManifestAndChecksTheSelectedApk() {
        var calls = 0
        val actual = UpdateFeed.parse(release()) { url ->
            calls++
            assertEquals(manifestUrl, url)
            request.json()
        }
        assertEquals(1, calls)
        assertEquals(request, actual)
    }

    @Test fun incompleteLegacyReleaseIsAFailureNotAnUpToDateResult() {
        rejected(release().put("assets", JSONArray()))
        rejected(release().apply { getJSONArray("assets").remove(0) })
        rejected(release().apply { getJSONArray("assets").remove(1) })
        rejected(release().apply { getJSONArray("assets").put(getJSONArray("assets").getJSONObject(0)) })
        rejected(release().apply { getJSONArray("assets").getJSONObject(0).put("browser_download_url", "http://example.org/metadata.json") })
    }

    @Test fun legacyReleaseCannotRelabelTheManifestOrSubstituteItsApk() {
        rejected(release().put("tag_name", "v17.0.30"))
        rejected(release().apply { getJSONArray("assets").getJSONObject(1).put("browser_download_url", request.url + "?replacement=1") })
        rejected(release().apply { getJSONArray("assets").getJSONObject(1).put("digest", "sha256:" + "b".repeat(64)) })
        rejected(release().apply { getJSONArray("assets").getJSONObject(1).put("digest", "sha256:broken") })
    }

    @Test fun legacyDigestMayComeFromEitherVerifiedMetadataSourceButCannotBeAbsent() {
        val noApiDigest = release().apply { getJSONArray("assets").getJSONObject(1).remove("digest") }
        assertEquals(request, UpdateFeed.parse(noApiDigest) { request.json() })
        val nullApiDigest = release().apply { getJSONArray("assets").getJSONObject(1).put("digest", JSONObject.NULL) }
        assertEquals(request, UpdateFeed.parse(nullApiDigest) { request.json() })
        val noManifestDigest = request.json().apply { remove("sha256") }
        assertEquals(request, UpdateFeed.parse(release()) { noManifestDigest })
        rejected(noApiDigest, noManifestDigest)
    }

    @Test fun draftsPrereleasesAndHistoricalCiTagsDoNotTriggerMetadataRequests() {
        for (document in listOf(release().put("draft", true), release().put("prerelease", true),
            release().put("tag_name", "run-1234"), request.json().put("prerelease", true))) {
            assertNull(UpdateFeed.parse(document) { error("Filtered release attempted a metadata fetch") })
        }
    }
}
