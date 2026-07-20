package com.xyq.livetranslate

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VideoMetadataClientTest {
    private lateinit var server: MockWebServer
    private lateinit var endpoints: VideoMetadataEndpoints
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        endpoints = VideoMetadataEndpoints(
            youtubeOEmbed = server.url("/youtube"),
            bilibiliVideo = server.url("/bilibili-video"),
            bilibiliLiveRoom = server.url("/bilibili-live"),
            bilibiliLiveUser = server.url("/bilibili-user"),
            twitchGraphQl = server.url("/twitch"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `recognizes and normalizes supported platform links`() {
        val cases = listOf(
            "https://youtu.be/demo123?t=10" to
                ParsedVideoLink(
                    VideoLinkKind.YOUTUBE,
                    "demo123",
                    "https://www.youtube.com/watch?v=demo123",
                ),
            "https://m.bilibili.com/video/BV1xx411c7mD?p=2" to
                ParsedVideoLink(
                    VideoLinkKind.BILIBILI_VIDEO,
                    "BV1xx411c7mD",
                    "https://www.bilibili.com/video/BV1xx411c7mD",
                ),
            "https://live.bilibili.com/22603245?broadcast_type=0" to
                ParsedVideoLink(
                    VideoLinkKind.BILIBILI_LIVE,
                    "22603245",
                    "https://live.bilibili.com/22603245",
                ),
            "https://www.twitch.tv/videos/2823359178?t=1h" to
                ParsedVideoLink(
                    VideoLinkKind.TWITCH_VIDEO,
                    "2823359178",
                    "https://www.twitch.tv/videos/2823359178",
                ),
            "https://clips.twitch.tv/ExampleClip" to
                ParsedVideoLink(
                    VideoLinkKind.TWITCH_CLIP,
                    "ExampleClip",
                    "https://clips.twitch.tv/ExampleClip",
                ),
            "https://www.twitch.tv/SomeChannel" to
                ParsedVideoLink(
                    VideoLinkKind.TWITCH_CHANNEL,
                    "SomeChannel",
                    "https://www.twitch.tv/SomeChannel",
                ),
        )

        cases.forEach { (input, expected) ->
            assertEquals(expected, VideoMetadataClient.parseLink(input))
        }
    }

    @Test
    fun `extracts url from copied share text`() {
        val parsed = VideoMetadataClient.parseLink(
            "【分享视频】标题 https://www.bilibili.com/video/av170001?p=1。",
        )

        assertEquals(VideoLinkKind.BILIBILI_VIDEO, parsed.kind)
        assertEquals("av170001", parsed.id)
        assertEquals("https://www.bilibili.com/video/av170001", parsed.normalizedUrl)
    }

    @Test
    fun `rejects lookalike and unsupported hosts`() {
        assertThrows(IllegalStateException::class.java) {
            VideoMetadataClient.parseLink("https://evilyoutube.com/watch?v=demo")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoMetadataClient.parseLink("https://www.twitch.tv/directory")
        }
    }

    @Test
    fun `rejects unsafe short links and malformed numeric ids`() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoMetadataClient.parseLink("http://b23.tv/example")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoMetadataClient.parseLink("https://live.bilibili.com/22603245evil")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoMetadataClient.parseLink("https://www.twitch.tv/videos/2823359178evil")
        }
    }

    @Test
    fun `validates every Bilibili short link redirect before following it`() {
        val current = "https://b23.tv/example".toHttpUrl()

        val video = VideoMetadataClient.validateBilibiliRedirect(
            current,
            "https://www.bilibili.com/video/BV1xx411c7mD?p=1",
        )
        assertEquals(VideoLinkKind.BILIBILI_VIDEO, video.resolvedLink?.kind)
        assertEquals("BV1xx411c7mD", video.resolvedLink?.id)

        val nextShort = VideoMetadataClient.validateBilibiliRedirect(current, "/next")
        assertEquals("https://b23.tv/next", nextShort.nextUrl.toString())
        assertEquals(null, nextShort.resolvedLink)

        assertThrows(IllegalArgumentException::class.java) {
            VideoMetadataClient.validateBilibiliRedirect(
                current,
                "http://127.0.0.1/private",
            )
        }
        assertThrows(IllegalStateException::class.java) {
            VideoMetadataClient.validateBilibiliRedirect(
                current,
                "https://example.com/private",
            )
        }
    }

    @Test
    fun `surfaces platform http api json and graphql errors`() {
        server.enqueue(MockResponse().setResponseCode(503))
        assertThrows(IllegalStateException::class.java) {
            VideoMetadataClient.fetch("https://youtu.be/demo123", client, endpoints)
        }

        server.enqueue(jsonResponse("{not-json"))
        assertThrows(IllegalStateException::class.java) {
            VideoMetadataClient.fetch("https://youtu.be/demo123", client, endpoints)
        }

        server.enqueue(jsonResponse("""{"code":-404,"message":"啥都木有"}"""))
        assertThrows(IllegalStateException::class.java) {
            VideoMetadataClient.fetch(
                "https://www.bilibili.com/video/BV1xx411c7mD",
                client,
                endpoints,
            )
        }

        server.enqueue(jsonResponse("""{"errors":[{"message":"content unavailable"}]}"""))
        assertThrows(IllegalStateException::class.java) {
            VideoMetadataClient.fetch("https://www.twitch.tv/demolive", client, endpoints)
        }
    }

    @Test
    fun `fetches YouTube oEmbed metadata`() {
        server.enqueue(
            jsonResponse(
                """{"title":"配信タイトル","author_name":"Demo Channel"}""",
            ),
        )

        val result = VideoMetadataClient.fetch(
            "https://youtu.be/demo123",
            client,
            endpoints,
        )

        assertEquals(VideoPlatform.YOUTUBE, result.platform)
        assertEquals("配信タイトル", result.title)
        assertEquals("Demo Channel", result.authorName)
        val request = server.takeRequest()
        assertEquals("/youtube", request.requestUrl?.encodedPath)
        assertEquals("json", request.requestUrl?.queryParameter("format"))
        assertEquals(result.url, request.requestUrl?.queryParameter("url"))
    }

    @Test
    fun `fetches Bilibili video metadata and description`() {
        server.enqueue(
            jsonResponse(
                """
                {
                  "code": 0,
                  "data": {
                    "bvid": "BV1xx411c7mD",
                    "title": "字幕君交流场所",
                    "tname": "日常",
                    "desc": "  本期讨论字幕与翻译。  ",
                    "owner": {"name": "碧诗"}
                  }
                }
                """.trimIndent(),
            ),
        )

        val result = VideoMetadataClient.fetch(
            "https://www.bilibili.com/video/BV1xx411c7mD",
            client,
            endpoints,
        )

        assertEquals(VideoPlatform.BILIBILI, result.platform)
        assertEquals("字幕君交流场所", result.title)
        assertEquals("碧诗", result.authorName)
        assertEquals("日常", result.category)
        assertEquals("本期讨论字幕与翻译。", result.description)
        assertEquals("BV1xx411c7mD", server.takeRequest().requestUrl?.queryParameter("bvid"))
    }

    @Test
    fun `fetches Bilibili live room and anchor metadata`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
                "/bilibili-live" -> jsonResponse(
                    """
                    {
                      "code": 0,
                      "data": {
                        "room_id": 22603245,
                        "uid": 1265680561,
                        "title": "雑談配信",
                        "area_name": "虚拟主播"
                      }
                    }
                    """.trimIndent(),
                )
                "/bilibili-user" -> jsonResponse(
                    """{"code":0,"data":{"info":{"uname":"测试主播"}}}""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }

        val result = VideoMetadataClient.fetch(
            "https://live.bilibili.com/22603245",
            client,
            endpoints,
        )

        assertEquals(VideoPlatform.BILIBILI, result.platform)
        assertEquals("雑談配信", result.title)
        assertEquals("测试主播", result.authorName)
        assertEquals("虚拟主播", result.category)
        assertEquals("https://live.bilibili.com/22603245", result.url)
    }

    @Test
    fun `fetches Twitch channel vod and clip through public web GraphQL`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = JSONObject(request.body.readUtf8())
                return when (body.getString("operationName")) {
                    "ChannelMetadata" -> jsonResponse(
                        """
                        {"data":{"user":{"displayName":"DemoLive","description":"日英双语直播", "stream":{"title":"歌回","game":{"name":"Music"}}}}}
                        """.trimIndent(),
                    )
                    "VideoMetadata" -> jsonResponse(
                        """
                        {"data":{"video":{"title":"VOD 标题","owner":{"displayName":"DemoLive"},"game":{"displayName":"Just Chatting"}}}}
                        """.trimIndent(),
                    )
                    "ClipMetadata" -> jsonResponse(
                        """
                        {"data":{"clip":{"title":"Clip 标题","broadcaster":{"displayName":"DemoLive"},"game":{"displayName":"Music"}}}}
                        """.trimIndent(),
                    )
                    else -> MockResponse().setResponseCode(400)
                }
            }
        }

        val channel = VideoMetadataClient.fetch("https://www.twitch.tv/demolive", client, endpoints)
        val vod = VideoMetadataClient.fetch("https://www.twitch.tv/videos/123456", client, endpoints)
        val clip = VideoMetadataClient.fetch("https://clips.twitch.tv/ExampleClip", client, endpoints)

        assertEquals("歌回", channel.title)
        assertEquals("日英双语直播", channel.description)
        assertEquals("VOD 标题", vod.title)
        assertEquals("Just Chatting", vod.category)
        assertEquals("Clip 标题", clip.title)
        assertEquals("DemoLive", clip.authorName)
        repeat(3) {
            assertTrue(server.takeRequest().getHeader("Client-ID").orEmpty().isNotBlank())
        }
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)
}
