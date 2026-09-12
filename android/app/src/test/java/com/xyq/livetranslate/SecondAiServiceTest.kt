package com.xyq.livetranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class SecondAiServiceTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // ---------- SettingsStore 域名与 Key 校验 ----------

    @Test
    fun isOfficialGeminiEndpointValidatesHostPrecisely() {
        assertTrue(SettingsStore.isOfficialGeminiEndpoint("generativelanguage.googleapis.com"))
        assertTrue(SettingsStore.isOfficialGeminiEndpoint("https://generativelanguage.googleapis.com"))
        assertTrue(SettingsStore.isOfficialGeminiEndpoint("wss://generativelanguage.googleapis.com"))
        assertTrue(SettingsStore.isOfficialGeminiEndpoint("https://generativelanguage.googleapis.com/v1beta"))
        assertTrue(SettingsStore.isOfficialGeminiEndpoint("")) // 默认留空视为主机名默认

        // 恶意或第三方域名不能误判为官方
        assertFalse(SettingsStore.isOfficialGeminiEndpoint("evilgoogleapis.com"))
        assertFalse(SettingsStore.isOfficialGeminiEndpoint("https://evilgoogleapis.com"))
        assertFalse(SettingsStore.isOfficialGeminiEndpoint("https://generativelanguage.googleapis.com.attacker.com"))
        assertFalse(SettingsStore.isOfficialGeminiEndpoint("https://api.openai.com/v1"))
        assertFalse(SettingsStore.isOfficialGeminiEndpoint("https://my-proxy.com/generativelanguage.googleapis.com"))
    }

    @Test
    fun extractFirstApiKeyReturnsFirstNonEmptyKey() {
        assertEquals("key1", SettingsStore.extractFirstApiKey("key1,key2,key3"))
        assertEquals("keyA", SettingsStore.extractFirstApiKey("   keyA   ,   keyB  "))
        assertEquals("single_key", SettingsStore.extractFirstApiKey("single_key"))
        assertEquals("", SettingsStore.extractFirstApiKey(""))
        assertEquals("", SettingsStore.extractFirstApiKey("  ,  ,  "))
    }

    // ---------- SettingsStore 迁移与凭据隔离 ----------

    @Test
    fun migrateSecondAiSettingsOfficialGeminiPreserved() {
        val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        sp.edit()
            .putString("secondAiFormat", "gemini")
            .putString("secondAiBaseUrl", "https://generativelanguage.googleapis.com")
            .putString("secondAiModel", "models/gemini-2.5-flash")
            .commit()

        SettingsStore.migrateSecondAiSettingsIfNeeded(context)

        assertEquals(SettingsStore.SERVICE_GEMINI, SettingsStore.secondAiService(context))
        assertEquals("https://generativelanguage.googleapis.com", SettingsStore.secondAiGeminiBaseUrl(context))
        assertEquals("models/gemini-2.5-flash", SettingsStore.secondAiGeminiModel(context))
    }

    @Test
    fun migrateSecondAiSettingsCustomPreservedWithoutInjectingFakeModel() {
        val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        sp.edit()
            .putString("secondAiFormat", "openai")
            .putString("secondAiBaseUrl", "https://api.custom-proxy.com/v1")
            .putString("secondAiModel", "custom-llm-model")
            .commit()

        SettingsStore.migrateSecondAiSettingsIfNeeded(context)

        assertEquals(SettingsStore.SERVICE_CUSTOM, SettingsStore.secondAiService(context))
        assertEquals("openai", SettingsStore.secondAiCustomFormat(context))
        assertEquals("https://api.custom-proxy.com/v1", SettingsStore.secondAiCustomBaseUrl(context))
        assertEquals("custom-llm-model", SettingsStore.secondAiCustomModel(context))
    }

    @Test
    fun customServiceDoesNotFallbackToGeminiWhenEmpty() {
        SettingsStore.saveSecondAiService(context, SettingsStore.SERVICE_CUSTOM)
        SettingsStore.saveSecondAiCustomBaseUrl(context, "")
        SettingsStore.saveSecondAiCustomModel(context, "")

        // 自定义服务字段为空时不应偷换成 Google 官方地址或模型
        assertEquals("", SettingsStore.secondAiBaseUrl(context))
        assertEquals("", SettingsStore.secondAiModel(context))
    }

    @Test
    fun zenServiceReturnsFixedPublicCredentialsAndBaseUrl() {
        SettingsStore.saveSecondAiService(context, SettingsStore.SERVICE_OPENCODE_ZEN)

        assertEquals(OpenCodeZenCatalog.BASE_URL, SettingsStore.secondAiBaseUrl(context))
        assertEquals(OpenCodeZenCatalog.PUBLIC_KEY, SettingsStore.secondAiApiKey(context))
        assertEquals("openai", SettingsStore.secondAiFormat(context))
        assertEquals(OpenCodeZenCatalog.CANDIDATE_MODEL, SettingsStore.secondAiModel(context))

        // 试图设置非白名单模型会自动拒绝或重置为 CANDIDATE_MODEL
        SettingsStore.saveSecondAiZenModel(context, "paid-gpt-4o")
        assertEquals(OpenCodeZenCatalog.CANDIDATE_MODEL, SettingsStore.secondAiZenModel(context))
    }

    // ---------- OpenCodeZenCatalog 白名单与错误本地化 ----------

    @Test
    fun zenFilterAvailableFreeModelsIntersectsCorrectly() {
        val remote = listOf("big-pickle", "gpt-4o", "claude-3-5-sonnet")
        val filtered = OpenCodeZenCatalog.filterAvailableFreeModels(remote)
        assertEquals(listOf("big-pickle"), filtered)
    }

    @Test
    fun zenFilterAvailableFreeModelsNegativeTestReturnsEmptyWhenNoMatch() {
        // 负例测试：远端若下线 big-pickle 或仅返回付费模型，绝不能 fallback 注入候选模型
        val remote = listOf("gpt-4o", "claude-3-5-sonnet", "deepseek-chat")
        val filtered = OpenCodeZenCatalog.filterAvailableFreeModels(remote)
        assertTrue("交集为空时必须返回空列表", filtered.isEmpty())

        val emptyRemote = emptyList<String>()
        assertTrue("远端为空时必须返回空列表", OpenCodeZenCatalog.filterAvailableFreeModels(emptyRemote).isEmpty())
    }

    @Test
    fun zenIsAllowedFreeModelEnforcesAllowlist() {
        assertTrue(OpenCodeZenCatalog.isAllowedFreeModel("big-pickle"))
        assertFalse(OpenCodeZenCatalog.isAllowedFreeModel("gpt-4o"))
        assertFalse(OpenCodeZenCatalog.isAllowedFreeModel("claude-3-5-sonnet"))
        assertFalse(OpenCodeZenCatalog.isAllowedFreeModel("big-pickle-pro"))
        assertFalse(OpenCodeZenCatalog.isAllowedFreeModel(""))
    }

    @Test
    fun zenLocalizeErrorExplainsClientRestriction() {
        val err1 = """{"type":"error","error":{"type":"MissingSessionID","message":"Error from provider (Console): OpenCode's free tier can only be used in OpenCode"}}"""
        val localized1 = OpenCodeZenCatalog.localizeZenError(err1)
        assertEquals("当前免费接口限制仅 OpenCode 客户端使用，本 App 未验证可用；可重新测试或切换服务", localized1)

        val err2 = "MissingSessionID: Please provide session id"
        val localized2 = OpenCodeZenCatalog.localizeZenError(err2)
        assertEquals("当前免费接口限制仅 OpenCode 客户端使用，本 App 未验证可用；可重新测试或切换服务", localized2)

        val errOther = "Connection refused"
        assertEquals("Connection refused", OpenCodeZenCatalog.localizeZenError(errOther))
    }

    // ---------- AiTextClient 自检 JSON 契约与脱敏 ----------

    @Test
    fun aiTextProbeSucceedsWithValidContract() {
        val contentJson = """{"sessionContext":"自检通过，测试背景正常","note":"分析服务正常"}"""
        val openAiEnvelope = """{"choices":[{"message":{"content":${org.json.JSONObject.quote(contentJson)}}}]}"""
        server.enqueue(MockResponse().setBody(openAiEnvelope))

        val result = AiTextClient.probe(
            baseUrl = server.url("/").toString(),
            apiKey = "test-key",
            model = "test-model",
            format = AiTextClient.Format.OPENAI,
        )
        assertEquals("分析服务正常", result)
    }

    @Test
    fun aiTextProbeRejectsEmptyOrMissingContractFields() {
        val emptyContentJson = """{"otherField":"something"}"""
        val openAiEnvelope = """{"choices":[{"message":{"content":${org.json.JSONObject.quote(emptyContentJson)}}}]}"""
        server.enqueue(MockResponse().setBody(openAiEnvelope))

        try {
            AiTextClient.probe(
                baseUrl = server.url("/").toString(),
                apiKey = "test-key",
                model = "test-model",
                format = AiTextClient.Format.OPENAI,
            )
            fail("Expected exception for missing contract fields")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("未包含有效") == true)
        }
    }

    @Test
    fun aiTextProbeGeminiFormatSucceeds() {
        val contentJson = """{"sessionContext":"Gemini自检通过","note":"Gemini正常"}"""
        val geminiEnvelope = """{"candidates":[{"content":{"parts":[{"text":${org.json.JSONObject.quote(contentJson)}}]}}]}"""
        server.enqueue(MockResponse().setBody(geminiEnvelope))

        val result = AiTextClient.probe(
            baseUrl = server.url("/").toString(),
            apiKey = "test-key",
            model = "models/gemini-2.5-flash",
            format = AiTextClient.Format.GEMINI,
        )
        assertEquals("Gemini正常", result)
    }

    @Test
    fun zenAccessRestrictionSurfacesAccurateMessageNotKeyAdvice() {
        // 真实实测：OpenCode Zen 对 public 接入点返回 400 MissingSessionID。
        // 若上游错误体被丢弃，UI 只能显示「请检查 API Key」——而 Zen 根本不要用户填 Key，属误导。
        val zenError = """{"type":"error","error":{"type":"MissingSessionID",""" +
            """"message":"Error from provider (Console): OpenCode's free tier can only be used in OpenCode"}}"""
        server.enqueue(MockResponse().setResponseCode(400).setBody(zenError))

        val failure = runCatching {
            AiTextClient.probe(
                baseUrl = server.url("/").toString(),
                apiKey = OpenCodeZenCatalog.PUBLIC_KEY,
                model = OpenCodeZenCatalog.CANDIDATE_MODEL,
                format = AiTextClient.Format.OPENAI,
            )
        }.exceptionOrNull()

        val raw = failure?.message.orEmpty()
        assertTrue("上游错误类型必须保留，否则无法本地化：$raw", raw.contains("MissingSessionID"))
        assertFalse("不得再给出误导性的 Key 建议：$raw", raw.contains("请检查 API Key 和服务地址"))

        val localized = OpenCodeZenCatalog.localizeZenError(raw)
        assertTrue("应落到 Zen 限制专用文案：$localized", localized.contains("未验证可用"))
    }

    @Test
    fun upstreamErrorExtractionDropsBodyButKeepsDiagnosis() {
        val body = """{"error":{"status":"PERMISSION_DENIED","message":"API key not valid","details":[1,2,3]}}"""
        val extracted = AiTextClient.extractUpstreamError(body)
        assertTrue(extracted.contains("PERMISSION_DENIED"))
        assertTrue(extracted.contains("API key not valid"))
        assertFalse("不得回抛整个响应体", extracted.contains("details"))
        assertEquals("", AiTextClient.extractUpstreamError("<html>502 Bad Gateway</html>"))
    }

    @Test
    fun sanitizeErrorMasksCredentials() {
        val msgWithKey = "Request failed at https://example.com/api?key=AIzaSyD_SecretKey12345678901234567890"
        val sanitized1 = AiTextClient.sanitizeError(msgWithKey)
        assertFalse(sanitized1.contains("AIzaSyD_SecretKey12345678901234567890"))
        assertTrue(sanitized1.contains("key=***"))

        val msgWithBearer = "HTTP 401 Unauthorized: Bearer sk-proj-123456789abcdef"
        val sanitized2 = AiTextClient.sanitizeError(msgWithBearer)
        assertFalse(sanitized2.contains("sk-proj-123456789abcdef"))
        assertTrue(sanitized2.contains("Bearer ***"))
    }
}
