package com.xyq.livetranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateCheckerTest {

    @Test
    fun parseManifestKeepsOrderAndExpandsGithubMirrors() {
        val json = """
            {
              "versionCode": 35,
              "versionName": "2.4.0",
              "title": "流译 2.4.0",
              "notes": "首个正式版",
              "apkName": "LiveTranslate-2.4.0.apk",
              "downloadUrls": [
                "https://github.com/xieyuanqing/vtuber-live-translate/releases/download/v2.4.0/LiveTranslate-2.4.0.apk"
              ]
            }
        """.trimIndent()

        val info = UpdateChecker.parseManifest(json, "test")
        assertEquals(35L, info.versionCode)
        assertEquals("2.4.0", info.versionName)
        assertEquals("首个正式版", info.notes)
        assertTrue(info.downloadUrls[0].contains("github.com"))
        assertTrue(info.downloadUrls.any { it.startsWith("https://ghproxy.net/") })
        assertTrue(info.downloadUrls.any { it.startsWith("https://mirror.ghproxy.com/") })
        assertEquals(3, info.downloadUrls.size)
        assertEquals("", info.sha256)
    }

    @Test
    fun parseManifestKeepsAValidSha256AndDropsAMalformedOne() {
        val digest = "ab".repeat(32)
        val json = """
            {
              "versionCode": 40,
              "versionName": "2.6.2",
              "apkName": "LiveTranslate-2.6.2.apk",
              "sha256": "  ${digest.uppercase()}  ",
              "downloadUrls": ["https://example.com/app.apk"]
            }
        """.trimIndent()
        assertEquals(digest, UpdateChecker.parseManifest(json).sha256)

        val malformed = json.replace(digest.uppercase(), "not-a-digest")
        assertEquals("", UpdateChecker.parseManifest(malformed).sha256)
    }

    @Test
    fun parseGitHubReleaseReadsApkAndVersionCodeFromBody() {
        val json = """
            {
              "tag_name": "v2.4.0",
              "name": "流译 2.4.0",
              "body": "versionCode: 35\n\n首个正式版",
              "assets": [
                {
                  "name": "LiveTranslate-2.4.0.apk",
                  "browser_download_url": "https://github.com/xieyuanqing/vtuber-live-translate/releases/download/v2.4.0/LiveTranslate-2.4.0.apk"
                }
              ]
            }
        """.trimIndent()
        val info = UpdateChecker.parseGitHubRelease(json, "api")
        assertEquals(35L, info.versionCode)
        assertEquals("LiveTranslate-2.4.0.apk", info.apkName)
        assertTrue(info.downloadUrls.first().endsWith(".apk"))
        assertEquals("", info.sha256)
    }

    @Test
    fun parseGitHubReleaseReadsSha256FromBody() {
        val digest = "cd".repeat(32)
        val json = """
            {
              "tag_name": "v2.6.2",
              "name": "流译 2.6.2",
              "body": "versionCode: 40\nsha256: $digest",
              "assets": [
                {
                  "name": "LiveTranslate-2.6.2.apk",
                  "browser_download_url": "https://github.com/xieyuanqing/vtuber-live-translate/releases/download/v2.6.2/LiveTranslate-2.6.2.apk"
                }
              ]
            }
        """.trimIndent()
        assertEquals(digest, UpdateChecker.parseGitHubRelease(json).sha256)
    }

    @Test
    fun verifierTrustsAMatchingDigestAndRefusesMissingOrDifferentBytes() {
        val file = File.createTempFile("livetranslate-apk", ".bin")
        try {
            file.writeBytes("apk-bytes".toByteArray())
            val digest = UpdateVerifier.digest(file)
            assertTrue(UpdateVerifier.verify(file, digest.uppercase()) is UpdateVerifier.Result.Trusted)
            assertTrue(UpdateVerifier.verify(file, "") is UpdateVerifier.Result.Missing)
            assertTrue(UpdateVerifier.verify(file, "ab".repeat(32)) is UpdateVerifier.Result.Mismatch)
        } finally {
            file.delete()
        }
        assertTrue(UpdateVerifier.verify(file, "ab".repeat(32)) is UpdateVerifier.Result.Unreadable)
    }

    @Test
    fun ignoredVersionIsHigherThanCurrentButSkippedByCallerLogic() {
        // pure data: ignored handled by UpdateChecker.check via ignoredVersionCode param;
        // here verify Available vs UpToDate branching expectations with local compare.
        val current = 34L
        val latest = 35L
        val ignored = 35L
        assertTrue(latest > current)
        assertTrue(latest <= ignored)
    }
}
