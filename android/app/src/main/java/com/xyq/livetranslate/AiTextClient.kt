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
    ): JSONObject {
        when (format) {
            Format.GEMINI -> return generateGemini(systemPrompt, userPrompt, baseUrl, apiKey, model)
            Format.OPENAI -> return generateOpenAI(systemPrompt, userPrompt, baseUrl, apiKey, model)
        }
    }

    // ---------- Gemini 原生 REST ----------

    private fun generateGemini(
        systemPrompt: String,
        userPrompt: String,
        baseUrl: String,
        apiKey: String,
        model: String,
    ): JSONObject {
        val url = baseUrl.trimEnd('/') +
            "/v1beta/models/${model}:generateContent?key=$apiKey"

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
            // 开启 Google Search grounding，让模型实时搜索 web 获取 VTuber 最新信息
            .put(
                "tools", org.json.JSONArray()
                    .put(JSONObject().put("google_search", JSONObject()))
            )

        if (systemPrompt.isNotBlank()) {
            bodyObj.put(
                "systemInstruction",
                JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
        }

        val jsonMedia = "application/json; charset=utf-8".toMediaType()
        val req = Request.Builder()
            .url(url)
            .post(bodyObj.toString().toRequestBody(jsonMedia))
            .build()

        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "Gemini API error ${resp.code}: ${body.take(300)}")
                error("AI API 返回错误 ${resp.code}，请检查 Key 和模型名是否正确")
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
        val url = baseUrl.trimEnd('/') + "/v1/chat/completions"

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
                Log.w(TAG, "OpenAI API error ${resp.code}: ${body.take(300)}")
                error("AI API 返回错误 ${resp.code}，请检查 Key 和 URL 是否正确")
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