package com.xyq.livetranslate.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.xyq.livetranslate.R
import com.xyq.livetranslate.SceneLibraryStore
import com.xyq.livetranslate.TranslationLanguageCatalog
import com.xyq.livetranslate.TranslationMode
import com.xyq.livetranslate.TranslationPlanStore
import com.xyq.livetranslate.formatElapsedDuration

/** 一个模式主页的唯一 View 绑定。两个 controller 必须共享同一个实例。 */
internal data class ModeHomeViews(
    val idleContent: View,
    val runningContent: View,
    val homeScenes: ChipGroup,
    val idleStatus: TextView?,
    val idleSubStatus: TextView?,
    val runningStatus: TextView,
    val runningStatusDot: View,
    val runningSubStatus: TextView?,
    val elapsed: TextView,
    val runningMeta: TextView,
    val confirmedList: LinearLayout,
    val audioLevel: TextView,
    val audioProgress: ProgressBar,
    val startButton: View,
    val stopButton: MaterialButton,
    val targetLanguageLabel: TextView,
    val currentTranslation: TextView,
    val sourceTail: TextView,
    val transcriptPath: TextView,
    val sourceLanguage: MaterialAutoCompleteTextView,
    val targetLanguage: MaterialAutoCompleteTextView,
    val planCard: View,
    val planSummary: TextView,
    val profileSummary: TextView,
    val openSceneLibraryButton: View,
    val sessionContext: TextInputEditText,
    val analyzeContextButton: MaterialButton,
    val analyzeContextStatus: TextView,
    val overlayPermissionRow: View?,
    val overlayPermissionDot: View?,
    val overlayPermissionStatus: TextView?,
    val overlayPermissionSettings: View?,
    val videoSessionUrl: TextInputEditText?,
) {
    companion object {
        fun bind(root: View, mode: TranslationMode): ModeHomeViews = when (mode) {
            TranslationMode.INTERPRETATION -> ModeHomeViews(
                idleContent = root.findViewById(R.id.interpIdleContent),
                runningContent = root.findViewById(R.id.interpRunningContent),
                homeScenes = root.findViewById(R.id.chipGroupInterpHomeScenes),
                idleStatus = root.findViewById(R.id.tvInterpStatus),
                idleSubStatus = root.findViewById(R.id.tvInterpSubStatus),
                runningStatus = root.findViewById(R.id.tvInterpRunningStatus),
                runningStatusDot = root.findViewById(R.id.viewInterpRunningStatusDot),
                runningSubStatus = null,
                elapsed = root.findViewById(R.id.tvInterpElapsed),
                runningMeta = root.findViewById(R.id.tvInterpRunningMeta),
                confirmedList = root.findViewById(R.id.interpConfirmedList),
                audioLevel = root.findViewById(R.id.tvInterpAudioLevel),
                audioProgress = root.findViewById(R.id.pbInterpAudio),
                startButton = root.findViewById(R.id.btnInterpToggle),
                stopButton = root.findViewById(R.id.btnInterpStop),
                targetLanguageLabel = root.findViewById(R.id.tvInterpTargetLanguageLabel),
                currentTranslation = root.findViewById(R.id.tvInterpZh),
                sourceTail = root.findViewById(R.id.tvInterpJa),
                transcriptPath = root.findViewById(R.id.tvInterpTranscriptPath),
                sourceLanguage = root.findViewById(R.id.acInterpSourceLang),
                targetLanguage = root.findViewById(R.id.acInterpTargetLang),
                planCard = root.findViewById(R.id.cardInterpPlan),
                planSummary = root.findViewById(R.id.tvInterpPlanSummary),
                profileSummary = root.findViewById(R.id.tvInterpProfile),
                openSceneLibraryButton = root.findViewById(R.id.btnInterpOpenPlanLibrary),
                sessionContext = root.findViewById(R.id.etInterpSessionContext),
                analyzeContextButton = root.findViewById(R.id.btnInterpAnalyzeContext),
                analyzeContextStatus = root.findViewById(R.id.tvInterpAnalyzeStatus),
                overlayPermissionRow = null,
                overlayPermissionDot = null,
                overlayPermissionStatus = null,
                overlayPermissionSettings = null,
                videoSessionUrl = null,
            )
            TranslationMode.VIDEO -> ModeHomeViews(
                idleContent = root.findViewById(R.id.videoIdleContent),
                runningContent = root.findViewById(R.id.videoRunningContent),
                homeScenes = root.findViewById(R.id.chipGroupVideoHomeScenes),
                idleStatus = null,
                idleSubStatus = null,
                runningStatus = root.findViewById(R.id.tvHeroStatus),
                runningStatusDot = root.findViewById(R.id.viewVideoRunningStatusDot),
                runningSubStatus = root.findViewById(R.id.tvHeroSubStatus),
                elapsed = root.findViewById(R.id.tvVideoElapsed),
                runningMeta = root.findViewById(R.id.tvVideoRunningMeta),
                confirmedList = root.findViewById(R.id.videoConfirmedList),
                audioLevel = root.findViewById(R.id.tvAudioLevel),
                audioProgress = root.findViewById(R.id.pbAudio),
                startButton = root.findViewById(R.id.btnToggle),
                stopButton = root.findViewById(R.id.btnVideoStop),
                targetLanguageLabel = root.findViewById(R.id.tvVideoTargetLanguageLabel),
                currentTranslation = root.findViewById(R.id.tvLiveZh),
                sourceTail = root.findViewById(R.id.tvLiveJa),
                transcriptPath = root.findViewById(R.id.tvTranscriptPath),
                sourceLanguage = root.findViewById(R.id.acVideoSourceLang),
                targetLanguage = root.findViewById(R.id.acVideoTargetLang),
                planCard = root.findViewById(R.id.cardVideoPlan),
                planSummary = root.findViewById(R.id.tvVideoPlanSummary),
                profileSummary = root.findViewById(R.id.tvCurrentProfile),
                openSceneLibraryButton = root.findViewById(R.id.btnVideoOpenPlanLibrary),
                sessionContext = root.findViewById(R.id.etVideoSessionContext),
                analyzeContextButton = root.findViewById(R.id.btnVideoAnalyzeContext),
                analyzeContextStatus = root.findViewById(R.id.tvVideoAnalyzeStatus),
                overlayPermissionRow = root.findViewById(R.id.rowOverlayPermission),
                overlayPermissionDot = root.findViewById(R.id.viewOverlayPermissionDot),
                overlayPermissionStatus = root.findViewById(R.id.tvOverlayPermissionStatus),
                overlayPermissionSettings = root.findViewById(R.id.btnOverlayPermissionSettings),
                videoSessionUrl = root.findViewById(R.id.etVideoSessionUrl),
            )
        }
    }
}

/** 同一份实现按 mode 隔离 Store、文案与动作。 */
internal class ModeHomeController(
    private val context: Context,
    private val mode: TranslationMode,
    internal val views: ModeHomeViews,
    private val toggleSession: (captureMode: String) -> Unit,
    private val openSceneLibrary: (mode: TranslationMode, returnTabId: Int) -> Unit,
    private val openOverlaySettings: () -> Unit,
) {
    private val captureMode = UiRuntimeStatus.captureMode(mode)
    private val returnTabId = when (mode) {
        TranslationMode.INTERPRETATION -> R.id.nav_interp
        TranslationMode.VIDEO -> R.id.nav_video
    }

    fun setup() {
        setupLanguageControls()
        views.planCard.setOnClickListener { openSceneLibrary(mode, returnTabId) }
        views.openSceneLibraryButton.setOnClickListener { openSceneLibrary(mode, returnTabId) }
        views.startButton.setOnClickListener { toggleSession(captureMode) }
        views.stopButton.setOnClickListener { toggleSession(captureMode) }
        views.overlayPermissionRow?.setOnClickListener { openOverlaySettings() }
        views.overlayPermissionSettings?.setOnClickListener { openOverlaySettings() }
        refreshConfiguration()
    }

    fun refreshConfiguration() {
        setupHomeSceneChips()
        renderLanguageControls()
    }

    internal fun refreshHomeScenesForTest() = setupHomeSceneChips()

    private fun setupHomeSceneChips() {
        val current = TranslationPlanStore.loadDraft(context, mode)
        val selectedSceneId = SceneLibraryStore.resolve(context, mode, current.scenePresetId).id
        views.homeScenes.removeAllViews()
        SceneLibraryStore.list(context, mode).forEach { scene ->
            views.homeScenes.addView(Chip(context).apply {
                text = scene.label
                isCheckable = true
                isCheckedIconVisible = false
                isChecked = scene.id == selectedSceneId
                minHeight = resources.getDimensionPixelSize(R.dimen.touch_target)
                chipBackgroundColor = context.getColorStateList(R.color.selector_scene_chip_bg)
                setTextColor(context.getColorStateList(R.color.selector_scene_chip_text))
                chipStrokeWidth = 0f
                setOnClickListener {
                    val latest = TranslationPlanStore.loadDraft(context, mode)
                    TranslationPlanStore.saveDraft(context, latest.copy(scenePresetId = scene.id))
                    updatePlanSummary()
                }
            })
        }
    }

    private fun setupLanguageControls() {
        views.sourceLanguage.setSimpleItems(TranslationLanguageCatalog.sources.map { it.label }.toTypedArray())
        views.targetLanguage.setSimpleItems(TranslationLanguageCatalog.targets.map { it.label }.toTypedArray())
        listOf(views.sourceLanguage, views.targetLanguage).forEach { dropdown ->
            dropdown.setOnClickListener { dropdown.showDropDown() }
        }
        views.sourceLanguage.setOnItemClickListener { _, _, position, _ ->
            val code = TranslationLanguageCatalog.sources.getOrNull(position)?.code
                ?: return@setOnItemClickListener
            val current = TranslationPlanStore.loadDraft(context, mode)
            TranslationPlanStore.saveDraft(context, current.copy(sourceLanguageCode = code))
            renderLanguageControls()
        }
        views.targetLanguage.setOnItemClickListener { _, _, position, _ ->
            val code = TranslationLanguageCatalog.targets.getOrNull(position)?.code
                ?: return@setOnItemClickListener
            val current = TranslationPlanStore.loadDraft(context, mode)
            TranslationPlanStore.saveDraft(context, current.copy(targetLanguageCode = code))
            renderLanguageControls()
        }
    }

    private fun renderLanguageControls() {
        val plan = TranslationPlanStore.loadDraft(context, mode)
        views.sourceLanguage.setText(TranslationLanguageCatalog.source(plan.sourceLanguageCode).label, false)
        val targetLabel = TranslationLanguageCatalog.target(plan.targetLanguageCode).label
        views.targetLanguage.setText(targetLabel, false)
        views.targetLanguageLabel.text = "目标译文 · $targetLabel"
        updatePlanSummary()
    }

    private fun updatePlanSummary() {
        val plan = TranslationPlanStore.loadDraft(context, mode)
        val scene = SceneLibraryStore.resolve(context, mode, plan.scenePresetId)
        views.planSummary.text = "${scene.label} · ${plan.directionLabel}"
        views.profileSummary.text = scene.instruction.replace('\n', ' ').take(48)
            .ifEmpty { "点击管理场景库；本场上下文在主页填写" }
    }

    fun render(status: UiRuntimeStatus) {
        val active = status.serviceRunning && status.captureMode == captureMode
        views.idleContent.visibility = if (active) View.GONE else View.VISIBLE
        views.runningContent.visibility = if (active) View.VISIBLE else View.GONE
        views.sourceLanguage.isEnabled = !status.serviceRunning
        views.targetLanguage.isEnabled = !status.serviceRunning
        views.startButton.isEnabled = !status.serviceRunning

        if (mode == TranslationMode.INTERPRETATION) {
            views.idleStatus?.text = if (status.serviceRunning && !active) "视频运行中" else "待开始"
            views.idleSubStatus?.text = if (status.serviceRunning && !active) {
                "当前正在运行视频字幕，请先停止后再开同传"
            } else {
                "麦克风实时同传"
            }
        } else {
            renderOverlayPermission(status.overlayAllowed)
        }
        if (!active) return

        val plan = TranslationPlanStore.loadDraft(context, mode)
        val sourceCode = status.sourceLanguageCode.ifBlank { plan.sourceLanguageCode }
        val targetCode = status.targetLanguageCode.ifBlank { plan.targetLanguageCode }
        val sceneId = status.scenePresetId.ifBlank { plan.scenePresetId }
        val direction = "${TranslationLanguageCatalog.source(sourceCode).label} → " +
            TranslationLanguageCatalog.target(targetCode).label
        val scene = status.sceneLabel.ifBlank {
            SceneLibraryStore.resolve(context, mode, sceneId).label
        }
        views.runningStatus.text = status.activeStatusText
        ViewCompat.setBackgroundTintList(
            views.runningStatusDot,
            context.getColorStateList(status.activeStatusColorRes),
        )
        if (mode == TranslationMode.VIDEO) {
            views.runningSubStatus?.text = buildString {
                append("其他应用音频 · ")
                append(if (status.overlayAllowed) "悬浮字幕已开启" else "悬浮字幕未授权")
                if (status.currentKeyLabel.isNotEmpty()) append(" · ").append(status.currentKeyLabel)
            }
        }
        views.elapsed.text = formatRunningElapsed(status.startedAtMs, status.sampledAtMs)
        views.runningMeta.text = "$direction · $scene"
        views.audioLevel.text = "${status.audioLevelPct}%"
        views.audioProgress.progress = status.audioLevelPct
        renderConfirmedTranslations(status.confirmedTranslations)
        views.currentTranslation.text = status.currentTranslation.trim()
            .ifBlank { status.confirmedTranslations.lastOrNull().orEmpty() }
            .ifBlank { "等待译文…" }
        views.sourceTail.text = status.sourceTail.trim().ifBlank { "等待原文输入…" }
        views.transcriptPath.text = if (status.transcriptPath.isNotEmpty()) {
            "本场记录：${status.transcriptPath}"
        } else {
            "自动保存到应用内历史"
        }
    }

    private fun renderOverlayPermission(allowed: Boolean) {
        views.overlayPermissionStatus?.text = if (allowed) {
            "已授权，可在其他应用上显示字幕"
        } else {
            "未授权，开始视频字幕前需要开启"
        }
        views.overlayPermissionDot?.let { dot ->
            ViewCompat.setBackgroundTintList(
                dot,
                context.getColorStateList(if (allowed) R.color.success else R.color.error),
            )
        }
        views.overlayPermissionSettings?.visibility = if (allowed) View.GONE else View.VISIBLE
    }

    private fun renderConfirmedTranslations(translations: List<String>) {
        val visible = translations.map(String::trim).filter(String::isNotEmpty).takeLast(6)
        val renderKey = visible.joinToString("\u0000")
        if (views.confirmedList.tag == renderKey) return
        views.confirmedList.tag = renderKey
        views.confirmedList.removeAllViews()
        if (visible.isEmpty()) {
            views.confirmedList.addView(createConfirmedTranslationCard("等待语音…", 14f, 1f, R.color.text_muted))
            return
        }
        visible.forEachIndexed { index, line ->
            views.confirmedList.addView(
                createConfirmedTranslationCard(
                    text = line,
                    textSize = 16f,
                    alpha = 0.48f + (index + 1).toFloat() / visible.size * 0.34f,
                    colorRes = R.color.text_secondary,
                ),
            )
        }
    }

    private fun createConfirmedTranslationCard(
        text: String,
        textSize: Float,
        alpha: Float,
        colorRes: Int,
    ): TextView = TextView(context).apply {
        this.text = text
        this.textSize = textSize
        this.alpha = alpha
        setTextColor(context.getColor(colorRes))
        val padding = resources.getDimensionPixelSize(R.dimen.grid_4)
        setPadding(0, padding, 0, padding)
    }

    private fun formatRunningElapsed(startedAtMs: Long, sampledAtMs: Long): String {
        if (startedAtMs <= 0L) return "00:00"
        return formatElapsedDuration(sampledAtMs - startedAtMs)
    }
}
