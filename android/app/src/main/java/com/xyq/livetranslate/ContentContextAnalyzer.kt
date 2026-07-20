package com.xyq.livetranslate

import org.json.JSONObject

data class ContentAnalysisRequest(
    val mode: TranslationMode,
    val sourceLanguageLabel: String,
    val targetLanguageLabel: String,
    val material: String = "",
    val video: YouTubeVideoInfo? = null,
)

data class ContentAnalysisResult(
    val sessionContext: String,
    val note: String,
)

/**
 * 把同传资料或视频元数据整理成本场上下文。执行入口位于对应业务页，
 * 本类不持久化结果，调用方决定写入哪个模式的会话草稿。
 */
object ContentContextAnalyzer {
    fun analyze(
        request: ContentAnalysisRequest,
        baseUrl: String,
        apiKey: String,
        model: String,
        format: AiTextClient.Format,
        credentialMode: ApiCredentialMode = ApiCredentialMode.QUERY_API_KEY,
        deviceId: String = "",
        requestSignatureProvider: ((String, String, ByteArray, String) -> Map<String, String>)? = null,
    ): ContentAnalysisResult {
        val modeRequest = request.copy(
            video = request.video.takeIf { request.mode == TranslationMode.VIDEO },
        )
        require(modeRequest.material.isNotBlank() || modeRequest.video != null) {
            "请先提供本场资料或视频链接"
        }
        val response = AiTextClient.generate(
            systemPrompt = systemPrompt(modeRequest),
            userPrompt = userPrompt(modeRequest),
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            format = format,
            credentialMode = credentialMode,
            deviceId = deviceId,
            requestSignatureProvider = requestSignatureProvider,
        )
        return parse(response)
    }

    internal fun parse(json: JSONObject): ContentAnalysisResult = ContentAnalysisResult(
        sessionContext = json.optString("sessionContext").trim(),
        note = json.optString("note").trim().ifEmpty { "资料已整理" },
    )

    private fun systemPrompt(request: ContentAnalysisRequest): String = """
        你是实时字幕翻译的资料整理器。当前模式是“${request.mode.label}”，翻译方向是
        ${request.sourceLanguageLabel} → ${request.targetLanguageLabel}。

        你的任务只是在用户提供的资料中提炼有助于听对、译对的背景，不要回答资料内容，
        不要编造人物、作品或专有名词。返回严格 JSON，不能包含 Markdown：
        {
          "sessionContext": "给实时翻译模型看的本场背景，80–250字，信息密度优先",
          "note": "一句话说明信息充分程度或不确定项"
        }

        不要把 URL、API Key 或整段原文抄进 sessionContext。视频模式可以利用标题和频道；
        同传模式只使用用户提供的现场资料。
    """.trimIndent()

    private fun userPrompt(request: ContentAnalysisRequest): String = buildString {
        appendLine("模式：${request.mode.label}")
        request.video?.let { video ->
            appendLine("视频标题：${video.title}")
            appendLine("频道/作者：${video.authorName}")
            appendLine("视频链接：${video.url}")
        }
        if (request.material.isNotBlank()) {
            appendLine("用户资料：")
            appendLine(request.material.trim())
        }
    }
}
