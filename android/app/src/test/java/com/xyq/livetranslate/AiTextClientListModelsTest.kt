package com.xyq.livetranslate

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            credentialMode = ApiCredentialMode.QUERY_API_KEY,
        )
        assertEquals(listOf("models/gemini-3-flash-001", "models/gemini-3-pro-001"), models)
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
            credentialMode = ApiCredentialMode.QUERY_API_KEY,
        )
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), models)
    }

    @Test
    fun gatewayPathUsesGatewayPrefixForFriendBearerMode() {
        server.enqueue(MockResponse().setBody("""{"models":[{"name":"models/gemini-3.5-flash","supportedGenerationMethods":["generateContent"]}]}"""))
        AiTextClient.listModels(
            baseUrl = server.url("/").toString(),
            apiKey = "token",
            format = AiTextClient.Format.GEMINI,
            credentialMode = ApiCredentialMode.BEARER_TOKEN,
            deviceId = "device-id",
            requestSignatureProvider = { _, _, _, _ ->
                mapOf("X-Device-Signature" to "sig")
            },
        )
        val request = server.takeRequest()
        // 好友网关路径必须走 /gateway/v1beta/models，不能直连官方端点
        assertEquals("/gateway/v1beta/models", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        assertEquals("device-id", request.getHeader("X-Device-ID"))
        assertEquals("sig", request.getHeader("X-Device-Signature"))
    }

    @Test
    fun emptyModelsReturnsEmptyList() {
        server.enqueue(MockResponse().setBody("""{"models":[]}"""))
        val models = AiTextClient.listModels(
            baseUrl = server.url("/").toString(),
            apiKey = "test-key",
            format = AiTextClient.Format.GEMINI,
            credentialMode = ApiCredentialMode.QUERY_API_KEY,
        )
        assertTrue(models.isEmpty())
    }
}
