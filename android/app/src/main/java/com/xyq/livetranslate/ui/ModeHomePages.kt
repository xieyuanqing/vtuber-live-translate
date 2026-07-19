package com.xyq.livetranslate.ui

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.xyq.livetranslate.CaptureService
import com.xyq.livetranslate.R
import com.xyq.livetranslate.SceneLibraryStore
import com.xyq.livetranslate.StatusBus
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
    val pauseButton: MaterialButton,
    val stopButton: MaterialButton,
    val targetLanguageLabel: TextView,
    val currentTranslation: TextView,
    val sourceTail: TextView,
    val transcriptPath: TextView,
    val sourceLanguage: MaterialAutoCompleteTextView,
    val targetLanguage: MaterialAutoCompleteTextView,
    val languageSwapButton: TextView,
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
                pauseButton = root.findViewById(R.id.btnInterpPause),
                stopButton = root.findViewById(R.id.btnInterpStop),
                targetLanguageLabel = root.findViewById(R.id.tvInterpTargetLanguageLabel),
                currentTranslation = root.findViewById(R.id.tvInterpZh),
                sourceTail = root.findViewById(R.id.tvInterpJa),
                transcriptPath = root.findViewById(R.id.tvInterpTranscriptPath),
                sourceLanguage = root.findViewById(R.id.acInterpSourceLang),
                targetLanguage = root.findViewById(R.id.acInterpTargetLang),
                languageSwapButton = root.findViewById(R.id.btnInterpSwapLang),
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
                pauseButton = root.findViewById(R.id.btnVideoPause),
                stopButton = root.findViewById(R.id.btnVideoStop),
                targetLanguageLabel = root.findViewById(R.id.tvVideoTargetLanguageLabel),
                currentTranslation = root.findViewById(R.id.tvLiveZh),
                sourceTail = root.findViewById(R.id.tvLiveJa),
                transcriptPath = root.findViewById(R.id.tvTranscriptPath),
                sourceLanguage = root.findViewById(R.id.acVideoSourceLang),
                targetLanguage = root.findViewById(R.id.acVideoTargetLang),
                languageSwapButton = root.findViewById(R.id.btnVideoSwapLang),
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
    private var renderedConfirmedTranslations: List<String>? = null

    fun setup() {
        setupLanguageControls()
        views.planCard.setOnClickListener { openSceneLibrary(mode, returnTabId) }
        views.openSceneLibraryButton.setOnClickListener { openSceneLibrary(mode, returnTabId) }
        views.startButton.setOnClickListener { toggleSession(captureMode) }
        views.pauseButton.setOnClickListener {
            context.startService(
                Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_TOGGLE_PAUSE),
            )
        }
        views.stopButton.setOnClickListener { confirmStop() }
        views.languageSwapButton.setOnClickListener { swapLanguages() }
        views.overlayPermissionRow?.setOnClickListener { openOverlaySettings() }
        views.overlayPermissionSettings?.setOnClickListener { openOverlaySettings() }
        refreshConfiguration()
    }

    private fun confirmStop() {
        val label = if (mode == TranslationMode.INTERPRETATION) "同传" else "视频字幕"
        MaterialAlertDialogBuilder(context)
            .setTitle("停止$label？")
            .setMessage("停止后需要重新开始会话。")
            .setNegativeButton("取消", null)
            .setPositiveButton("停止") { _, _ -> toggleSession(captureMode) }
            .show()
    }

    private fun swapLanguages() {
        if (StatusBus.serviceRunning) {
            Toast.makeText(context, "运行中无法切换语言", Toast.LENGTH_SHORT).show()
            return
        }
        val plan = TranslationPlanStore.loadDraft(context, mode)
        if (plan.sourceLanguageCode.equals("auto", ignoreCase = true)) {
            Toast.makeText(context, "源语言为自动检测时无法交换", Toast.LENGTH_SHORT).show()
            return
        }
        val sourceAsTarget = TranslationLanguageCatalog.targets.any {
            it.code.equals(plan.sourceLanguageCode, ignoreCase = true)
        }
        val targetAsSource = TranslationLanguageCatalog.sources.any {
            it.code.equals(plan.targetLanguageCode, ignoreCase = true)
        }
        if (!sourceAsTarget || !targetAsSource) {
            Toast.makeText(context, "当前语言方向不支持交换", Toast.LENGTH_SHORT).show()
            return
        }
        TranslationPlanStore.saveDraft(
            context,
            plan.copy(
                sourceLanguageCode = plan.targetLanguageCode,
                targetLanguageCode = plan.sourceLanguageCode,
            ),
        )
        renderLanguageControls()
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
        // 主页只显示场景名与语言方向；场景提示词不在主页展开。
        views.planSummary.text = "${scene.label} · ${plan.directionLabel}"
        views.profileSummary.visibility = View.GONE
        views.profileSummary.text = ""
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
        views.pauseButton.text = if (status.paused) "继续" else "暂停"
        if (mode == TranslationMode.VIDEO) {
            views.runningSubStatus?.text = buildString {
                append("其他应用音频 · ")
                append(if (status.overlayAllowed) "悬浮字幕已开启" else "悬浮字幕未授权")
                if (status.currentKeyLabel.isNotEmpty()) append(" · ").append(status.currentKeyLabel)
                val hint = listeningHint(status)
                if (hint != null) append(" · ").append(hint)
            }
        } else {
            // 同传运行态副状态：静音/聆听提示（C7）。
            views.runningMeta.text = buildString {
                append("$direction · $scene")
                val hint = listeningHint(status)
                if (hint != null) append(" · ").append(hint)
            }
        }
        views.elapsed.text = formatRunningElapsed(status.startedAtMs, status.sampledAtMs)
        if (mode == TranslationMode.VIDEO) {
            views.runningMeta.text = "$direction · $scene"
        }
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
        // 已授权时整行隐藏，缺权限时显示警告横幅。
        views.overlayPermissionRow?.visibility = if (allowed) View.GONE else View.VISIBLE
        if (allowed) return
        views.overlayPermissionStatus?.text = "悬浮字幕未授权"
        views.overlayPermissionSettings?.visibility = View.VISIBLE
    }

    private fun renderConfirmedTranslations(translations: List<String>) {
        // 展示 StatusBus 提供的全部确认行（当前上限 80），不再截成 6 条。
        val visible = translations.map(String::trim).filter(String::isNotEmpty)
        if (visible == renderedConfirmedTranslations) return
        val scrollParent = findConfirmedScrollParent()
        val shouldFollow = renderedConfirmedTranslations == null || isNearBottom(scrollParent)
        if (visible.isEmpty()) {
            views.confirmedList.removeAllViews()
            views.confirmedList.addView(createConfirmedTranslationCard("等待语音…", 14f, 1f, R.color.text_muted))
            renderedConfirmedTranslations = emptyList()
            return
        }

        val old = renderedConfirmedTranslations.orEmpty()
        when {
            old.isNotEmpty() && visible.size >= old.size && visible.subList(0, old.size) == old -> {
                visible.drop(old.size).forEach(::appendConfirmedTranslation)
            }
            old.isNotEmpty() && old.size == visible.size && old.drop(1) == visible.dropLast(1) -> {
                views.confirmedList.removeViewAt(0)
                appendConfirmedTranslation(visible.last())
            }
            else -> {
                views.confirmedList.removeAllViews()
                visible.forEach(::appendConfirmedTranslation)
            }
        }
        renderedConfirmedTranslations = visible.toList()
        visible.indices.forEach { index ->
            val ageBoost = (index + 1).toFloat() / visible.size
            views.confirmedList.getChildAt(index)?.alpha = 0.48f + ageBoost * 0.34f
        }

        // 用户原本就在底部时才继续跟随；上滑回看后不强制拉回。
        if (shouldFollow) {
            views.confirmedList.post {
                when (scrollParent) {
                    is android.widget.ScrollView -> scrollParent.fullScroll(View.FOCUS_DOWN)
                    is androidx.core.widget.NestedScrollView -> scrollParent.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    private fun appendConfirmedTranslation(line: String) {
        views.confirmedList.addView(
            createConfirmedTranslationCard(
                text = line,
                textSize = 16f,
                alpha = 1f,
                colorRes = R.color.text_secondary,
            ),
        )
    }

    private fun findConfirmedScrollParent(): View? {
        var parent = views.confirmedList.parent
        while (parent is View) {
            if (parent is android.widget.ScrollView || parent is androidx.core.widget.NestedScrollView) {
                return parent
            }
            parent = parent.parent
        }
        return null
    }

    private fun isNearBottom(scrollParent: View?): Boolean {
        val distance = when (scrollParent) {
            is android.widget.ScrollView -> {
                val child = scrollParent.getChildAt(0) ?: return true
                child.height - (scrollParent.scrollY + scrollParent.height)
            }
            is androidx.core.widget.NestedScrollView -> {
                val child = scrollParent.getChildAt(0) ?: return true
                child.height - (scrollParent.scrollY + scrollParent.height)
            }
            else -> return true
        }
        return distance <= context.resources.getDimensionPixelSize(R.dimen.touch_target)
    }

    private fun listeningHint(status: UiRuntimeStatus): String? {
        if (status.paused) return null
        if (status.connState != "ready") return null
        if (status.audioLevelPct <= 2) return "静音中"
        val lastChange = status.lastSubtitleAtMs.takeIf { it > 0L } ?: status.startedAtMs
        if (lastChange > 0L && status.sampledAtMs - lastChange >= 8_000L) return "聆听中…"
        return null
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
