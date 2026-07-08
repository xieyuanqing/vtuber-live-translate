package com.xyq.livetranslate

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** YouTube URL 解析 + oEmbed 元数据获取。后端逻辑先固定，UI 只调用这个入口。 */
object YouTubeOEmbedClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    fun normalizeWatchUrl(input: String): String {
        val raw = input.trim()
        if (raw.isEmpty()) return ""
        val u = raw.toHttpUrlOrNull() ?: return raw
        val host = u.host.lowercase()
        val id = when {
            host == "youtu.be" -> u.encodedPathSegments.firstOrNull()
            host.endsWith("youtube.com") && u.encodedPath == "/watch" -> u.queryParameter("v")
            host.endsWith("youtube.com") && u.encodedPath.startsWith("/live/") ->
                u.encodedPathSegments.getOrNull(1)
            host.endsWith("youtube.com") && u.encodedPath.startsWith("/shorts/") ->
                u.encodedPathSegments.getOrNull(1)
            else -> null
        }?.takeIf { it.isNotBlank() }
        return if (id == null) raw else "https://www.youtube.com/watch?v=$id"
    }

    fun fetch(inputUrl: String): YouTubeVideoInfo {
        val url = normalizeWatchUrl(inputUrl)
        require(url.isNotBlank()) { "YouTube URL 为空" }
        val api = "https://www.youtube.com/oembed?format=json&url=" +
            URLEncoder.encode(url, "UTF-8")
        val req = Request.Builder().url(api).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("YouTube oEmbed 失败：HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            return YouTubeVideoInfo(
                url = url,
                title = json.optString("title", ""),
                authorName = json.optString("author_name", ""),
            )
        }
    }
}
