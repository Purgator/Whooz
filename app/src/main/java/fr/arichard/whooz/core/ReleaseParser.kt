package fr.arichard.whooz.core

import org.json.JSONArray
import org.json.JSONObject

/** What the updater needs from a GitHub release: where the APK is and how big it must be. */
data class ReleaseInfo(
    /** Raw tag, e.g. "v1.2". */
    val tagName: String,
    /** [tagName] without its `v` prefix, e.g. "1.2". */
    val version: String,
    /** Https download URL of the `Whooz.apk` asset. */
    val assetUrl: String,
    /** Size GitHub reports for the asset; the download must match it byte for byte. */
    val assetSize: Long,
)

/**
 * Pure parser of the GitHub `releases/latest` JSON. Strict on purpose: the updater only
 * ever installs what it fully understands, so any missing or odd field yields null rather
 * than a best guess, and nothing here throws on malformed input.
 */
object ReleaseParser {

    /** The only asset the updater installs; other assets (sources, checksums) are ignored. */
    const val ASSET_NAME = "Whooz.apk"

    /** A release APK is 2–3 MB; anything above this is not ours or is a mistake. */
    const val MAX_APK_BYTES: Long = 30L * 1024 * 1024

    /**
     * Returns the release described by [json], or null when the document is not a release
     * with a usable `Whooz.apk` asset (missing tag, no asset, non-https URL, size outside
     * 1..[MAX_APK_BYTES]).
     */
    fun parse(json: String?): ReleaseInfo? {
        if (json.isNullOrBlank()) return null
        return try {
            parseObject(JSONObject(json))
        } catch (e: Exception) {
            null
        }
    }

    private fun parseObject(root: JSONObject): ReleaseInfo? {
        val tagName = root.optString("tag_name", "").trim()
        val version = VersionCompare.normalize(tagName)
        if (version.isEmpty()) return null
        val assets: JSONArray = root.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name", "") != ASSET_NAME) continue
            val url = asset.optString("browser_download_url", "").trim()
            if (!url.startsWith("https://")) return null
            val size = asset.optLong("size", -1L)
            if (size < 1L || size > MAX_APK_BYTES) return null
            return ReleaseInfo(tagName, version, url, size)
        }
        return null
    }
}
