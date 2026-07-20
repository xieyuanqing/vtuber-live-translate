package com.xyq.livetranslate.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.xyq.livetranslate.AiTextClient
import com.xyq.livetranslate.R
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
import com.xyq.livetranslate.VideoMetadataClient
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

    @Volatile
    private var latestInterpAnalysisRequestId = ""

    @Volatile
    private var latestVideoAnalysisRequestId = ""
    private var interpContextExpanded = false
    private val interpContextToggle: View? =
        interpretationViews.idleContent.findViewById(R.id.rowInterpSessionContextToggle)
    private val interpContextHeader: TextView? =
        interpretationViews.idleContent.findViewById(R.id.tvInterpSessionContextHeader)
    private val interpContextSummary: TextView? =
        interpretationViews.idleContent.findViewById(R.id.tvInterpSessionContextSummary)
    private val interpContextBody: View? =
        interpretationViews.idleContent.findViewById(R.id.interpSessionContextBody)
    private var videoContextExpanded = false
    private val videoContextToggle: View? =
        videoViews.idleContent.findViewById(R.id.rowVideoSessionContextToggle)
    private val videoContextHeader: TextView? =
        videoViews.idleContent.findViewById(R.id.tvVideoSessionContextHeader)
    private val videoContextSummary: TextView? =
        videoViews.idleContent.findViewById(R.id.tvVideoSessionContextSummary)
    private val videoContextBody: View? =
        videoViews.idleContent.findViewById(R.id.videoSessionContextBody)
    private val interpClearButton: View? =
        interpretationViews.idleContent.findViewById(R.id.btnInterpClearSessionContext)
    private val videoClearButton: View? =
        videoViews.idleContent.findViewById(R.id.btnVideoClearSessionContext)

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
        interpContextToggle?.setOnClickListener {
            interpContextExpanded = !interpContextExpanded
            renderInterpContextFold()
            if (interpContextExpanded) {
                interpretationViews.sessionContext.requestFocus()
            }
        }
        interpretationViews.sessionContext.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (!interpContextExpanded) renderInterpContextFold()
                    else updateInterpContextSummaryOnly()
                }
            },
        )
        renderInterpContextFold()
        videoContextToggle?.setOnClickListener {
            videoContextExpanded = !videoContextExpanded
            renderVideoContextFold()
            if (videoContextExpanded) {
                videoViews.sessionContext.requestFocus()
            }
        }
        val videoWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!videoContextExpanded) renderVideoContextFold()
                else updateVideoContextSummaryOnly()
            }
        }
        videoViews.sessionContext.addTextChangedListener(videoWatcher)
        videoViews.videoSessionUrl?.addTextChangedListener(videoWatcher)
        interpClearButton?.setOnClickListener {
            interpretationViews.sessionContext.setText("")
            showAnalyzeStatus(interpretationViews.analyzeContextStatus, "")
            renderInterpContextFold()
            toast("已清除本场背景")
        }
        videoClearButton?.setOnClickListener {
            videoViews.sessionContext.setText("")
            videoViews.videoSessionUrl?.setText("")
            showAnalyzeStatus(videoViews.analyzeContextStatus, "")
            renderVideoContextFold()
            toast("已清除本场视频资料")
        }
        renderVideoContextFold()
    }

    private fun renderInterpContextFold() {
        val body = interpContextBody ?: return
        body.visibility = if (interpContextExpanded) View.VISIBLE else View.GONE
        updateInterpContextSummaryOnly()
        val count = interpretationViews.sessionContext.text?.toString().orEmpty().trim().length
        interpContextHeader?.text = if (interpContextExpanded) {
            "本场背景 · 可选"
        } else {
            "本场背景 · 可选 ›"
        }
        if (!interpContextExpanded && count > 0) {
            interpContextSummary?.visibility = View.VISIBLE
            interpContextSummary?.text = "已填写 · ${count} 字"
        } else if (!interpContextExpanded) {
            interpContextSummary?.visibility = View.GONE
            interpContextSummary?.text = ""
        } else {
            interpContextSummary?.visibility = View.GONE
        }
    }

    private fun updateInterpContextSummaryOnly() {
        val count = interpretationViews.sessionContext.text?.toString().orEmpty().trim().length
        if (!interpContextExpanded && count > 0) {
            interpContextSummary?.visibility = View.VISIBLE
            interpContextSummary?.text = "已填写 · ${count} 字"
        } else if (!interpContextExpanded) {
            interpContextSummary?.visibility = View.GONE
            interpContextSummary?.text = ""
        }
        interpClearButton?.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    private fun renderVideoContextFold() {
        val body = videoContextBody ?: return
        body.visibility = if (videoContextExpanded) View.VISIBLE else View.GONE
        updateVideoContextSummaryOnly()
        videoContextHeader?.text = if (videoContextExpanded) {
            "本场视频 · 可选"
        } else {
            "本场视频 · 可选 ›"
        }
    }

    private fun updateVideoContextSummaryOnly() {
        val contextText = videoViews.sessionContext.text?.toString().orEmpty().trim()
        val url = videoViews.videoSessionUrl?.text?.toString().orEmpty().trim()
        val parts = buildList {
            if (url.isNotEmpty()) add("链接")
            if (contextText.isNotEmpty()) add("${contextText.length} 字")
        }
        if (!videoContextExpanded && parts.isNotEmpty()) {
            videoContextSummary?.visibility = View.VISIBLE
            videoContextSummary?.text = "已填 · " + parts.joinToString(" · ")
        } else {
            videoContextSummary?.visibility = View.GONE
            videoContextSummary?.text = ""
        }
        videoClearButton?.visibility =
            if (contextText.isNotEmpty() || url.isNotEmpty()) View.VISIBLE else View.GONE
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
        // 恢复后仍默认折叠，仅刷新摘要。
        interpContextExpanded = false
        videoContextExpanded = false
        renderInterpContextFold()
        renderVideoContextFold()
    }

    fun destroy() {
        latestInterpAnalysisRequestId = ""
        latestVideoAnalysisRequestId = ""
    }

    override fun current(mode: TranslationMode): SessionPromptContext = SessionPromptContext(
        manualContext = views(mode).sessionContext.text?.toString().orEmpty().trim(),
    )

    override fun clearAfterSuccessfulStart(mode: TranslationMode) {
        views(mode).sessionContext.setText("")
        if (mode == TranslationMode.VIDEO) videoViews.videoSessionUrl?.setText("")
        when (mode) {
            TranslationMode.INTERPRETATION -> {
                interpContextExpanded = false
                renderInterpContextFold()
            }
            TranslationMode.VIDEO -> {
                videoContextExpanded = false
                renderVideoContextFold()
            }
        }
    }

    private fun views(mode: TranslationMode): ModeHomeViews = when (mode) {
        TranslationMode.INTERPRETATION -> interpretationViews
        TranslationMode.VIDEO -> videoViews
    }

    private fun showAnalyzeStatus(statusView: TextView, message: String) {
        statusView.text = message
        statusView.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
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
            showAnalyzeStatus(statusView, "好友测试凭据已失效，请回到设置重新绑定")
            return
        }
        if (apiKey.isBlank()) {
            showAnalyzeStatus(
                statusView,
                if (friendAccess) {
                    "好友测试凭据已失效，请回到设置重新绑定"
                } else {
                    "请先在设置 → 内容分析 AI 中填写 API Key"
                },
            )
            return
        }
        val material = modeViews.sessionContext.text?.toString().orEmpty().trim()
        val url = modeViews.videoSessionUrl?.text?.toString().orEmpty().trim()
        if (mode == TranslationMode.INTERPRETATION && material.isBlank()) {
            showAnalyzeStatus(statusView, "请先填写本场背景或资料")
            return
        }
        if (mode == TranslationMode.VIDEO && url.isBlank() && material.isBlank()) {
            showAnalyzeStatus(statusView, "请先填写视频链接或本场资料")
            return
        }
        if (mode == TranslationMode.VIDEO && url.isBlank()) {
            showAnalyzeStatus(
                statusView,
                "解析视频需要先填写 YouTube、哔哩哔哩或 Twitch 链接",
            )
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
        showAnalyzeStatus(statusView, "正在整理，请稍候…")

        runCatching {
            Thread({
                runCatching {
                    val videoInfo = if (mode == TranslationMode.VIDEO) {
                        VideoMetadataClient.fetch(url)
                    } else {
                        null
                    }
                    check(isHostActive() && requestId == latestRequestId(mode)) { "分析已取消" }
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
                    )
                }.onSuccess { result ->
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
                            showAnalyzeStatus(statusView, "输入已修改，之前的分析结果已忽略")
                            button.isEnabled = true
                            return@success
                        }
                        if (result.sessionContext.isBlank()) {
                            showAnalyzeStatus(statusView, "AI 没有返回可用背景，请补充资料后重试")
                        } else {
                            modeViews.sessionContext.setText(result.sessionContext)
                            showAnalyzeStatus(statusView, result.note.ifBlank { "本场资料已整理" })
                        }
                        button.isEnabled = true
                    }
                }.onFailure { error ->
                    postToUi failure@{
                        if (!isHostActive() || requestId != latestRequestId(mode)) return@failure
                        showAnalyzeStatus(statusView, "整理失败：${error.message ?: "未知错误"}")
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
