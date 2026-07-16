package com.xyq.livetranslate

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.UUID

class TranslationPlanBottomSheet : BottomSheetDialogFragment() {
    interface Listener {
        fun onTranslationPlanApplied(
            mode: TranslationMode,
            plan: TranslationPlan,
            sessionContext: String,
            videoUrl: String,
        )
    }

    var listener: Listener? = null

    private lateinit var mode: TranslationMode
    private lateinit var plan: TranslationPlan
    private var savedPlans: List<SavedTranslationPlan> = emptyList()
    private var latestAnalysisRequestId = ""
    private var pendingGlossary: GlossaryProfile? = null

    override fun onAttach(context: android.content.Context) {
        super.onAttach(context)
        listener = context as? Listener ?: listener
    }

    override fun onDetach() {
        listener = null
        super.onDetach()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = arguments?.getString(ARG_MODE)
            ?.let { key -> TranslationMode.entries.firstOrNull { it.storageKey == key } }
            ?: TranslationMode.INTERPRETATION
        plan = TranslationPlanStore.loadDraft(requireContext(), mode)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.bottom_sheet_translation_plan, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val title = view.findViewById<TextView>(R.id.tvPlanSheetTitle)
        val saved = view.findViewById<MaterialAutoCompleteTextView>(R.id.acPlanSaved)
        val chips = view.findViewById<ChipGroup>(R.id.chipGroupPlanScenes)
        val customSceneLayout = view.findViewById<TextInputLayout>(R.id.tilPlanCustomScene)
        val customScene = view.findViewById<TextInputEditText>(R.id.etPlanCustomScene)
        val videoUrlLayout = view.findViewById<TextInputLayout>(R.id.tilPlanVideoUrl)
        val videoUrl = view.findViewById<TextInputEditText>(R.id.etPlanVideoUrl)
        val context = view.findViewById<TextInputEditText>(R.id.etPlanSessionContext)
        val analyze = view.findViewById<MaterialButton>(R.id.btnAnalyzeContext)
        val analyzeStatus = view.findViewById<TextView>(R.id.tvAnalyzeStatus)
        val advancedToggle = view.findViewById<MaterialButton>(R.id.btnToggleAdvanced)
        val advancedLayout = view.findViewById<TextInputLayout>(R.id.tilPlanCustom)
        val advanced = view.findViewById<TextInputEditText>(R.id.etPlanCustom)
        val nameLayout = view.findViewById<TextInputLayout>(R.id.tilPlanName)
        val name = view.findViewById<TextInputEditText>(R.id.etPlanName)
        val apply = view.findViewById<MaterialButton>(R.id.btnSavePlan)

        title.text = if (mode == TranslationMode.INTERPRETATION) "同传方案" else "视频方案"
        analyze.text = if (mode == TranslationMode.INTERPRETATION) {
            "AI 整理资料与术语"
        } else {
            "AI 解析本场视频"
        }
        videoUrlLayout.visibility = if (mode == TranslationMode.VIDEO) View.VISIBLE else View.GONE
        context.setText(arguments?.getString(ARG_CONTEXT).orEmpty())
        videoUrl.setText(arguments?.getString(ARG_VIDEO_URL).orEmpty())
        customScene.setText(plan.customSceneInstruction)
        advanced.setText(plan.advancedInstruction)

        fun renderPlan(newPlan: TranslationPlan) {
            plan = newPlan.copy(mode = mode).normalized()
            customScene.setText(plan.customSceneInstruction)
            advanced.setText(plan.advancedInstruction)
            val scene = ScenePromptCatalog.resolve(mode, plan.scenePresetId)
            for (index in 0 until chips.childCount) {
                val chip = chips.getChildAt(index) as? Chip ?: continue
                chip.isChecked = chip.tag == scene.id
            }
            customSceneLayout.visibility = if (scene.id == "custom") View.VISIBLE else View.GONE
        }

        ScenePromptCatalog.presets(mode).forEach { preset ->
            chips.addView(Chip(requireContext()).apply {
                text = preset.label
                tag = preset.id
                isCheckable = true
                isCheckedIconVisible = false
                minHeight = resources.getDimensionPixelSize(R.dimen.touch_target)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        plan = plan.copy(scenePresetId = preset.id)
                        customSceneLayout.visibility =
                            if (preset.id == "custom") View.VISIBLE else View.GONE
                    }
                }
            })
        }
        renderPlan(plan)

        savedPlans = TranslationPlanStore.listSaved(requireContext(), mode)
        val savedLabels = listOf("当前方案") + savedPlans.map { it.name }
        saved.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, savedLabels),
        )
        saved.setText("当前方案", false)
        saved.setOnItemClickListener { _, _, position, _ ->
            if (position > 0) {
                if (latestAnalysisRequestId.isNotEmpty()) {
                    latestAnalysisRequestId = ""
                    analyze.isEnabled = true
                    analyzeStatus.text = "方案已切换，之前的分析结果已忽略"
                }
                pendingGlossary = null
                TranslationPlanStore.applySaved(
                    requireContext(),
                    mode,
                    savedPlans[position - 1].id,
                )?.let(::renderPlan)
            }
        }

        advancedToggle.setOnClickListener {
            val show = advancedLayout.visibility != View.VISIBLE
            advancedLayout.visibility = if (show) View.VISIBLE else View.GONE
            nameLayout.visibility = if (show) View.VISIBLE else View.GONE
            advancedToggle.text = if (show) "收起高级自定义 ⌄" else "高级自定义  ›"
        }

        analyze.setOnClickListener {
            val apiKey = SettingsStore.secondAiApiKey(requireContext())
            if (apiKey.isBlank()) {
                analyzeStatus.text = "请先在设置 → AI 内容分析中填写 API Key"
                return@setOnClickListener
            }
            val material = context.text?.toString().orEmpty().trim()
            val url = videoUrl.text?.toString().orEmpty().trim()
            if (mode == TranslationMode.INTERPRETATION && material.isBlank()) {
                analyzeStatus.text = "请先填写本场背景或资料"
                return@setOnClickListener
            }
            if (mode == TranslationMode.VIDEO && url.isBlank()) {
                analyzeStatus.text = "请先填写 YouTube 视频链接"
                return@setOnClickListener
            }
            val requestId = UUID.randomUUID().toString()
            latestAnalysisRequestId = requestId
            val analysisPlan = plan.normalized()
            val baseUrl = SettingsStore.secondAiBaseUrl(requireContext())
            val model = SettingsStore.secondAiModel(requireContext())
            val format = AiTextClient.Format.fromKey(
                SettingsStore.secondAiFormat(requireContext()),
            )
            analyze.isEnabled = false
            analyzeStatus.text = "正在整理，请稍候…"
            Thread({
                runCatching {
                    val videoInfo = if (mode == TranslationMode.VIDEO) {
                        YouTubeOEmbedClient.fetch(url)
                    } else {
                        null
                    }
                    val source = TranslationLanguageCatalog.source(analysisPlan.sourceLanguageCode)
                    val target = TranslationLanguageCatalog.target(analysisPlan.targetLanguageCode)
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
                    activity?.runOnUiThread {
                        if (requestId != latestAnalysisRequestId || !isAdded) return@runOnUiThread
                        val inputChanged = context.text?.toString().orEmpty().trim() != material ||
                            videoUrl.text?.toString().orEmpty().trim() != url
                        if (inputChanged) {
                            latestAnalysisRequestId = ""
                            analyzeStatus.text = "输入已修改，之前的分析结果已忽略"
                            analyze.isEnabled = true
                            return@runOnUiThread
                        }
                        if (result.sessionContext.isBlank()) {
                            analyzeStatus.text = "AI 没有返回可用背景，请补充资料后重试"
                        } else {
                            context.setText(result.sessionContext)
                            result.glossary?.let { profile ->
                                val candidate = profile.copy(
                                    id = profile.id.ifBlank { UUID.randomUUID().toString() },
                                ).normalized()
                                pendingGlossary = candidate
                                plan = plan.copy(glossaryKey = candidate.id)
                            }
                            analyzeStatus.text = result.note
                        }
                        analyze.isEnabled = true
                    }
                }.onFailure { error ->
                    activity?.runOnUiThread {
                        if (requestId != latestAnalysisRequestId || !isAdded) return@runOnUiThread
                        analyzeStatus.text = "整理失败：${error.message ?: "未知错误"}"
                        analyze.isEnabled = true
                    }
                }
            }, "context-analysis-${mode.storageKey}").start()
        }

        apply.setOnClickListener {
            val selectedScene = chips.findViewById<Chip>(chips.checkedChipId)?.tag as? String
                ?: plan.scenePresetId
            plan = plan.copy(
                scenePresetId = selectedScene,
                customSceneInstruction = customScene.text?.toString().orEmpty(),
                advancedInstruction = advanced.text?.toString().orEmpty(),
            ).normalized()
            pendingGlossary
                ?.takeIf { it.id == plan.glossaryKey }
                ?.let { glossary ->
                    val savedGlossary = GlossaryStore.upsert(requireContext(), glossary)
                    plan = plan.copy(glossaryKey = savedGlossary.id)
                }
            TranslationPlanStore.saveDraft(requireContext(), plan)
            name.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { planName ->
                TranslationPlanStore.saveAs(requireContext(), mode, planName, plan)
            }
            listener?.onTranslationPlanApplied(
                mode = mode,
                plan = plan,
                sessionContext = context.text?.toString().orEmpty().trim(),
                videoUrl = videoUrl.text?.toString().orEmpty().trim(),
            )
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?: return
        sheet.layoutParams.height = (resources.displayMetrics.heightPixels * 0.9f).toInt()
        BottomSheetBehavior.from(sheet).state = BottomSheetBehavior.STATE_EXPANDED
    }

    override fun onDestroyView() {
        latestAnalysisRequestId = ""
        super.onDestroyView()
    }

    companion object {
        private const val ARG_MODE = "mode"
        private const val ARG_CONTEXT = "context"
        private const val ARG_VIDEO_URL = "videoUrl"

        fun newInstance(
            mode: TranslationMode,
            sessionContext: String,
            videoUrl: String = "",
        ) = TranslationPlanBottomSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_MODE, mode.storageKey)
                putString(ARG_CONTEXT, sessionContext)
                putString(ARG_VIDEO_URL, videoUrl)
            }
        }
    }
}
