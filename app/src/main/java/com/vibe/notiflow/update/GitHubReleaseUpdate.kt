package com.vibe.notiflow.update

import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class UpdateCandidate(
    val available: Boolean,
    val latestTag: String,
    val latestVersionName: String,
    val releaseUrl: String,
    val assetName: String?,
    val downloadUrl: String?,
    val assetSize: Long
)

object GitHubReleaseUpdate {
    const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/cloim/NotiFlow/releases/latest"

    fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = versionParts(latest)
        val currentParts = versionParts(current)
        val size = maxOf(latestParts.size, currentParts.size)

        for (index in 0 until size) {
            val next = latestParts.getOrElse(index) { 0 }
            val now = currentParts.getOrElse(index) { 0 }
            if (next > now) return true
            if (next < now) return false
        }
        return false
    }

    fun parseLatestRelease(
        json: String,
        currentVersionName: String,
        isDevBuild: Boolean
    ): UpdateCandidate {
        val release = JsonParser.parseString(json).asJsonObject
        val latestTag = release.stringValue("tag_name").trim()
        val releaseUrl = release.stringValue("html_url").trim()
        val latestVersionName = latestTag.trimStart('v', 'V')
        val isNewer = isNewerVersion(latestTag, currentVersionName)
        val assetPrefix = if (isDevBuild) "NotiFlow-dev-" else "NotiFlow-prod-"
        val assets = release.getAsJsonArray("assets")

        var selectedName: String? = null
        var selectedUrl: String? = null
        var selectedSize = 0L
        if (assets != null) {
            for (element in assets) {
                val asset = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val name = asset.stringValue("name").trim()
                if (!name.startsWith(assetPrefix) || !name.endsWith(".apk")) continue
                if (latestTag.isNotBlank() && !name.contains(latestTag)) continue

                selectedName = name
                selectedUrl = asset.stringValue("browser_download_url").trim()
                selectedSize = asset.longValue("size")
                break
            }
        }

        return UpdateCandidate(
            available = isNewer && !selectedUrl.isNullOrBlank(),
            latestTag = latestTag,
            latestVersionName = latestVersionName,
            releaseUrl = releaseUrl,
            assetName = selectedName,
            downloadUrl = selectedUrl,
            assetSize = selectedSize
        )
    }

    private fun versionParts(raw: String): List<Int> {
        return raw
            .trim()
            .trimStart('v', 'V')
            .split(Regex("[^0-9]+"))
            .filter { it.isNotBlank() }
            .map { it.toIntOrNull() ?: 0 }
    }

    private fun JsonObject.stringValue(key: String): String {
        val element = get(key) ?: return ""
        return if (element.isJsonNull) "" else element.asString
    }

    private fun JsonObject.longValue(key: String): Long {
        val element = get(key) ?: return 0L
        return if (element.isJsonNull) 0L else element.asLong
    }
}
