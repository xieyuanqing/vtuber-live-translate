package com.xyq.livetranslate

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 多源检查更新：
 * 1. 仓库内 update.json（GitHub raw）
 * 2. 国内可访问的 raw 镜像
 * 3. GitHub Releases API（再加镜像）
 */
object UpdateChecker {

    const val REPO_OWNER = "xieyuanqing"
    const val REPO_NAME = "vtuber-live-translate"
    const val MANIFEST_PATH = "update.json"

    /** 检查清单源（按顺序尝试）。 */
    val manifestSources: List<Pair<String, String>> = listOf(
        "GitHub" to "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/main/$MANIFEST_PATH",
        "jsDelivr" to "https://cdn.jsdelivr.net/gh/$REPO_OWNER/$REPO_NAME@main/$MANIFEST_PATH",
        "ghproxy" to "https://ghproxy.net/https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/main/$MANIFEST_PATH",
        "GitHub API" to "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest",
        "API 镜像" to "https://ghproxy.net/https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest",
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun check(currentVersionCode: Long, ignoredVersionCode: Long = 0L): UpdateCheckResult {
        val errors = mutableListOf<String>()
        for ((label, url) in manifestSources) {
            val body = fetchText(url)
            if (body == null) {
                errors += "$label 不可达"
                continue
            }
            val info = runCatching {
                if (url.contains("/releases/latest")) {
                    parseGitHubRelease(body, label)
                } else {
                    parseManifest(body, label)
                }
            }.getOrElse {
                errors += "$label 解析失败"
                null
            } ?: continue

            if (info.versionCode <= currentVersionCode) {
                return UpdateCheckResult.UpToDate(currentVersionCode, info.versionCode)
            }
            if (ignoredVersionCode > 0L && info.versionCode <= ignoredVersionCode) {
                return UpdateCheckResult.Ignored
            }
            return UpdateCheckResult.Available(info)
        }
        val detail = errors.take(3).joinToString("；").ifBlank { "网络不可用" }
        return UpdateCheckResult.Failed("检查更新失败：$detail")
    }

    fun parseManifest(jsonText: String, sourceLabel: String = ""): AppUpdateInfo {
        val json = JSONObject(jsonText)
        val versionCode = json.optLong("versionCode", -1L)
        require(versionCode > 0L) { "缺少 versionCode" }
        val versionName = json.optString("versionName").ifBlank { versionCode.toString() }
        val title = json.optString("title").ifBlank { "流译 $versionName" }
        val notes = json.optString("notes").ifBlank { json.optString("body") }
        val apkName = json.optString("apkName")
            .ifBlank { json.optString("apkFileName") }
            .ifBlank { "LiveTranslate-$versionName.apk" }
        val urls = mutableListOf<String>()
        val arr = json.optJSONArray("downloadUrls")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val u = arr.optString(i).trim()
                if (u.isNotEmpty()) urls += u
            }
        }
        val single = json.optString("downloadUrl").trim()
        if (single.isNotEmpty()) urls += single
        require(urls.isNotEmpty()) { "缺少 downloadUrls" }
        return AppUpdateInfo(
            versionCode = versionCode,
            versionName = versionName,
            title = title,
            notes = notes.trim(),
            apkName = apkName,
            downloadUrls = expandDownloadMirrors(urls.distinct()),
            sourceLabel = sourceLabel,
        )
    }

    fun parseGitHubRelease(jsonText: String, sourceLabel: String = ""): AppUpdateInfo {
        val json = JSONObject(jsonText)
        val tag = json.optString("tag_name").removePrefix("v").trim()
        val versionName = json.optString("name").ifBlank { tag }.ifBlank { "unknown" }
        val notes = json.optString("body").trim()
        val assets = json.optJSONArray("assets") ?: JSONArray()
        var apkUrl = ""
        var apkName = "LiveTranslate.apk"
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            apkName = name
            apkUrl = asset.optString("browser_download_url")
            if (apkUrl.isNotEmpty()) break
        }
        require(apkUrl.isNotEmpty()) { "Release 中没有 APK" }
        // tag 形如 2.4.0 时用 versionName 兜底；优先 body 外自定义字段没有则从 tag 推导 code 失败则用 published 时间不可靠。
        // 正式清单仍以 update.json 为准；API 路径要求 tag 为纯数字 versionCode 或 name 含 code=。
        val versionCode = extractVersionCode(json, tag, versionName)
        require(versionCode > 0L) { "无法解析 versionCode，请使用 update.json" }
        return AppUpdateInfo(
            versionCode = versionCode,
            versionName = versionName.removePrefix("流译").trim().ifBlank { tag },
            title = json.optString("name").ifBlank { "流译 $versionName" },
            notes = notes,
            apkName = apkName,
            downloadUrls = expandDownloadMirrors(listOf(apkUrl)),
            sourceLabel = sourceLabel,
        )
    }

    fun expandDownloadMirrors(urls: List<String>): List<String> {
        val out = linkedSetOf<String>()
        for (url in urls) {
            val clean = url.trim()
            if (clean.isEmpty()) continue
            out += clean
            if (clean.contains("github.com") || clean.contains("githubusercontent.com")) {
                out += "https://ghproxy.net/$clean"
                out += "https://mirror.ghproxy.com/$clean"
            }
        }
        return out.toList()
    }

    private fun extractVersionCode(json: JSONObject, tag: String, versionName: String): Long {
        if (json.has("versionCode")) {
            val v = json.optLong("versionCode", -1L)
            if (v > 0L) return v
        }
        // body 首行支持 "versionCode: 35"
        val body = json.optString("body")
        Regex("""versionCode\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.let { if (it > 0L) return it }
        tag.toLongOrNull()?.let { if (it > 0L) return it }
        // 最后：versionName 全是数字
        versionName.filter { it.isDigit() }.toLongOrNull()?.let { if (it > 0L) return it }
        return -1L
    }

    private fun fetchText(url: String): String? {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json, text/plain, */*")
                .header("User-Agent", "LiveTranslate-UpdateChecker")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }
}
