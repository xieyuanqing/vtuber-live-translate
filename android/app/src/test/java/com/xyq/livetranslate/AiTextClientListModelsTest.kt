package com.xyq.livetranslate

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AiTextClientListModelsTest {
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
    fun geminiListFiltersToGenerateContentModels() {
        server.enqueue(
            MockResponse().setBody(
                """
                {"models":[
                  {"name":"models/gemini-3-flash-001","supportedGenerationMethods":["generateContent"]},
                  {"name":"models/embedding-001","supportedGenerationMethods":["embedContent"]},
                  {"name":"models/gemini-3-pro-001","supportedGenerationMethods":["generateContent","embedContent"]}
                ]}
                """.trimIndent(),
            ),
        )
        val models = AiTextClient.listModels(
            baseUrl = server.url("/").toString(),
            apiKey = "test-key",
            format = AiTextClient.Format.GEMINI,
        )
        assertEquals(listOf("models/gemini-3-flash-001", "models/gemini-3-pro-001"), models)
        val request = server.takeRequest()
        assertEquals("/v1beta/models?key=test-key", request.path)
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("X-Device-Signature"))
    }

    @Test
    fun openAiListReturnsDataIds() {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":[
                  {"id":"gpt-4o"},
                  {"id":"gpt-4o-mini"}
                ]}
                """.trimIndent(),
            ),
        )
        val models = AiTextClient.listModels(
            baseUrl = server.url("/").toString(),
            apiKey = "test-key",
            format = AiTextClient.Format.OPENAI,
        )
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), models)
        val request = server.takeRequest()
        assertEquals("/v1/models", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertNull(request.getHeader("X-Device-ID"))
    }

    @Test
    fun pastedVersionSegmentIsNotAppendedTwice() {
        // 反代地址常常已经写到 /v1 或 /v1beta，直接拼接会得到打不通的 /v1/v1/models。
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"gpt-4o"}]}"""))
        AiTextClient.listModels(
            baseUrl = server.url("/v1").toString(),
            apiKey = "test-key",
            format = AiTextClient.Format.OPENAI,
        )
        assertEquals("/v1/models", server.takeRequest().path)

        server.enqueue(MockResponse().setBody("""{"models":[]}"""))
        AiTextClient.listModels(
            baseUrl = server.url("/v1beta/models/").toString(),
            apiKey = "test-key",
            format = AiTextClient.Format.GEMINI,
        )
        assertEquals("/v1beta/models?key=test-key", server.takeRequest().path)
    }

    @Test
    fun normalizeBaseUrlOnlyTrimsTrailingVersionSegments() {
        assertEquals("https://proxy.test", AiTextClient.normalizeBaseUrl("https://proxy.test/"))
        assertEquals("https://proxy.test", AiTextClient.normalizeBaseUrl(" https://proxy.test/v1 "))
        assertEquals("https://proxy.test", AiTextClient.normalizeBaseUrl("https://proxy.test/v1beta/models"))
        // 路径中间的 v1 不是版本尾巴，不能动。
        assertEquals(
            "https://proxy.test/v1/relay",
            AiTextClient.normalizeBaseUrl("https://proxy.test/v1/relay/"),
        )
    }

    @Test
    fun emptyModelsReturnsEmptyList() {
        server.enqueue(MockResponse().setBody("""{"models":[]}"""))
        val models = AiTextClient.listModels(
            baseUrl = server.url("/").toString(),
            apiKey = "test-key",
            format = AiTextClient.Format.GEMINI,
        )
        assertTrue(models.isEmpty())
    }
}
