package com.xyq.livetranslate

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FriendGatewayClientTest {
    private lateinit var server: MockWebServer

    private val identity = object : FriendGatewayIdentity {
        override fun publicKey(): String = "public-key-for-contract-test"
        override fun deviceId(): String = "device-id-for-contract-test"
        override fun signBinding(challengeId: String, nonce: String, deviceId: String): String =
            "binding-signature-$challengeId"

        override fun signRequest(
            method: String,
            path: String,
            body: ByteArray,
            token: String,
        ): Map<String, String> = mapOf(
            "X-Device-ID" to deviceId(),
            "X-Device-Time" to "1234567890",
            "X-Device-Nonce" to "nonce-for-contract-test",
            "X-Device-Signature" to "request-signature",
        )
    }

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
    fun bindAndStatusUseChallengeAndSignedContract() {
        server.enqueue(
            MockResponse().setBody(
                """{"challengeId":"ch_test","nonce":"nonce_test","deviceId":"device-id-for-contract-test","expiresAt":9999999999}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"accessToken":"ltg_test","tokenExpiresAt":9999999999,"label":"好友 A"}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"bound":true,"label":"好友 A","tokenExpiresAt":9999999999,"usage":{"textRequests":2,"liveSessions":3}}"""
            )
        )
        val client = FriendGatewayClient(identity, server.url("/").toString())

        val binding = client.bind("LT-AAAAA-BBBBB-CCCCC-DDDDD-EEEEEE", "test")
        assertEquals("ltg_test", binding.accessToken)
        assertEquals("好友 A", binding.label)
        val challenge = server.takeRequest()
        assertEquals("/api/v1/bind/challenge", challenge.path)
        assertTrue(challenge.body.readUtf8().contains("devicePublicKey"))
        val complete = server.takeRequest()
        assertEquals("/api/v1/bind", complete.path)
        assertTrue(complete.body.readUtf8().contains("binding-signature-ch_test"))

        val status = client.status(binding.accessToken)
        assertEquals(2, status.textRequests)
        assertEquals(3, status.liveSessions)
        val statusRequest = server.takeRequest()
        assertEquals("Bearer ltg_test", statusRequest.getHeader("Authorization"))
        assertEquals(identity.deviceId(), statusRequest.getHeader("X-Device-ID"))
        assertEquals("request-signature", statusRequest.getHeader("X-Device-Signature"))
    }

    @Test
    fun geminiBearerModeNeverPlacesTokenInUrlAndNormalizesModel() {
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"text":"{\"ok\":true}"}]}}]}"""
            )
        )

        val result = AiTextClient.generate(
            systemPrompt = "system",
            userPrompt = "user",
            baseUrl = server.url("gateway").toString().trimEnd('/'),
            apiKey = "ltg_secret_token",
            model = "models/gemini-2.5-flash",
            format = AiTextClient.Format.GEMINI,
            credentialMode = ApiCredentialMode.BEARER_TOKEN,
            deviceId = identity.deviceId(),
            requestSignatureProvider = identity::signRequest,
        )

        assertTrue(result.getBoolean("ok"))
        val request = server.takeRequest()
        assertEquals(
            "/gateway/v1beta/models/gemini-2.5-flash:generateContent",
            request.path,
        )
        assertFalse(request.path.orEmpty().contains("ltg_secret_token"))
        assertEquals("Bearer ltg_secret_token", request.getHeader("Authorization"))
        assertEquals(identity.deviceId(), request.getHeader("X-Device-ID"))
        assertEquals("request-signature", request.getHeader("X-Device-Signature"))
        assertTrue(request.body.readUtf8().contains("googleSearch"))
    }
}
