package com.xyq.livetranslate

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * 方案编辑：名称 + 场景 + 长期提示词。
 * 本版本不包含术语库、临时本场上下文或 AI 资料整理。
 */
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
        val chips = view.findViewById<ChipGroup>(R.id.chipGroupPlanScenes)
        val customSceneLayout = view.findViewById<TextInputLayout>(R.id.tilPlanCustomScene)
        val customScene = view.findViewById<TextInputEditText>(R.id.etPlanCustomScene)
        val advanced = view.findViewById<TextInputEditText>(R.id.etPlanCustom)
        val name = view.findViewById<TextInputEditText>(R.id.etPlanName)
        val apply = view.findViewById<MaterialButton>(R.id.btnSavePlan)

        title.text = if (mode == TranslationMode.INTERPRETATION) "同传方案" else "视频方案"
        name.setText(arguments?.getString(ARG_NAME).orEmpty())
        customScene.setText(plan.customSceneInstruction)
        advanced.setText(plan.advancedInstruction)

        fun renderPlan(newPlan: TranslationPlan) {
            plan = newPlan.copy(mode = mode, glossaryKey = "").normalized()
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
                        plan = plan.copy(scenePresetId = preset.id, glossaryKey = "")
                        customSceneLayout.visibility =
                            if (preset.id == "custom") View.VISIBLE else View.GONE
                    }
                }
            })
        }
        renderPlan(plan)

        apply.setOnClickListener {
            val selectedScene = chips.findViewById<Chip>(chips.checkedChipId)?.tag as? String
                ?: plan.scenePresetId
            plan = plan.copy(
                scenePresetId = selectedScene,
                customSceneInstruction = customScene.text?.toString().orEmpty(),
                advancedInstruction = advanced.text?.toString().orEmpty(),
                glossaryKey = "",
            ).normalized()
            TranslationPlanStore.saveDraft(requireContext(), plan)
            name.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { planName ->
                TranslationPlanStore.saveAs(requireContext(), mode, planName, plan)
            }
            listener?.onTranslationPlanApplied(
                mode = mode,
                plan = plan,
                sessionContext = "",
                videoUrl = "",
            )
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?: return
        sheet.layoutParams.height = (resources.displayMetrics.heightPixels * 0.85f).toInt()
        BottomSheetBehavior.from(sheet).state = BottomSheetBehavior.STATE_EXPANDED
    }

    companion object {
        private const val ARG_MODE = "mode"
        private const val ARG_NAME = "name"

        fun newInstance(
            mode: TranslationMode,
            sessionContext: String = "",
            videoUrl: String = "",
            planName: String = "",
        ) = TranslationPlanBottomSheet().apply {
            // sessionContext / videoUrl 保留参数签名兼容旧调用，本版本忽略。
            arguments = Bundle().apply {
                putString(ARG_MODE, mode.storageKey)
                putString(ARG_NAME, planName)
            }
        }
    }
}
