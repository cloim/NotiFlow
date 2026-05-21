package com.vibe.notiflow

import com.vibe.notiflow.update.GitHubReleaseUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseUpdateTest {
    @Test
    fun comparesSemanticVersionsWithTagPrefix() {
        assertTrue(GitHubReleaseUpdate.isNewerVersion("v1.0.11", "1.0.10"))
        assertTrue(GitHubReleaseUpdate.isNewerVersion("v1.1.0", "1.0.99"))
        assertFalse(GitHubReleaseUpdate.isNewerVersion("v1.0.10", "1.0.10"))
        assertFalse(GitHubReleaseUpdate.isNewerVersion("v1.0.9", "1.0.10"))
    }

    @Test
    fun selectsFlavorSpecificApkAssetFromLatestRelease() {
        val releaseJson = """
            {
              "tag_name": "v1.0.11",
              "html_url": "https://github.com/cloim/NotiFlow/releases/tag/v1.0.11",
              "assets": [
                {
                  "name": "NotiFlow-dev-v1.0.11.apk",
                  "browser_download_url": "https://example.test/dev.apk",
                  "size": 101
                },
                {
                  "name": "NotiFlow-prod-v1.0.11.apk",
                  "browser_download_url": "https://example.test/prod.apk",
                  "size": 202
                },
                {
                  "name": "NotiFlow-prod-v1.0.11.aab",
                  "browser_download_url": "https://example.test/prod.aab",
                  "size": 303
                }
              ]
            }
        """.trimIndent()

        val prod = GitHubReleaseUpdate.parseLatestRelease(
            json = releaseJson,
            currentVersionName = "1.0.10",
            isDevBuild = false
        )
        val dev = GitHubReleaseUpdate.parseLatestRelease(
            json = releaseJson,
            currentVersionName = "1.0.10",
            isDevBuild = true
        )

        assertTrue(prod.available)
        assertEquals("NotiFlow-prod-v1.0.11.apk", prod.assetName)
        assertEquals("https://example.test/prod.apk", prod.downloadUrl)

        assertTrue(dev.available)
        assertEquals("NotiFlow-dev-v1.0.11.apk", dev.assetName)
        assertEquals("https://example.test/dev.apk", dev.downloadUrl)
    }

    @Test
    fun reportsNoUpdateWhenLatestReleaseIsNotNewer() {
        val releaseJson = """
            {
              "tag_name": "v1.0.10",
              "html_url": "https://github.com/cloim/NotiFlow/releases/tag/v1.0.10",
              "assets": [
                {
                  "name": "NotiFlow-prod-v1.0.10.apk",
                  "browser_download_url": "https://example.test/prod.apk",
                  "size": 202
                }
              ]
            }
        """.trimIndent()

        val update = GitHubReleaseUpdate.parseLatestRelease(
            json = releaseJson,
            currentVersionName = "1.0.10",
            isDevBuild = false
        )

        assertFalse(update.available)
        assertEquals("v1.0.10", update.latestTag)
    }
}
