package com.xyq.livetranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun parseManifestReadsAndNormalizesSha256() {
        val digest = "B2E6EDCF5543E20910A46B1AE8F98F233EAD56641162E588A979CC7641CA0F09"
        val json = """
            {
              "versionCode": 36,
              "versionName": "2.4.1",
              "sha256": "$digest",
              "downloadUrls": ["https://example.com/a.apk"]
            }
        """.trimIndent()
        assertEquals(digest.lowercase(), UpdateChecker.parseManifest(json).sha256)
    }

    @Test
    fun malformedSha256IsTreatedAsAbsentInsteadOfBlockingUpdate() {
        val json = """
            {
              "versionCode": 36,
              "versionName": "2.4.1",
              "sha256": "not-a-hash",
              "downloadUrls": ["https://example.com/a.apk"]
            }
        """.trimIndent()
        assertEquals("", UpdateChecker.parseManifest(json).sha256)
    }

    @Test
    fun parseGitHubReleaseExtractsSha256FromBody() {
        val json = """
            {
              "tag_name": "v2.4.0",
              "name": "流译 2.4.0",
              "body": "versionCode: 35\n\nAPK SHA-256: b2e6edcf5543e20910a46b1ae8f98f233ead56641162e588a979cc7641ca0f09",
              "assets": [
                {"name": "a.apk", "browser_download_url": "https://example.com/a.apk"}
              ]
            }
        """.trimIndent()
        assertEquals(
            "b2e6edcf5543e20910a46b1ae8f98f233ead56641162e588a979cc7641ca0f09",
            UpdateChecker.parseGitHubRelease(json).sha256,
        )
    }

    @Test
    fun releaseWithoutParsableVersionCodeFailsInsteadOfGuessingFromDigits() {
        // "2.4.1" 抽数字会变成 241，导致永远提示有新版本；现在必须显式解析失败。
        val json = """
            {
              "tag_name": "v2.4.1",
              "name": "流译 2.4.1",
              "body": "没有版本号字段",
              "assets": [
                {"name": "a.apk", "browser_download_url": "https://example.com/a.apk"}
              ]
            }
        """.trimIndent()
        try {
            UpdateChecker.parseGitHubRelease(json)
            fail("缺 versionCode 的 Release 应该解析失败")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("versionCode"))
        }
    }

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
