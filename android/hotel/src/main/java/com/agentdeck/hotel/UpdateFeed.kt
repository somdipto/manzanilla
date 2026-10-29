package com.agentdeck.hotel

import org.json.JSONObject
import java.net.URI

/** Reads either the CDN-served immutable manifest or the legacy GitHub release response.
 * APK signing/package/version verification still runs on the downloaded archive.
 */
object UpdateFeed {
    fun parse(document: JSONObject, expectedDistribution: String, fetch: (String) -> JSONObject): UpdateRequest? {
        require(UpdateDistribution.valid(expectedDistribution)) { "Invalid expected distribution" }
        if (document.optBoolean("draft") || document.optBoolean("prerelease")) return null
        if (document.has("schema")) return pinned(UpdateRequest.fromJson(document), expectedDistribution)

        val tag = document.getString("tag_name")
        // Historical CI tags are not installable versions.
        if (!tag.startsWith("v") || !ReleaseVersion.valid(tag)) return null
        val assets = document.getJSONArray("assets")
        val entries = (0 until assets.length()).map { assets.getJSONObject(it) }
        val metadataName = if (expectedDistribution == "hotel") "hotel-release-metadata.json" else "release-metadata.json"
        val apkName = if (expectedDistribution == "hotel") "Manzanilla-Hotel.apk" else "Manzanilla-Reception.apk"
        val manifest = entries.singleOrNull { it.optString("name") == metadataName }
            ?: error("Release metadata asset is missing or ambiguous")
        val manifestUrl = manifest.getString("browser_download_url")
        val manifestUri = https(manifestUrl)
        require(manifestUri.path.endsWith("/releases/download/$tag/$metadataName") &&
            manifestUri.path == manifestUri.normalize().path) { "Release metadata URL mismatch" }
        val request = UpdateRequest.fromJson(fetch(manifestUrl))
        require(request.tag == tag) { "Release tag/metadata mismatch" }
        require(request.distribution == expectedDistribution) { "Release distribution mismatch" }
        val apk = entries.singleOrNull {
            it.optString("browser_download_url") == request.url && it.optString("name") == apkName
        } ?: error("Release APK/metadata mismatch")
        val rawDigest = apk.opt("digest")
        val digest = if (rawDigest == null || rawDigest == JSONObject.NULL || rawDigest == "") null else {
            require(rawDigest is String && Regex("sha256:[0-9a-fA-F]{64}").matches(rawDigest)) {
                "Invalid release APK digest"
            }
            rawDigest.removePrefix("sha256:").lowercase()
        }
        if (request.sha256 != null && digest != null) {
            require(request.sha256 == digest) { "Release digests disagree" }
        }
        return pinned(request.copy(sha256 = digest ?: request.sha256), expectedDistribution)
    }

    private fun pinned(request: UpdateRequest, expectedDistribution: String): UpdateRequest {
        require(request.distribution == expectedDistribution) { "Release distribution mismatch" }
        require(request.sha256 != null) { "Release metadata must include an APK digest" }
        val uri = https(request.url)
        val path = uri.path.orEmpty()
        val apkName = if (expectedDistribution == "hotel") "Manzanilla-Hotel.apk" else "Manzanilla-Reception.apk"
        require(path == uri.normalize().path &&
            path.endsWith("/releases/download/${request.tag}/$apkName")) {
            "APK URL is not pinned to its release"
        }
        return request
    }

    private fun https(url: String): URI = URI(url).also {
        require(it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null) {
            "Update metadata requires HTTPS"
        }
    }
}
