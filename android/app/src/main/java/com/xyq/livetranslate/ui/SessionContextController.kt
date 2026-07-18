package com.xyq.livetranslate.ui

import android.content.Context
import android.os.Bundle
import com.xyq.livetranslate.AiTextClient
import com.xyq.livetranslate.ApiCredentialMode
import com.xyq.livetranslate.ContentAnalysisRequest
import com.xyq.livetranslate.ContentContextAnalyzer
import com.xyq.livetranslate.FriendDeviceIdentity
import com.xyq.livetranslate.FriendGatewayStore
import com.xyq.livetranslate.SessionPromptContext
import com.xyq.livetranslate.SettingsStore
import com.xyq.livetranslate.TranslationLanguageCatalog
import com.xyq.livetranslate.TranslationMode
import com.xyq.livetranslate.TranslationPlanStore
import com.xyq.livetranslate.YouTubeOEmbedClient
import java.util.UUID

/** 两个模式的本场临时上下文、视频 URL 与独立 AI 请求守卫。 */
internal class SessionContextController(
    private val context: Context,
    private val interpretationViews: ModeHomeViews,
    private val videoViews: ModeHomeViews,
    private val persistSecondAiInputs: () -> Unit,
    private val postToUi: (() -> Unit) -> Unit,
    private val isHostActive: () -> Boolean,
    private val toast: (String) -> Unit,
) : SessionContextAccess {
    private companion object {
        const val STATE_INTERPRETATION_CONTEXT = "interpretation_context"
        const val STATE_VIDEO_CONTEXT = "video_context"
        const val STATE_VIDEO_URL = "video_url"
    }

    private var latestInterpAnalysisRequestId = ""
    private var latestVideoAnalysisRequestId = ""

    init {
        check(interpretationViews.videoSessionUrl == null)
        check(videoViews.videoSessionUrl != null)
    }

    fun setup() {
        interpretationViews.analyzeContextButton.setOnClickListener {
            analyzeSessionContext(TranslationMode.INTERPRETATION)
        }
        videoViews.analyzeContextButton.setOnClickListener {
            analyzeSessionContext(TranslationMode.VIDEO)
        }
    }

    fun saveState(outState: Bundle) {
        outState.putString(
            STATE_INTERPRETATION_CONTEXT,
            interpretationViews.sessionContext.text?.toString().orEmpty(),
        )
        outState.putString(
            STATE_VIDEO_CONTEXT,
            videoViews.sessionContext.text?.toString().orEmpty(),
        )
        outState.putString(
            STATE_VIDEO_URL,
            videoViews.videoSessionUrl?.text?.toString().orEmpty(),
        )
    }

    fun restoreState(savedState: Bundle?) {
        interpretationViews.sessionContext.setText(
            savedState?.getString(STATE_INTERPRETATION_CONTEXT).orEmpty(),
        )
        videoViews.sessionContext.setText(savedState?.getString(STATE_VIDEO_CONTEXT).orEmpty())
        videoViews.videoSessionUrl?.setText(savedState?.getString(STATE_VIDEO_URL).orEmpty())
    }

    override fun current(mode: TranslationMode): SessionPromptContext = SessionPromptContext(
        manualContext = views(mode).sessionContext.text?.toString().orEmpty().trim(),
    )

    override fun clearAfterSuccessfulStart(mode: TranslationMode) {
        views(mode).sessionContext.setText("")
        if (mode == TranslationMode.VIDEO) videoViews.videoSessionUrl?.setText("")
    }

    private fun views(mode: TranslationMode): ModeHomeViews = when (mode) {
        TranslationMode.INTERPRETATION -> interpretationViews
        TranslationMode.VIDEO -> videoViews
    }

    private fun analyzeSessionContext(mode: TranslationMode) {
        persistSecondAiInputs()
        val friendAccess = FriendGatewayStore.isActive(context)
        val apiKey = if (friendAccess) {
            FriendGatewayStore.token(context)
        } else {
            SettingsStore.secondAiApiKey(context)
        }
        val modeViews = views(mode)
        val statusView = modeViews.analyzeContextStatus
        val button = modeViews.analyzeContextButton
        if (
            FriendGatewayStore.mode(context) == FriendGatewayStore.MODE_FRIEND &&
            !friendAccess
        ) {
            statusView.text = "好友测试凭据已失效，请回到设置重新绑定"
            return
        }
        if (apiKey.isBlank()) {
            statusView.text = if (friendAccess) {
                "好友测试凭据已失效，请回到设置重新绑定"
            } else {
                "请先在设置 → 内容分析 AI 中填写 API Key"
            }
            return
        }
        val material = modeViews.sessionContext.text?.toString().orEmpty().trim()
        val url = modeViews.videoSessionUrl?.text?.toString().orEmpty().trim()
        if (mode == TranslationMode.INTERPRETATION && material.isBlank()) {
            statusView.text = "请先填写本场背景或资料"
            return
        }
        if (mode == TranslationMode.VIDEO && url.isBlank() && material.isBlank()) {
            statusView.text = "请先填写视频链接或本场资料"
            return
        }
        if (mode == TranslationMode.VIDEO && url.isBlank()) {
            statusView.text = "解析视频需要先填写 YouTube 链接"
            return
        }

        val requestId = UUID.randomUUID().toString()
        setLatestRequestId(mode, requestId)
        val plan = TranslationPlanStore.loadDraft(context, mode).normalized()
        val baseUrl = if (friendAccess) {
            FriendGatewayStore.GATEWAY_BASE_URL + "/gateway"
        } else {
            SettingsStore.secondAiBaseUrl(context)
        }
        val model = if (friendAccess) "gemini-3.5-flash" else SettingsStore.secondAiModel(context)
        val format = if (friendAccess) {
            AiTextClient.Format.GEMINI
        } else {
            AiTextClient.Format.fromKey(SettingsStore.secondAiFormat(context))
        }
        val credentialMode = if (friendAccess) {
            ApiCredentialMode.BEARER_TOKEN
        } else {
            ApiCredentialMode.QUERY_API_KEY
        }
        val deviceId = if (friendAccess) FriendGatewayStore.deviceId(context) else ""
        val requestSignatureProvider:
            ((String, String, ByteArray, String) -> Map<String, String>)? = if (friendAccess) {
                { method, path, body, token ->
                    FriendDeviceIdentity.signRequest(context, method, path, body, token).asMap()
                }
            } else {
                null
            }
        button.isEnabled = false
        statusView.text = "正在整理，请稍候…"

        runCatching {
            Thread({
                runCatching {
                    val videoInfo = if (mode == TranslationMode.VIDEO) {
                        YouTubeOEmbedClient.fetch(url)
                    } else {
                        null
                    }
                    val source = TranslationLanguageCatalog.source(plan.sourceLanguageCode)
                    val target = TranslationLanguageCatalog.target(plan.targetLanguageCode)
                    ContentContextAnalyzer.analyze(
                        request = ContentAnalysisRequest(
                            mode = mode,
                            sourceLanguageLabel = source.label,
                            targetLanguageLabel = target.label,
                            material = material,
                            video = videoInfo,
                        ),
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        model = model,
                        format = format,
                        credentialMode = credentialMode,
                        deviceId = deviceId,
                        requestSignatureProvider = requestSignatureProvider,
                    ) to videoInfo
                }.onSuccess { (result, videoInfo) ->
                    postToUi success@{
                        if (!isHostActive() || requestId != latestRequestId(mode)) return@success
                        val inputChanged = when (mode) {
                            TranslationMode.INTERPRETATION ->
                                modeViews.sessionContext.text?.toString().orEmpty().trim() != material
                            TranslationMode.VIDEO ->
                                modeViews.sessionContext.text?.toString().orEmpty().trim() != material ||
                                    modeViews.videoSessionUrl?.text?.toString().orEmpty().trim() != url
                        }
                        if (inputChanged) {
                            setLatestRequestId(mode, "")
                            statusView.text = "输入已修改，之前的分析结果已忽略"
                            button.isEnabled = true
                            return@success
                        }
                        if (result.sessionContext.isBlank()) {
                            statusView.text = "AI 没有返回可用背景，请补充资料后重试"
                        } else {
                            val composed = buildString {
                                if (videoInfo != null) {
                                    if (videoInfo.title.isNotBlank()) appendLine("视频标题：${videoInfo.title}")
                                    if (videoInfo.authorName.isNotBlank()) appendLine("频道/作者：${videoInfo.authorName}")
                                    if (isNotEmpty()) appendLine()
                                }
                                append(result.sessionContext)
                            }.trim()
                            modeViews.sessionContext.setText(composed)
                            statusView.text = result.note.ifBlank { "本场资料已整理" }
                        }
                        button.isEnabled = true
                    }
                }.onFailure { error ->
                    postToUi failure@{
                        if (!isHostActive() || requestId != latestRequestId(mode)) return@failure
                        statusView.text = "整理失败：${error.message ?: "未知错误"}"
                        button.isEnabled = true
                    }
                }
            }, "session-context-${mode.storageKey}").apply { start() }
        }.onFailure { error ->
            button.isEnabled = true
            toast("无法启动内容整理：${error.message ?: "未知错误"}")
        }
    }

    private fun latestRequestId(mode: TranslationMode): String = when (mode) {
        TranslationMode.INTERPRETATION -> latestInterpAnalysisRequestId
        TranslationMode.VIDEO -> latestVideoAnalysisRequestId
    }

    private fun setLatestRequestId(mode: TranslationMode, requestId: String) {
        when (mode) {
            TranslationMode.INTERPRETATION -> latestInterpAnalysisRequestId = requestId
            TranslationMode.VIDEO -> latestVideoAnalysisRequestId = requestId
        }
    }
}
