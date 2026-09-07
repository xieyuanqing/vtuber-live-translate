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
