package com.xyq.livetranslate

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class WebPageReaderClientTest {
    private lateinit var server: MockWebServer
    private val publicDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (hostname == "localhost") {
                Dns.SYSTEM.lookup(hostname)
            } else {
                listOf(InetAddress.getByAddress(hostname, byteArrayOf(8, 8, 8, 8)))
            }
    }
    private val client = OkHttpClient.Builder().dns(publicDns).build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `reads public page through bounded Jina json response`() {
        server.enqueue(
            jsonResponse(
                """
                {
                  "code": 200,
                  "data": {
                    "title": "陌生站点的直播页面",
                    "description": "节目介绍",
                    "url": "https://media.example/watch/42",
                    "text": "主播正在讨论日语游戏术语与角色背景。",
                    "httpStatus": 200
                  }
                }
                """.trimIndent(),
            ),
        )

        val result = WebPageReaderClient.fetch(
            "https://media.example/watch/42?room=jp",
            client,
            server.url("/"),
        )

        assertEquals("陌生站点的直播页面", result.title)
        assertEquals("节目介绍", result.description)
        assertEquals("主播正在讨论日语游戏术语与角色背景。", result.content)
        val request = server.takeRequest()
        assertEquals("/https://media.example/watch/42?room=jp", request.path)
        assertEquals("application/json", request.getHeader("Accept"))
        assertEquals("text", request.getHeader("X-Respond-With"))
        assertEquals("3000", request.getHeader("X-Max-Tokens"))
        assertEquals("text", request.getHeader("X-Retain-Links"))
        assertEquals("alt", request.getHeader("X-Retain-Images"))
        assertEquals("text", request.getHeader("X-Retain-Media"))
    }

    @Test
    fun `rejects private credentialed and nonstandard target urls`() {
        listOf(
            "http://127.0.0.1/private",
            "http://169.254.169.254/latest/meta-data",
            "http://192.168.1.8/video",
            "http://[::1]/video",
            "https://localhost/video",
            "https://router/video",
            "https://nas.home/video",
            "https://user:password@example.com/video",
            "https://example.com:8443/video",
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                WebPageReaderClient.validateTargetUrl(url)
            }
        }

        val privateDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                listOf(InetAddress.getByAddress(hostname, byteArrayOf(10, 0, 0, 8)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WebPageReaderClient.validateTargetUrl("https://intranet.company/video", privateDns)
        }

        assertEquals(
            "https://media.example/watch",
            WebPageReaderClient.validateTargetUrl(
                "https://media.example/watch",
                publicDns,
            ).toString(),
        )
    }

    @Test
    fun `surfaces reader rate limit blocked target and blank content`() {
        server.enqueue(MockResponse().setResponseCode(429))
        val rateLimit = assertThrows(IllegalStateException::class.java) {
            WebPageReaderClient.fetch("https://media.example/watch", client, server.url("/"))
        }
        assertTrue(rateLimit.message.orEmpty().contains("频繁"))

        server.enqueue(
            jsonResponse(
                """{"code":200,"data":{"title":"blocked","text":"denied","httpStatus":403}}""",
            ),
        )
        val blocked = assertThrows(IllegalStateException::class.java) {
            WebPageReaderClient.fetch("https://media.example/watch", client, server.url("/"))
        }
        assertTrue(blocked.message.orEmpty().contains("HTTP 403"))

        server.enqueue(
            jsonResponse(
                """{"code":200,"data":{"title":"empty","text":"   ","httpStatus":200}}""",
            ),
        )
        val blank = assertThrows(IllegalStateException::class.java) {
            WebPageReaderClient.fetch("https://media.example/watch", client, server.url("/"))
        }
        assertTrue(blank.message.orEmpty().contains("没有提取到有效内容"))

        server.enqueue(
            jsonResponse(
                """{"code":200,"data":{"title":"redirected","url":"http://127.0.0.1/private","text":"sensitive","httpStatus":200}}""",
            ),
        )
        val redirected = assertThrows(IllegalStateException::class.java) {
            WebPageReaderClient.fetch("https://media.example/watch", client, server.url("/"))
        }
        assertTrue(redirected.message.orEmpty().contains("不安全重定向"))
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)
}
