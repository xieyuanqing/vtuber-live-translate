package com.xyq.livetranslate

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 通用文本 AI 客户端：支持 Gemini 原生格式和 OpenAI 兼容格式。
 * 同步调用（后台线程使用），输出强制 JSON 以便程序化解析。
 */
object AiTextClient {

    private const val TAG = "AiText"

    /** API 格式枚举 */
    enum class Format(val key: String) {
        GEMINI("gemini"),
        OPENAI("openai"),
        ;

        companion object {
            fun fromKey(key: String): Format =
                entries.firstOrNull { it.key == key } ?: GEMINI
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 发一条 system prompt + user prompt，期望模型返回 JSON 字符串。
     * 内部强制 JSON mode（Gemini：responseMimeType；OpenAI：response_format）。
     * 返回的 [JSONObject] 是模型输出的顶层 JSON 对象。
     */
    fun generate(
        systemPrompt: String,
        userPrompt: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        format: Format,
        enableSearch: Boolean = false,
    ): JSONObject {
        when (format) {
            Format.GEMINI -> return generateGemini(
                systemPrompt,
                userPrompt,
                baseUrl,
                apiKey,
                model,
                enableSearch,
            )
            Format.OPENAI -> return generateOpenAI(
                systemPrompt,
                userPrompt,
                baseUrl,
                apiKey,
                model,
            )
        }
    }

    /**
     * 用当前配置跑一次最小请求，确认 Key、地址、模型三者能真正跑通。
     * 走的是和资料整理完全相同的请求路径，所以能通过就代表分析功能可用。
     *
     * 验证 ContentContextAnalyzer 所需的 JSON keys/types（sessionContext, note），
     * 杜绝 optString(...).ifEmpty { "pong" } 假阳性。
     *
     * 调用方必须自行放到后台线程执行（同步阻塞）。成功返回一句可展示的短文本，
     * 失败抛 [Exception]，异常信息直接给用户看。
     */
    fun probe(
        baseUrl: String,
        apiKey: String,
        model: String,
        format: Format,
    ): String {
        val response = generate(
            systemPrompt = "你是连通性自检端点。只返回严格JSON，格式如下：{\"sessionContext\":\"自检通过\",\"note\":\"分析服务正常\"}",
            userPrompt = "请进行连通性自检并返回要求的JSON",
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            format = format,
            enableSearch = false,
        )
        val sessionContext = if (response.has("sessionContext") && !response.isNull("sessionContext")) {
            response.optString("sessionContext", "").trim()
        } else {
            ""
        }
        val note = if (response.has("note") && !response.isNull("note")) {
            response.optString("note", "").trim()
        } else {
            ""
        }

        if (sessionContext.isEmpty() && note.isEmpty()) {
            error("AI 返回的 JSON 未包含有效的 sessionContext 或 note 字段")
        }
        return note.ifEmpty { sessionContext }.take(50)
    }

    /**
     * 从上游错误响应体里取出可判别的错误类型与说明。
     *
     * 只保留结构化字段（OpenAI 兼容的 error.type / error.message，Gemini 的 error.status /
     * error.message），不回抛整个响应体：调用方需要这些字段才能把「服务自身的接入限制」
     * 和「用户填错 Key」区分开，否则只能显示误导性的通用提示。
     */
    internal fun extractUpstreamError(body: String): String {
        if (body.isBlank()) return ""
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull() ?: return ""
        val type = error.optString("type").ifEmpty { error.optString("status") }
        val message = error.optString("message")
        return listOf(type, message).filter { it.isNotEmpty() }.joinToString("：").take(160)
    }

    /** 对外暴露的错误信息脱敏：不暴露 API Key 与冗余服务端响应体 */
    fun sanitizeError(message: String?): String {
        if (message.isNullOrBlank()) return "请求失败"
        return message
            .replace(Regex("(?i)key=[^&\\s]+"), "key=***")
            .replace(Regex("(?i)bearer\\s+[^\\s]+"), "Bearer ***")
            .replace(Regex("AIza[0-9A-Za-z-_]{35}"), "AIza***")
            .take(120)
    }

    /**
     * 统一处理用户粘贴的服务地址。
     *
     * 反代地址常常已经带上了 `/v1`、`/v1beta` 甚至 `/v1/models`，
     * 直接拼接会得到 `…/v1/v1/chat/completions` 这种打不通的路径。
     * 这里把末尾的版本段和斜杠去掉，让能自动处理的格式问题由 App 承担。
     */
    internal fun normalizeBaseUrl(baseUrl: String): String {
        var url = baseUrl.trim().trimEnd('/')
        val versionTails = listOf("/v1beta/models", "/v1/models", "/v1beta", "/v1")
        var trimmed = true
        while (trimmed) {
            trimmed = false
            for (tail in versionTails) {
                if (url.endsWith(tail, ignoreCase = true)) {
                    url = url.dropLast(tail.length).trimEnd('/')
                    trimmed = true
                }
            }
        }
        return url
    }

    /**
     * 拉取当前配置下可用的文本模型列表，供设置页选择。
     *
     * - Gemini 原生格式：请求 [baseUrl]/v1beta/models，过滤掉不支持 generateContent 的项。
     * - OpenAI 兼容格式：请求 [baseUrl]/v1/models，返回 data[].id。
     *
     * 调用方必须自行放到后台线程执行（同步阻塞）。
     * 失败时抛 [Exception]，调用方捕获后回退为手动输入并提示。
     */
    fun listModels(
        baseUrl: String,
        apiKey: String,
        format: Format,
    ): List<String> {
        val url = buildModelsUrl(baseUrl, format, apiKey)
        val req = Request.Builder()
            .url(url)
            .apply {
                if (format == Format.OPENAI) {
                    header("Authorization", "Bearer $apiKey")
                }
            }
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "listModels error ${resp.code}: ${body.take(300)}")
                error("拉取模型列表失败（${resp.code}）")
            }
            return parseModels(body, format)
        }
    }

    private fun buildModelsUrl(
        baseUrl: String,
        format: Format,
        apiKey: String,
    ): String = when (format) {
        Format.GEMINI -> normalizeBaseUrl(baseUrl) + "/v1beta/models?key=$apiKey"
        Format.OPENAI -> normalizeBaseUrl(baseUrl) + "/v1/models"
    }

    private fun parseModels(body: String, format: Format): List<String> {
        val root = JSONObject(body)
        return when (format) {
            Format.GEMINI -> {
                val arr = root.optJSONArray("models") ?: return emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                    val name = obj.optString("name", "")
                    val methods = obj.optJSONArray("supportedGenerationMethods") ?: org.json.JSONArray()
                    val supports = (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                    if (supports) name.ifEmpty { null } else null
                }
            }
            Format.OPENAI -> {
                val arr = root.optJSONArray("data") ?: return emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.optString("id", "")?.ifEmpty { null }
                }
            }
        }
    }

    // ---------- Gemini 原生 REST ----------

    private fun generateGemini(
        systemPrompt: String,
        userPrompt: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        enableSearch: Boolean = false,
    ): JSONObject {
        val modelId = model.removePrefix("models/")
        val url = normalizeBaseUrl(baseUrl) + "/v1beta/models/${modelId}:generateContent?key=$apiKey"

        val parts = org.json.JSONArray()
        parts.put(JSONObject().put("text", userPrompt))
        val contents = org.json.JSONArray()
        contents.put(
            JSONObject()
                .put("role", "user")
                .put("parts", parts)
        )

        val bodyObj = JSONObject()
            .put("contents", contents)
            .put(
                "generationConfig", JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("temperature", 0.2)
            )

        if (enableSearch) {
            // 可选开启 Google Search grounding，默认关闭以保障免费档与结构化 JSON 兼容
            bodyObj.put(
                "tools", org.json.JSONArray()
                    .put(JSONObject().put("googleSearch", JSONObject()))
            )
        }

        if (systemPrompt.isNotBlank()) {
            bodyObj.put(
                "systemInstruction",
                JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
        }

        val jsonMedia = "application/json; charset=utf-8".toMediaType()
        val bodyBytes = bodyObj.toString().toByteArray(Charsets.UTF_8)
        val req = Request.Builder()
            .url(url)
            .post(bodyBytes.toRequestBody(jsonMedia))
            .build()

        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "Gemini API error ${resp.code}: ${sanitizeError(body)}")
                val upstream = extractUpstreamError(body)
                error(
                    if (upstream.isEmpty()) {
                        "AI 服务返回错误 ${resp.code}，请检查 API Key 和模型 ID 是否正确"
                    } else {
                        "AI 服务返回错误 ${resp.code}：${sanitizeError(upstream)}"
                    }
                )
            }
            return parseGeminiResponse(body)
        }
    }

    private fun parseGeminiResponse(body: String): JSONObject {
        val root = JSONObject(body)
        val candidates = root.optJSONArray("candidates")
            ?: error("AI 返回格式异常：缺少 candidates")
        val first = candidates.optJSONObject(0)
            ?: error("AI 返回了空候选列表")
        val content = first.optJSONObject("content")
            ?: error("AI 返回格式异常：缺少 content")
        val parts = content.optJSONArray("parts")
            ?: error("AI 返回格式异常：缺少 parts")
        val text = parts.optJSONObject(0)?.optString("text", "")
            ?: error("AI 返回了空文本")
        return extractJsonObject(text)
    }

    // ---------- OpenAI 兼容 ----------

    private fun generateOpenAI(
        systemPrompt: String,
        userPrompt: String,
        baseUrl: String,
        apiKey: String,
        model: String,
    ): JSONObject {
        val url = normalizeBaseUrl(baseUrl) + "/v1/chat/completions"

        val messages = org.json.JSONArray()
        if (systemPrompt.isNotBlank()) {
            messages.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", systemPrompt)
            )
        }
        messages.put(
            JSONObject()
                .put("role", "user")
                .put("content", userPrompt)
        )

        val bodyObj = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.2)
            .put(
                "response_format",
                JSONObject().put("type", "json_object")
            )

        val jsonMedia = "application/json; charset=utf-8".toMediaType()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(bodyObj.toString().toRequestBody(jsonMedia))
            .build()

        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "OpenAI API error ${resp.code}: ${sanitizeError(body)}")
                val upstream = extractUpstreamError(body)
                error(
                    if (upstream.isEmpty()) {
                        "AI 服务返回错误 ${resp.code}，请检查 API Key 和服务地址是否正确"
                    } else {
                        "AI 服务返回错误 ${resp.code}：${sanitizeError(upstream)}"
                    }
                )
            }
            return parseOpenAIResponse(body)
        }
    }

    private fun parseOpenAIResponse(body: String): JSONObject {
        val root = JSONObject(body)
        val choices = root.optJSONArray("choices")
            ?: error("AI 返回格式异常：缺少 choices")
        val first = choices.optJSONObject(0)
            ?: error("AI 返回了空选择列表")
        val message = first.optJSONObject("message")
            ?: error("AI 返回格式异常：缺少 message")
        val text = message.optString("content", "")
            ?: error("AI 返回了空内容")
        return extractJsonObject(text)
    }

    // ---------- JSON 解析 ----------

    /**
     * 从模型返回的文本中提取顶层 JSON 对象。
     * 有时模型会在 JSON 外围包 markdown code fence（```json ... ```），先剥掉。
     */
    private fun extractJsonObject(text: String): JSONObject {
        var s = text.trim()
        // 剥掉 markdown code fence
        val fenceRe = Regex("""```(?:json)?\s*([\s\S]*?)\s*```""")
        val m = fenceRe.find(s)
        if (m != null) s = m.groupValues[1].trim()

        // 模型有时在 JSON 前面加了一两句说明，找第一个 { 或 [
        val brace = s.indexOf('{')
        val bracket = s.indexOf('[')
        val start = when {
            brace < 0 && bracket < 0 ->
                error("AI 未返回 JSON：${s.take(200)}")
            brace < 0 -> bracket
            bracket < 0 -> brace
            else -> minOf(brace, bracket)
        }
        s = s.substring(start).trim()

        // 需要顶层是对象（不是数组），确保 { 在 [
        if (bracket >= 0 && bracket < brace) {
            // 有时模型会用 JSON 数组套一层，尝试取第一个对象的字符串段
            Log.w(TAG, "AI returned JSON array, trying to extract first object")
            val arr = try {
                org.json.JSONArray(s)
            } catch (e: Exception) {
                error("AI 返回了无法解析的 JSON 数组：${s.take(200)}")
            }
            return arr.optJSONObject(0)
                ?: error("AI 返回的 JSON 数组第一个元素不是对象：${s.take(200)}")
        }

        return JSONObject(s)
    }
}