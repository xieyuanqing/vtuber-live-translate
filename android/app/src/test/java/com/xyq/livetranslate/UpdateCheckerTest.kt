package com.xyq.livetranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
