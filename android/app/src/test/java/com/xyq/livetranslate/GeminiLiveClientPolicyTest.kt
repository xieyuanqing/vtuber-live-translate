package com.xyq.livetranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeminiLiveClientPolicyTest {
    @Test
    fun gatewayCloseCodesClassifyTerminalFailures() {
        assertEquals(
            "好友服务器拒绝了当前实时配置",
            GeminiLiveClient.gatewayCloseReason(4400),
        )
        assertEquals(
            "好友测试资格失效，请重新绑定",
            GeminiLiveClient.gatewayCloseReason(4401),
        )
        assertEquals("好友测试额度已用完", GeminiLiveClient.gatewayCloseReason(4429))
        assertNull(GeminiLiveClient.gatewayCloseReason(1006))
    }

    @Test
    fun gatewayHttpCodesClassifyTerminalFailures() {
        assertEquals(
            "好友测试资格失效，请重新绑定",
            GeminiLiveClient.gatewayHttpFailureReason(403),
        )
        assertEquals("好友测试额度已用完", GeminiLiveClient.gatewayHttpFailureReason(429))
        assertNull(GeminiLiveClient.gatewayHttpFailureReason(null))
        assertNull(GeminiLiveClient.gatewayHttpFailureReason(503))
    }

    @Test
    fun websocketSigningPathAcceptsWssGatewayUrl() {
        val path = GeminiLiveClient.websocketEncodedPath(
            "wss://translate-test.994431.xyz/gateway/ws/" +
                "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        )

        assertEquals(
            "/gateway/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
            path,
        )
    }
}