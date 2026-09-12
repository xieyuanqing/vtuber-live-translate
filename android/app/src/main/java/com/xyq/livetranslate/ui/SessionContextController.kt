package com.xyq.livetranslate.ui

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import com.xyq.livetranslate.AiTextClient
import com.xyq.livetranslate.R
import com.xyq.livetranslate.ContentAnalysisRequest
import com.xyq.livetranslate.ContentContextAnalyzer
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
    private val openAiSettings: (returnTabId: Int) -> Unit,
    private val postToUi: (() -> Unit) -> Unit,
    private val isHostActive: () -> Boolean,
    private val toast: (String) -> Unit,
) : SessionContextAccess {
    private companion object {
        const val STATE_INTERPRETATION_CONTEXT = "interpretation_context"
        const val STATE_VIDEO_CONTEXT = "video_context"
        const val STATE_VIDEO_URL = "video_url"
        const val SUMMARY_SNIPPET_LENGTH = 20
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

    /** 应用 AI 结果前的原文；只在本次替换后有效，用于「撤销替换」。 */
    private var interpUndoSnapshot: String? = null
    private var videoUndoSnapshot: String? = null

    /** 程序化写入输入框时抑制 watcher，避免自己触发「输入已修改」。 */
    private var suppressInputWatcher = false

    init {
        check(interpretationViews.videoSessionUrl == null)
        check(videoViews.videoSessionUrl != null)
    }

    fun setup() {
        TranslationMode.entries.forEach { mode ->
            val modeViews = views(mode)
            modeViews.analyzeContextButton.setOnClickListener { analyzeSessionContext(mode) }
            modeViews.analyzeConfigureButton.setOnClickListener { openAiSettings(returnTabId(mode)) }
            modeViews.analyzeApplyButton.setOnClickListener { applyAnalysisResult(mode) }
            modeViews.analyzeDiscardButton.setOnClickListener { discardAnalysisResult(mode) }
            modeViews.analyzeUndoButton.setOnClickListener { undoAnalysisResult(mode) }
        }
        interpContextToggle?.setOnClickListener {
            interpContextExpanded = !interpContextExpanded
            renderInterpContextFold()
            if (interpContextExpanded) {
                interpretationViews.sessionContext.requestFocus()
            }
        }
        interpretationViews.sessionContext.addTextChangedListener(
            watcher {
                invalidateAnalysisForInputChange(TranslationMode.INTERPRETATION)
                renderInterpContextFold()
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
        val videoWatcher = watcher {
            invalidateAnalysisForInputChange(TranslationMode.VIDEO)
            renderVideoAnalyzeButtonLabel()
            renderVideoContextFold()
        }
        videoViews.sessionContext.addTextChangedListener(videoWatcher)
        videoViews.videoSessionUrl?.addTextChangedListener(videoWatcher)
        interpClearButton?.setOnClickListener {
            setContextText(TranslationMode.INTERPRETATION, "")
            resetAnalysisUi(TranslationMode.INTERPRETATION)
            toast(context.getString(R.string.rt_toast_interp_context_cleared))
        }
        videoClearButton?.setOnClickListener {
            setContextText(TranslationMode.VIDEO, "")
            setVideoUrlText("")
            resetAnalysisUi(TranslationMode.VIDEO)
            toast(context.getString(R.string.rt_toast_video_context_cleared))
        }
        renderVideoAnalyzeButtonLabel()
        renderVideoContextFold()
    }

    private fun watcher(onChanged: () -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            if (suppressInputWatcher) return
            onChanged()
        }
    }

    private fun invalidateAnalysisForInputChange(mode: TranslationMode) {
        // 手动改过输入后，之前那次替换的原文已经对不上，撤销入口必须收回。
        setUndoSnapshot(mode, null)
        views(mode).analyzeUndoButton.visibility = View.GONE
        if (latestRequestId(mode).isEmpty()) return
        setLatestRequestId(mode, "")
        val modeViews = views(mode)
        modeViews.analyzeContextButton.isEnabled = true
        showAnalyzeStatus(mode, context.getString(R.string.rt_ctx_status_input_changed_cancelled))
    }

    private fun renderInterpContextFold() {
        val body = interpContextBody ?: return
        body.visibility = if (interpContextExpanded) View.VISIBLE else View.GONE
        interpContextHeader?.text = if (interpContextExpanded) {
            context.getString(R.string.rt_ctx_header_interp_expanded)
        } else {
            context.getString(R.string.rt_ctx_header_interp_collapsed)
        }
        val text = interpretationViews.sessionContext.text?.toString().orEmpty().trim()
        renderSummary(
            summaryView = interpContextSummary,
            expanded = interpContextExpanded,
            summary = if (text.isEmpty()) "" else snippet(text),
        )
        interpClearButton?.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderVideoContextFold() {
        val body = videoContextBody ?: return
        body.visibility = if (videoContextExpanded) View.VISIBLE else View.GONE
        videoContextHeader?.text = if (videoContextExpanded) {
            context.getString(R.string.rt_ctx_header_video_expanded)
        } else {
            context.getString(R.string.rt_ctx_header_video_collapsed)
        }
        val text = videoViews.sessionContext.text?.toString().orEmpty().trim()
        val url = videoViews.videoSessionUrl?.text?.toString().orEmpty().trim()
        // 折叠行显示一段能认出来的内容，而不是只报字数。
        val summary = when {
            text.isNotEmpty() && url.isNotEmpty() -> snippet(text) + context.getString(R.string.rt_ctx_summary_has_url)
            text.isNotEmpty() -> snippet(text)
            url.isNotEmpty() -> context.getString(R.string.rt_ctx_summary_url_only)
            else -> ""
        }
        renderSummary(
            summaryView = videoContextSummary,
            expanded = videoContextExpanded,
            summary = summary,
        )
        videoClearButton?.visibility =
            if (text.isEmpty() && url.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderSummary(summaryView: TextView?, expanded: Boolean, summary: String) {
        val visible = !expanded && summary.isNotEmpty()
        summaryView?.visibility = if (visible) View.VISIBLE else View.GONE
        summaryView?.text = if (visible) summary else ""
    }

    private fun snippet(text: String): String {
        val firstLine = text.lineSequence()
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
            .orEmpty()
        return if (firstLine.length <= SUMMARY_SNIPPET_LENGTH) {
            firstLine
        } else {
            firstLine.take(SUMMARY_SNIPPET_LENGTH) + "…"
        }
    }

    /** 有链接就是抓资料，只有文字就是整理；按钮别说自己做不到的事。 */
    private fun renderVideoAnalyzeButtonLabel() {
        val hasUrl = videoViews.videoSessionUrl?.text?.toString().orEmpty().isNotBlank()
        videoViews.analyzeContextButton.text = if (hasUrl) {
            context.getString(R.string.rt_ctx_btn_fetch_video_context)
        } else {
            context.getString(R.string.rt_ctx_btn_organize_context)
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
        setContextText(
            TranslationMode.INTERPRETATION,
            savedState?.getString(STATE_INTERPRETATION_CONTEXT).orEmpty(),
        )
        setContextText(TranslationMode.VIDEO, savedState?.getString(STATE_VIDEO_CONTEXT).orEmpty())
        setVideoUrlText(savedState?.getString(STATE_VIDEO_URL).orEmpty())
        // 恢复后仍默认折叠，仅刷新摘要。
        interpContextExpanded = false
        videoContextExpanded = false
        renderVideoAnalyzeButtonLabel()
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

    private fun views(mode: TranslationMode): ModeHomeViews = when (mode) {
        TranslationMode.INTERPRETATION -> interpretationViews
        TranslationMode.VIDEO -> videoViews
    }

    private fun returnTabId(mode: TranslationMode): Int = when (mode) {
        TranslationMode.INTERPRETATION -> R.id.nav_interp
        TranslationMode.VIDEO -> R.id.nav_video
    }

    private fun setContextText(mode: TranslationMode, text: String) {
        suppressInputWatcher = true
        views(mode).sessionContext.setText(text)
        suppressInputWatcher = false
    }

    private fun setVideoUrlText(text: String) {
        suppressInputWatcher = true
        videoViews.videoSessionUrl?.setText(text)
        suppressInputWatcher = false
    }

    private fun showAnalyzeStatus(mode: TranslationMode, message: String, warn: Boolean = false) {
        views(mode).analyzeContextStatus.apply {
            text = message
            visibility = if (message.isBlank()) View.GONE else View.VISIBLE
            setTextColor(context.getColor(if (warn) R.color.warning else R.color.text_muted))
        }
    }

    private fun resetAnalysisUi(mode: TranslationMode) {
        val modeViews = views(mode)
        showAnalyzeStatus(mode, "")
        modeViews.analyzePreview.visibility = View.GONE
        modeViews.analyzePreviewText.text = ""
        modeViews.analyzeConfigureButton.visibility = View.GONE
        modeViews.analyzeUndoButton.visibility = View.GONE
        setUndoSnapshot(mode, null)
        if (mode == TranslationMode.INTERPRETATION) renderInterpContextFold() else renderVideoContextFold()
    }

    /** 结果先摆出来给人看，用户点了才写进输入框。 */
    private fun showAnalysisPreview(mode: TranslationMode, result: String, note: String) {
        val modeViews = views(mode)
        modeViews.analyzePreviewText.text = result
        modeViews.analyzePreview.visibility = View.VISIBLE
        modeViews.analyzeUndoButton.visibility = View.GONE
        setUndoSnapshot(mode, null)
        showAnalyzeStatus(mode, note.ifBlank { context.getString(R.string.rt_ctx_status_ready_for_review) })
    }

    private fun applyAnalysisResult(mode: TranslationMode) {
        val modeViews = views(mode)
        val result = modeViews.analyzePreviewText.text?.toString().orEmpty()
        if (result.isBlank()) return
        setUndoSnapshot(mode, modeViews.sessionContext.text?.toString().orEmpty())
        setContextText(mode, result)
        modeViews.analyzePreview.visibility = View.GONE
        modeViews.analyzeUndoButton.visibility = View.VISIBLE
        showAnalyzeStatus(mode, context.getString(R.string.rt_ctx_status_applied))
        if (mode == TranslationMode.INTERPRETATION) renderInterpContextFold() else renderVideoContextFold()
    }

    private fun discardAnalysisResult(mode: TranslationMode) {
        val modeViews = views(mode)
        modeViews.analyzePreview.visibility = View.GONE
        modeViews.analyzePreviewText.text = ""
        showAnalyzeStatus(mode, context.getString(R.string.rt_ctx_status_discarded))
    }

    private fun undoAnalysisResult(mode: TranslationMode) {
        val snapshot = undoSnapshot(mode) ?: return
        setContextText(mode, snapshot)
        setUndoSnapshot(mode, null)
        views(mode).analyzeUndoButton.visibility = View.GONE
        showAnalyzeStatus(mode, context.getString(R.string.rt_ctx_status_undone))
        if (mode == TranslationMode.INTERPRETATION) renderInterpContextFold() else renderVideoContextFold()
    }

    private fun analyzeSessionContext(mode: TranslationMode) {
        persistSecondAiInputs()
        val modeViews = views(mode)
        val button = modeViews.analyzeContextButton
        modeViews.analyzeConfigureButton.visibility = View.GONE
        val apiKey = SettingsStore.secondAiApiKey(context)
        if (apiKey.isBlank()) {
            showAnalyzeStatus(mode, context.getString(R.string.rt_ctx_status_no_ai_configured), warn = true)
            modeViews.analyzeConfigureButton.visibility = View.VISIBLE
            return
        }
        val material = modeViews.sessionContext.text?.toString().orEmpty().trim()
        val url = modeViews.videoSessionUrl?.text?.toString().orEmpty().trim()
        if (material.isBlank() && url.isBlank()) {
            showAnalyzeStatus(
                mode,
                if (mode == TranslationMode.VIDEO) {
                    context.getString(R.string.rt_ctx_hint_fill_video_or_context)
                } else {
                    context.getString(R.string.rt_ctx_hint_fill_context)
                },
                warn = true,
            )
            return
        }

        val requestId = UUID.randomUUID().toString()
        setLatestRequestId(mode, requestId)
        val plan = TranslationPlanStore.loadDraft(context, mode).normalized()
        val baseUrl = SettingsStore.secondAiBaseUrl(context)
        val model = SettingsStore.secondAiModel(context)
        val format = AiTextClient.Format.fromKey(SettingsStore.secondAiFormat(context))
        button.isEnabled = false
        // 两段进度：先抓网页资料，再整理背景，失败时能看出卡在哪一步。
        showAnalyzeStatus(
            mode,
            if (url.isNotBlank()) {
                context.getString(R.string.rt_ctx_fetching_video_data)
            } else {
                context.getString(R.string.rt_ctx_organizing)
            },
        )

        Thread({
            runCatching {
                val videoInfo = if (mode == TranslationMode.VIDEO && url.isNotBlank()) {
                    VideoMetadataClient.fetch(url)
                } else {
                    null
                }
                check(isHostActive() && requestId == latestRequestId(mode)) { "analysis cancelled" }
                postToUi {
                    if (isHostActive() && requestId == latestRequestId(mode)) {
                        showAnalyzeStatus(mode, context.getString(R.string.rt_ctx_organizing))
                    }
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
                )
            }.onSuccess { result ->
                postToUi success@{
                    if (!isHostActive() || requestId != latestRequestId(mode)) return@success
                    val inputChanged = modeViews.sessionContext.text?.toString().orEmpty().trim() != material ||
                        modeViews.videoSessionUrl?.text?.toString().orEmpty().trim() != url
                    if (inputChanged) {
                        setLatestRequestId(mode, "")
                        showAnalyzeStatus(mode, context.getString(R.string.rt_ctx_status_input_changed_ignored))
                        button.isEnabled = true
                        return@success
                    }
                    setLatestRequestId(mode, "")
                    if (result.sessionContext.isBlank()) {
                        showAnalyzeStatus(
                            mode,
                            result.note.take(200).ifBlank { context.getString(R.string.rt_ctx_no_available_context) },
                            warn = true,
                        )
                    } else {
                        showAnalysisPreview(mode, result.sessionContext, result.note)
                    }
                    button.isEnabled = true
                }
            }.onFailure { error ->
                postToUi failure@{
                    if (!isHostActive() || requestId != latestRequestId(mode)) return@failure
                    setLatestRequestId(mode, "")
                    showAnalyzeStatus(
                        mode,
                        context.getString(
                            R.string.rt_ctx_organize_failed,
                            error.message ?: context.getString(R.string.rt_unknown_error),
                        ),
                        warn = true,
                    )
                    button.isEnabled = true
                }
            }
        }, "session-context-${mode.storageKey}").start()
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

    private fun undoSnapshot(mode: TranslationMode): String? = when (mode) {
        TranslationMode.INTERPRETATION -> interpUndoSnapshot
        TranslationMode.VIDEO -> videoUndoSnapshot
    }

    private fun setUndoSnapshot(mode: TranslationMode, value: String?) {
        when (mode) {
            TranslationMode.INTERPRETATION -> interpUndoSnapshot = value
            TranslationMode.VIDEO -> videoUndoSnapshot = value
        }
    }
}
