package com.xyq.livetranslate

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GeminiLiveProbeTest {
    private lateinit var server: MockWebServer

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
    fun buildWebSocketUrlEncodesApiKeyAndPath() {
        val url = GeminiLiveClient.buildWebSocketUrl(
            baseUrl = "https://generativelanguage.googleapis.com",
            apiKey = "AIzaSyTest_Key+Special/123",
        )
        val httpUrl = url.toHttpUrlOrNull()
        assertNotNull(httpUrl)
        assertEquals("generativelanguage.googleapis.com", httpUrl?.host)
        assertEquals(GeminiLiveClient.WS_PATH, httpUrl?.encodedPath)
        assertEquals("AIzaSyTest_Key+Special/123", httpUrl?.queryParameter("key"))
    }

    @Test
    fun buildWebSocketUrlExtractsFirstKeyFromMultiple() {
        val url = GeminiLiveClient.buildWebSocketUrl(
            baseUrl = "wss://generativelanguage.googleapis.com",
            apiKey = "first-key, second-key, third-key",
        )
        val httpUrl = url.toHttpUrlOrNull()
        assertEquals("first-key", httpUrl?.queryParameter("key"))
    }

    @Test
    fun buildSetupJsonContainsLiveModelAndAudioModalities() {
        val setupStr = GeminiLiveClient.buildSetupJson(
            model = GeminiLiveClient.MODEL,
            targetLang = "zh-CN",
            echoTargetLanguage = true,
            prompt = "Live translation prompt",
        )
        val json = JSONObject(setupStr)
        assertTrue(json.has("setup"))
        val setup = json.getJSONObject("setup")
        assertEquals(GeminiLiveClient.MODEL, setup.getString("model"))

        val genConfig = setup.getJSONObject("generationConfig")
        val modalities = genConfig.getJSONArray("responseModalities")
        assertEquals("AUDIO", modalities.getString(0))

        val transConfig = genConfig.getJSONObject("translationConfig")
        assertEquals("zh-CN", transConfig.getString("targetLanguageCode"))
        assertTrue(transConfig.getBoolean("echoTargetLanguage"))

        val sysInstruction = setup.getJSONObject("systemInstruction")
        val text = sysInstruction.getJSONArray("parts").getJSONObject(0).getString("text")
        assertEquals("Live translation prompt", text)
    }

    @Test
    fun probeLiveSucceedsOnSetupComplete() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"setupComplete":{}}""")
                }
            }),
        )

        val result = GeminiLiveClient.probeLive(
            baseUrl = server.url("/").toString(),
            apiKey = "test-live-key",
            timeoutMs = 3000L,
        )
        assertEquals("Live 模型连接通过（未测试音频翻译）", result)
    }

    @Test
    fun probeLiveFailsOnServerErrorResponse() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"error":{"code":400,"message":"Invalid setup"}}""")
                }
            }),
        )

        try {
            GeminiLiveClient.probeLive(
                baseUrl = server.url("/").toString(),
                apiKey = "test-live-key",
                timeoutMs = 3000L,
            )
            fail("Expected exception on error message")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("setup 错误") == true)
            assertFalse(e.message?.contains("test-live-key") == true)
        }
    }

    @Test
    fun probeLiveFailsOnServerClose() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.close(1008, "Policy violation")
                }
            }),
        )

        try {
            GeminiLiveClient.probeLive(
                baseUrl = server.url("/").toString(),
                apiKey = "test-live-key",
                timeoutMs = 3000L,
            )
            fail("Expected exception on server socket close")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("code=1008") == true)
            assertFalse(e.message?.contains("Policy violation") == true) // sanitized
            assertFalse(e.message?.contains("test-live-key") == true)
        }
    }

    @Test
    fun probeLiveFailsOnTimeout() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                // 不返回任何消息，模拟超时
            }),
        )

        try {
            GeminiLiveClient.probeLive(
                baseUrl = server.url("/").toString(),
                apiKey = "test-live-key",
                timeoutMs = 400L,
            )
            fail("Expected timeout exception")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("超时") == true)
        }
    }

    @Test
    fun probeLiveRejectsEmptyApiKey() {
        try {
            GeminiLiveClient.probeLive(
                baseUrl = server.url("/").toString(),
                apiKey = " ,  ",
                timeoutMs = 1000L,
            )
            fail("Expected exception for empty key")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("未填写 API Key") == true)
        }
    }

    private fun assertNotNull(obj: Any?) {
        assertTrue(obj != null)
    }
}
