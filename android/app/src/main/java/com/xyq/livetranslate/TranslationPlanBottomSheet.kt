package com.xyq.livetranslate

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView

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

        fun onSceneLibraryRequested(mode: TranslationMode)
    }

    var listener: Listener? = null

    private lateinit var mode: TranslationMode
    private lateinit var plan: TranslationPlan
    private var savedPlanId: String = ""

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
        savedPlanId = arguments?.getString(ARG_SAVED_ID).orEmpty()
        plan = TranslationPlanStore.listSaved(requireContext(), mode)
            .firstOrNull { it.id == savedPlanId }
            ?.plan
            ?: TranslationPlanStore.loadDraft(requireContext(), mode)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.bottom_sheet_translation_plan, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val title = view.findViewById<TextView>(R.id.tvPlanSheetTitle)
        val sceneField = view.findViewById<MaterialAutoCompleteTextView>(R.id.acPlanScene)
        val nameLayout = view.findViewById<TextInputLayout>(R.id.tilPlanName)
        val advanced = view.findViewById<TextInputEditText>(R.id.etPlanCustom)
        val name = view.findViewById<TextInputEditText>(R.id.etPlanName)
        val apply = view.findViewById<MaterialButton>(R.id.btnSavePlan)
        view.findViewById<View>(R.id.btnClosePlanSheet).setOnClickListener { dismiss() }

        title.text = if (mode == TranslationMode.INTERPRETATION) "同传方案" else "视频方案"
        val initialName = arguments?.getString(ARG_NAME).orEmpty()
        val initialPlan = plan
        name.setText(initialName)
        advanced.setText(plan.advancedInstruction)
        view.findViewById<View>(R.id.btnManageScenes).setOnClickListener {
            val openSceneLibrary = {
                listener?.onSceneLibraryRequested(mode)
                dismiss()
            }
            val hasUnsavedChanges = name.text?.toString().orEmpty() != initialName ||
                advanced.text?.toString().orEmpty() != initialPlan.advancedInstruction ||
                plan != initialPlan
            if (hasUnsavedChanges) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("放弃未保存修改？")
                    .setMessage("进入场景库会关闭当前方案编辑器。")
                    .setNegativeButton("继续编辑", null)
                    .setPositiveButton("放弃并前往") { _, _ -> openSceneLibrary() }
                    .show()
            } else {
                openSceneLibrary()
            }
        }

        val scenes = SceneLibraryStore.list(requireContext(), mode)
        val selectedScene = SceneLibraryStore.resolve(requireContext(), mode, plan.scenePresetId)
        sceneField.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, scenes.map { it.label }),
        )
        sceneField.setText(selectedScene.label, false)
        sceneField.setOnItemClickListener { _, _, position, _ ->
            scenes.getOrNull(position)?.let { plan = plan.copy(scenePresetId = it.id) }
        }

        apply.setOnClickListener {
            plan = plan.copy(
                advancedInstruction = advanced.text?.toString().orEmpty(),
            ).normalized()
            val planName = name.text?.toString()?.trim().orEmpty()
            nameLayout.error = null
            if (savedPlanId.isNotEmpty()) {
                val updated = TranslationPlanStore.updateSaved(
                    requireContext(), mode, savedPlanId, planName, plan,
                )
                if (updated == null) {
                    nameLayout.error = "原方案已不存在，请关闭后重新打开方案库"
                    name.requestFocus()
                    return@setOnClickListener
                }
            } else {
                TranslationPlanStore.saveDraft(requireContext(), plan)
                if (planName.isNotEmpty()) {
                    TranslationPlanStore.saveAs(requireContext(), mode, planName, plan)
                }
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

    @Suppress("DEPRECATION")
    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?: return
        val height = (resources.displayMetrics.heightPixels * 0.85f).toInt()
        sheet.layoutParams = sheet.layoutParams.apply { this.height = height }
        sheet.requestLayout()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        BottomSheetBehavior.from(sheet).apply {
            peekHeight = height
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    companion object {
        private const val ARG_MODE = "mode"
        private const val ARG_NAME = "name"
        private const val ARG_SAVED_ID = "saved_id"

        fun newInstance(
            mode: TranslationMode,
            sessionContext: String = "",
            videoUrl: String = "",
            planName: String = "",
            savedPlanId: String = "",
        ) = TranslationPlanBottomSheet().apply {
            // sessionContext / videoUrl 保留参数签名兼容旧调用，本版本忽略。
            arguments = Bundle().apply {
                putString(ARG_MODE, mode.storageKey)
                putString(ARG_NAME, planName)
                putString(ARG_SAVED_ID, savedPlanId)
            }
        }
    }
}
