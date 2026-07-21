package com.xyq.livetranslate

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class VideoLinkKind {
    YOUTUBE,
    BILIBILI_VIDEO,
    BILIBILI_LIVE,
    BILIBILI_SHORT,
    TWITCH_CHANNEL,
    TWITCH_VIDEO,
    TWITCH_CLIP,
    GENERIC_PAGE,
}

data class ParsedVideoLink(
    val kind: VideoLinkKind,
    val id: String,
    val normalizedUrl: String,
)

internal data class BilibiliRedirectTarget(
    val nextUrl: HttpUrl,
    val resolvedLink: ParsedVideoLink?,
)

internal data class VideoMetadataEndpoints(
    val youtubeOEmbed: HttpUrl = "https://www.youtube.com/oembed".toHttpUrl(),
    val bilibiliVideo: HttpUrl = "https://api.bilibili.com/x/web-interface/view".toHttpUrl(),
    val bilibiliLiveRoom: HttpUrl = "https://api.live.bilibili.com/room/v1/Room/get_info".toHttpUrl(),
    val bilibiliLiveUser: HttpUrl = "https://api.live.bilibili.com/live_user/v1/Master/info".toHttpUrl(),
    val twitchGraphQl: HttpUrl = "https://gql.twitch.tv/gql".toHttpUrl(),
    val webReader: HttpUrl = "https://r.jina.ai/".toHttpUrl(),
)

/** 自动识别视频平台并获取公开元数据，UI 与 AI 整理层只依赖这个入口。 */
object VideoMetadataClient {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    // Twitch 网页客户端公开使用的应用标识，不是 OAuth Token 或用户凭据。
    private const val TWITCH_WEB_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val HTTP_URL = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
    private val BV_ID = Regex("^BV[0-9A-Za-z]+$", RegexOption.IGNORE_CASE)
    private val AV_ID = Regex("^av([0-9]+)$", RegexOption.IGNORE_CASE)
    private val TWITCH_RESERVED_PATHS = setOf(
        "directory", "downloads", "jobs", "p", "search", "settings", "subscriptions",
        "turbo", "videos", "wallet",
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    fun fetch(inputUrl: String): VideoMetadata = fetch(inputUrl, http, VideoMetadataEndpoints())

    internal fun fetch(
        inputUrl: String,
        client: OkHttpClient,
        endpoints: VideoMetadataEndpoints,
    ): VideoMetadata {
        var link = parseLink(inputUrl)
        if (link.kind == VideoLinkKind.BILIBILI_SHORT) {
            link = resolveBilibiliShortLink(link.normalizedUrl, client)
        }
        return when (link.kind) {
            VideoLinkKind.YOUTUBE -> fetchYouTube(link, client, endpoints)
            VideoLinkKind.BILIBILI_VIDEO -> fetchBilibiliVideo(link, client, endpoints)
            VideoLinkKind.BILIBILI_LIVE -> fetchBilibiliLive(link, client, endpoints)
            VideoLinkKind.TWITCH_CHANNEL,
            VideoLinkKind.TWITCH_VIDEO,
            VideoLinkKind.TWITCH_CLIP,
            -> fetchTwitch(link, client, endpoints)
            VideoLinkKind.GENERIC_PAGE -> WebPageReaderClient.fetch(
                link.normalizedUrl,
                client,
                endpoints.webReader,
            )
            VideoLinkKind.BILIBILI_SHORT -> error("B站短链未能解析为视频或直播链接")
        }
    }

    fun parseLink(input: String): ParsedVideoLink {
        val raw = extractUrl(input)
        require(raw.isNotBlank()) { "视频链接为空" }
        val withScheme = if (raw.contains("://")) raw else "https://$raw"
        val url = withScheme.toHttpUrlOrNull() ?: error("视频链接格式无效")
        require(url.scheme == "http" || url.scheme == "https") { "视频链接格式无效" }
        val host = url.host.lowercase()
        val segments = url.pathSegments.filter { it.isNotBlank() }

        if (host == "youtu.be" || host.isDomainOf("youtube.com")) {
            val id = when {
                host == "youtu.be" -> segments.firstOrNull()
                url.encodedPath == "/watch" -> url.queryParameter("v")
                segments.firstOrNull() in setOf("live", "shorts", "embed") -> segments.getOrNull(1)
                else -> null
            }?.trim().orEmpty()
            require(id.isNotBlank()) { "无法识别 YouTube 视频 ID" }
            return ParsedVideoLink(
                VideoLinkKind.YOUTUBE,
                id,
                "https://www.youtube.com/watch?v=$id",
            )
        }

        if (host == "b23.tv") {
            require(url.scheme == "https" && url.port == 443) { "B站短链必须使用 HTTPS" }
            return ParsedVideoLink(VideoLinkKind.BILIBILI_SHORT, "", url.toString())
        }
        if (host == "live.bilibili.com") {
            val roomId = segments.firstOrNull()?.takeIf { it.all(Char::isDigit) }.orEmpty()
            require(roomId.isNotBlank()) { "无法识别哔哩哔哩直播间号" }
            return ParsedVideoLink(
                VideoLinkKind.BILIBILI_LIVE,
                roomId,
                "https://live.bilibili.com/$roomId",
            )
        }
        if (host.isDomainOf("bilibili.com")) {
            require(segments.firstOrNull().equals("video", ignoreCase = true)) {
                "暂不支持此类哔哩哔哩链接"
            }
            val id = segments.getOrNull(1).orEmpty()
            require(BV_ID.matches(id) || AV_ID.matches(id)) { "无法识别哔哩哔哩视频 ID" }
            return ParsedVideoLink(
                VideoLinkKind.BILIBILI_VIDEO,
                id,
                "https://www.bilibili.com/video/$id",
            )
        }

        if (host.isDomainOf("twitch.tv")) {
            if (host == "clips.twitch.tv") {
                val slug = segments.firstOrNull().orEmpty()
                require(slug.isNotBlank()) { "无法识别 Twitch Clip" }
                return ParsedVideoLink(
                    VideoLinkKind.TWITCH_CLIP,
                    slug,
                    "https://clips.twitch.tv/$slug",
                )
            }
            if (segments.firstOrNull().equals("videos", ignoreCase = true)) {
                val videoId = segments.getOrNull(1)?.takeIf { it.all(Char::isDigit) }.orEmpty()
                require(videoId.isNotBlank()) { "无法识别 Twitch VOD" }
                return ParsedVideoLink(
                    VideoLinkKind.TWITCH_VIDEO,
                    videoId,
                    "https://www.twitch.tv/videos/$videoId",
                )
            }
            if (segments.getOrNull(1).equals("clip", ignoreCase = true)) {
                val slug = segments.getOrNull(2).orEmpty()
                require(slug.isNotBlank()) { "无法识别 Twitch Clip" }
                return ParsedVideoLink(
                    VideoLinkKind.TWITCH_CLIP,
                    slug,
                    "https://clips.twitch.tv/$slug",
                )
            }
            val channel = segments.firstOrNull().orEmpty()
            require(channel.isNotBlank() && channel.lowercase() !in TWITCH_RESERVED_PATHS) {
                "无法识别 Twitch 频道"
            }
            return ParsedVideoLink(
                VideoLinkKind.TWITCH_CHANNEL,
                channel,
                "https://www.twitch.tv/$channel",
            )
        }

        val publicUrl = WebPageReaderClient.validateTargetUrl(url.toString())
        return ParsedVideoLink(
            VideoLinkKind.GENERIC_PAGE,
            "",
            publicUrl.toString(),
        )
    }

    private fun fetchYouTube(
        link: ParsedVideoLink,
        client: OkHttpClient,
        endpoints: VideoMetadataEndpoints,
    ): VideoMetadata {
        val api = endpoints.youtubeOEmbed.newBuilder()
            .addQueryParameter("format", "json")
            .addQueryParameter("url", link.normalizedUrl)
            .build()
        val json = getJson(client, api, "YouTube oEmbed")
        return VideoMetadata(
            platform = VideoPlatform.YOUTUBE,
            url = link.normalizedUrl,
            title = clean(json.optString("title")),
            authorName = clean(json.optString("author_name")),
        )
    }

    private fun fetchBilibiliVideo(
        link: ParsedVideoLink,
        client: OkHttpClient,
        endpoints: VideoMetadataEndpoints,
    ): VideoMetadata {
        val builder = endpoints.bilibiliVideo.newBuilder()
        val avMatch = AV_ID.matchEntire(link.id)
        if (avMatch == null) {
            builder.addQueryParameter("bvid", link.id)
        } else {
            builder.addQueryParameter("aid", avMatch.groupValues[1])
        }
        val root = getJson(client, builder.build(), "哔哩哔哩视频信息", bilibiliHeaders())
        requireApiSuccess(root, "哔哩哔哩视频信息")
        val data = root.optJSONObject("data") ?: error("哔哩哔哩没有返回视频信息")
        val owner = data.optJSONObject("owner")
        return VideoMetadata(
            platform = VideoPlatform.BILIBILI,
            url = canonicalBilibiliVideoUrl(data, link),
            title = clean(data.optString("title")),
            authorName = clean(owner?.optString("name").orEmpty()),
            category = clean(data.optString("tname")),
            description = cleanDescription(data.optString("desc")),
        )
    }

    private fun fetchBilibiliLive(
        link: ParsedVideoLink,
        client: OkHttpClient,
        endpoints: VideoMetadataEndpoints,
    ): VideoMetadata {
        val roomApi = endpoints.bilibiliLiveRoom.newBuilder()
            .addQueryParameter("room_id", link.id)
            .build()
        val root = getJson(client, roomApi, "哔哩哔哩直播间信息", bilibiliHeaders(link.normalizedUrl))
        requireApiSuccess(root, "哔哩哔哩直播间信息")
        val data = root.optJSONObject("data") ?: error("哔哩哔哩没有返回直播间信息")
        val uid = data.optLong("uid", 0L)
        val author = if (uid > 0L) {
            runCatching {
                val userApi = endpoints.bilibiliLiveUser.newBuilder()
                    .addQueryParameter("uid", uid.toString())
                    .build()
                val userRoot = getJson(
                    client,
                    userApi,
                    "哔哩哔哩主播信息",
                    bilibiliHeaders(link.normalizedUrl),
                )
                requireApiSuccess(userRoot, "哔哩哔哩主播信息")
                clean(
                    userRoot.optJSONObject("data")
                        ?.optJSONObject("info")
                        ?.optString("uname")
                        .orEmpty(),
                )
            }.getOrDefault("")
        } else {
            ""
        }
        val realRoomId = data.optLong("room_id", 0L).takeIf { it > 0L }?.toString() ?: link.id
        return VideoMetadata(
            platform = VideoPlatform.BILIBILI,
            url = "https://live.bilibili.com/$realRoomId",
            title = clean(data.optString("title")),
            authorName = author,
            category = clean(data.optString("area_name")),
        )
    }

    private fun fetchTwitch(
        link: ParsedVideoLink,
        client: OkHttpClient,
        endpoints: VideoMetadataEndpoints,
    ): VideoMetadata {
        val (operationName, query) = when (link.kind) {
            VideoLinkKind.TWITCH_CHANNEL -> "ChannelMetadata" to """
                query ChannelMetadata(${dollar()}login: String!) {
                  user(login: ${dollar()}login) {
                    login displayName description
                    stream { title game { name } }
                  }
                }
            """.trimIndent()
            VideoLinkKind.TWITCH_VIDEO -> "VideoMetadata" to """
                query VideoMetadata(${dollar()}id: ID!) {
                  video(id: ${dollar()}id) {
                    id title owner { login displayName } game { displayName }
                  }
                }
            """.trimIndent()
            VideoLinkKind.TWITCH_CLIP -> "ClipMetadata" to """
                query ClipMetadata(${dollar()}slug: ID!) {
                  clip(slug: ${dollar()}slug) {
                    slug title broadcaster { login displayName } game { displayName }
                  }
                }
            """.trimIndent()
            else -> error("不是 Twitch 链接")
        }
        val variableName = when (link.kind) {
            VideoLinkKind.TWITCH_CHANNEL -> "login"
            VideoLinkKind.TWITCH_VIDEO -> "id"
            VideoLinkKind.TWITCH_CLIP -> "slug"
            else -> error("不是 Twitch 链接")
        }
        val body = JSONObject()
            .put("operationName", operationName)
            .put("query", query)
            .put("variables", JSONObject().put(variableName, link.id))
            .toString()
            .toRequestBody(JSON)
        val request = Request.Builder()
            .url(endpoints.twitchGraphQl)
            .post(body)
            .header("Client-ID", TWITCH_WEB_CLIENT_ID)
            .header("Origin", "https://www.twitch.tv")
            .header("Referer", link.normalizedUrl)
            .header("User-Agent", USER_AGENT)
            .build()
        val root = executeJson(client, request, "Twitch 内容信息")
        root.optJSONArray("errors")?.takeIf { it.length() > 0 }?.let { errors ->
            error("Twitch 内容信息失败：${errors.optJSONObject(0)?.optString("message").orEmpty()}")
        }
        val data = root.optJSONObject("data") ?: error("Twitch 没有返回内容信息")
        return when (link.kind) {
            VideoLinkKind.TWITCH_CHANNEL -> {
                val user = data.optJSONObject("user") ?: error("Twitch 频道不存在或不可访问")
                val stream = user.optJSONObject("stream")
                val displayName = clean(user.optString("displayName")).ifBlank { link.id }
                VideoMetadata(
                    platform = VideoPlatform.TWITCH,
                    url = link.normalizedUrl,
                    title = clean(stream?.optString("title").orEmpty())
                        .ifBlank { "$displayName 的 Twitch 频道" },
                    authorName = displayName,
                    category = clean(stream?.optJSONObject("game")?.optString("name").orEmpty()),
                    description = cleanDescription(user.optString("description")),
                )
            }
            VideoLinkKind.TWITCH_VIDEO -> {
                val video = data.optJSONObject("video") ?: error("Twitch VOD 不存在或不可访问")
                VideoMetadata(
                    platform = VideoPlatform.TWITCH,
                    url = link.normalizedUrl,
                    title = clean(video.optString("title")),
                    authorName = clean(video.optJSONObject("owner")?.optString("displayName").orEmpty()),
                    category = clean(video.optJSONObject("game")?.optString("displayName").orEmpty()),
                )
            }
            VideoLinkKind.TWITCH_CLIP -> {
                val clip = data.optJSONObject("clip") ?: error("Twitch Clip 不存在或不可访问")
                VideoMetadata(
                    platform = VideoPlatform.TWITCH,
                    url = link.normalizedUrl,
                    title = clean(clip.optString("title")),
                    authorName = clean(
                        clip.optJSONObject("broadcaster")?.optString("displayName").orEmpty(),
                    ),
                    category = clean(clip.optJSONObject("game")?.optString("displayName").orEmpty()),
                )
            }
            else -> error("不是 Twitch 链接")
        }
    }

    private fun resolveBilibiliShortLink(url: String, client: OkHttpClient): ParsedVideoLink {
        val redirectClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        var current = url.toHttpUrl()
        repeat(4) {
            require(current.scheme == "https" && current.host == "b23.tv" && current.port == 443) {
                "B站短链跳转目标不安全"
            }
            val request = Request.Builder()
                .url(current)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.bilibili.com/")
                .build()
            redirectClient.newCall(request).execute().use { response ->
                require(response.code in setOf(301, 302, 303, 307, 308)) {
                    "B站短链已失效或未跳转"
                }
                val location = response.header("Location")
                    ?: error("B站短链跳转缺少目标地址")
                val target = validateBilibiliRedirect(current, location)
                target.resolvedLink?.let { return it }
                current = target.nextUrl
            }
        }
        error("B站短链跳转次数过多")
    }

    internal fun validateBilibiliRedirect(
        current: HttpUrl,
        location: String,
    ): BilibiliRedirectTarget {
        val next = current.resolve(location) ?: error("B站短链跳转地址无效")
        require(next.scheme == "https" && next.port == 443) { "B站短链只允许 HTTPS 跳转" }
        if (next.host == "b23.tv") {
            return BilibiliRedirectTarget(next, null)
        }
        val parsed = parseLink(next.toString())
        require(
            parsed.kind == VideoLinkKind.BILIBILI_VIDEO ||
                parsed.kind == VideoLinkKind.BILIBILI_LIVE,
        ) { "B站短链跳转到了不支持的内容" }
        return BilibiliRedirectTarget(next, parsed)
    }

    private fun getJson(
        client: OkHttpClient,
        url: HttpUrl,
        label: String,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject {
        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        headers.forEach { (name, value) -> builder.header(name, value) }
        return executeJson(client, builder.build(), label)
    }

    private fun executeJson(client: OkHttpClient, request: Request, label: String): JSONObject =
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("$label 失败：HTTP ${response.code}")
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) error("$label 返回为空")
            runCatching { JSONObject(text) }
                .getOrElse { error("$label 返回格式无效") }
        }

    private fun requireApiSuccess(root: JSONObject, label: String) {
        val code = root.optInt("code", Int.MIN_VALUE)
        if (code != 0) {
            val message = clean(root.optString("message")).ifBlank { "错误码 $code" }
            error("$label 失败：$message")
        }
    }

    private fun canonicalBilibiliVideoUrl(data: JSONObject, fallback: ParsedVideoLink): String {
        val bvid = clean(data.optString("bvid"))
        return if (bvid.isBlank()) fallback.normalizedUrl else "https://www.bilibili.com/video/$bvid"
    }

    private fun bilibiliHeaders(referer: String = "https://www.bilibili.com/"): Map<String, String> =
        mapOf("Referer" to referer)

    private fun extractUrl(input: String): String {
        val trimmed = input.trim()
        val found = HTTP_URL.find(trimmed)?.value ?: trimmed
        return found.trimEnd(')', ']', '}', '>', '，', '。', '！', '？', ',', '.', ';')
    }

    private fun String.isDomainOf(domain: String): Boolean =
        this == domain || endsWith(".$domain")

    private fun clean(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private fun cleanDescription(value: String): String = clean(value).take(600)

    /** 避免 Kotlin 在多行字符串中把 GraphQL 的 `$` 当作模板起始符。 */
    private fun dollar(): String = "$"
}
