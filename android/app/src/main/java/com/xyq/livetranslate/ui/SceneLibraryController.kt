package com.xyq.livetranslate.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.xyq.livetranslate.R
import com.xyq.livetranslate.SceneLibraryStore
import com.xyq.livetranslate.ScenePromptPreset
import com.xyq.livetranslate.TranslationMode
import com.xyq.livetranslate.TranslationPlanStore

internal data class SceneLibraryViews(
    val modeToggle: MaterialButtonToggleGroup,
    val interpretationButton: MaterialButton,
    val videoButton: MaterialButton,
    val list: LinearLayout,
    val resetButton: MaterialButton,
    val newSceneButton: ExtendedFloatingActionButton,
) {
    companion object {
        fun bind(root: View): SceneLibraryViews = SceneLibraryViews(
            modeToggle = root.findViewById(R.id.toggleSceneLibraryMode),
            interpretationButton = root.findViewById(R.id.btnSceneLibraryInterp),
            videoButton = root.findViewById(R.id.btnSceneLibraryVideo),
            list = root.findViewById(R.id.sceneLibraryList),
            resetButton = root.findViewById(R.id.btnResetSceneLibrary),
            newSceneButton = root.findViewById(R.id.fabNewScene),
        )
    }
}

internal class SceneLibraryController(
    private val context: Context,
    private val views: SceneLibraryViews,
    private val openPage: (returnTabId: Int) -> Unit,
    private val onSceneChanged: (TranslationMode) -> Unit,
    private val toast: (String) -> Unit,
) {
    private companion object {
        const val STATE_SCENE_LIBRARY_MODE = "scene_library_mode"
    }

    private val layoutInflater = LayoutInflater.from(context)
    private var mode: TranslationMode = TranslationMode.INTERPRETATION

    fun restoreState(savedState: Bundle?) {
        mode = savedState?.getString(STATE_SCENE_LIBRARY_MODE)
            ?.let { key -> TranslationMode.entries.firstOrNull { it.storageKey == key } }
            ?: TranslationMode.INTERPRETATION
    }

    fun saveState(outState: Bundle) {
        outState.putString(STATE_SCENE_LIBRARY_MODE, mode.storageKey)
    }

    fun setup() {
        views.modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            mode = when (checkedId) {
                views.videoButton.id -> TranslationMode.VIDEO
                else -> TranslationMode.INTERPRETATION
            }
            reload()
        }
        views.newSceneButton.setOnClickListener { showSceneEditor() }
        views.resetButton.setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle("恢复默认场景？")
                .setMessage("会替换当前模式的全部场景和默认选择；正在使用已删除场景的模式会回退到默认场景。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复") { _, _ ->
                    SceneLibraryStore.reset(context, mode)
                    notifySceneChanged()
                    toast("已恢复${mode.label}默认场景")
                }
                .show()
        }
    }

    fun open(mode: TranslationMode, returnTabId: Int) {
        this.mode = mode
        openPage(returnTabId)
    }

    fun reload() {
        val checkedId = if (mode == TranslationMode.VIDEO) {
            views.videoButton.id
        } else {
            views.interpretationButton.id
        }
        if (views.modeToggle.checkedButtonId != checkedId) {
            views.modeToggle.check(checkedId)
        }
        val items = SceneLibraryStore.list(context, mode)
        val defaultId = SceneLibraryStore.default(context, mode).id
        val inUseId = SceneLibraryStore.resolve(
            context,
            mode,
            TranslationPlanStore.loadDraft(context, mode).scenePresetId,
        ).id
        views.list.removeAllViews()
        items.forEach { scene ->
            views.list.addView(
                buildSceneCard(scene, scene.id == defaultId, scene.id == inUseId),
            )
        }
    }

    private fun buildSceneCard(
        scene: ScenePromptPreset,
        isDefault: Boolean,
        isInUse: Boolean,
    ): View {
        val card = layoutInflater.inflate(R.layout.item_scene_preset, views.list, false)
        card.findViewById<TextView>(R.id.tvSceneIcon).text =
            scene.label.firstOrNull()?.toString() ?: "场"
        card.findViewById<TextView>(R.id.tvSceneName).text = scene.label
        card.findViewById<TextView>(R.id.tvSceneInstruction).text = scene.instruction
        card.findViewById<Chip>(R.id.chipSceneDefault).visibility =
            if (isDefault) View.VISIBLE else View.GONE
        card.findViewById<Chip>(R.id.chipSceneInUse).visibility =
            if (isInUse) View.VISIBLE else View.GONE
        card.findViewById<MaterialButton>(R.id.btnUseScene).apply {
            visibility = if (isInUse) View.GONE else View.VISIBLE
            setOnClickListener {
                val draft = TranslationPlanStore.loadDraft(context, mode)
                TranslationPlanStore.saveDraft(
                    context,
                    draft.copy(scenePresetId = scene.id),
                )
                notifySceneChanged()
                toast("已使用：${scene.label}")
            }
        }
        card.findViewById<MaterialButton>(R.id.btnSetDefaultScene).apply {
            visibility = if (isDefault) View.GONE else View.VISIBLE
            setOnClickListener {
                if (SceneLibraryStore.setDefault(context, mode, scene.id)) {
                    val draft = TranslationPlanStore.loadDraft(context, mode)
                    TranslationPlanStore.saveDraft(
                        context,
                        draft.copy(scenePresetId = scene.id),
                    )
                    notifySceneChanged()
                    toast("已设为${mode.label}默认场景")
                } else {
                    toast("场景库数据异常，请先恢复模板")
                }
            }
        }
        card.findViewById<MaterialButton>(R.id.btnEditScene).setOnClickListener {
            showSceneEditor(scene)
        }
        card.findViewById<MaterialButton>(R.id.btnDeleteScene).setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle("删除“${scene.label}”？")
                .setMessage("正在使用该场景的模式下次启动会回退到当前默认场景。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除") { _, _ ->
                    if (SceneLibraryStore.delete(context, mode, scene.id)) {
                        notifySceneChanged()
                        toast("已删除：${scene.label}")
                    } else {
                        val message = if (SceneLibraryStore.list(context, mode).size <= 1) {
                            "每种模式至少保留一个场景"
                        } else {
                            "场景库数据异常，请先恢复模板"
                        }
                        toast(message)
                    }
                }
                .show()
        }
        return card
    }

    private fun showSceneEditor(existing: ScenePromptPreset? = null) {
        val content = layoutInflater.inflate(R.layout.dialog_scene_editor, null, false)
        val nameLayout = content.findViewById<TextInputLayout>(R.id.tilSceneName)
        val promptLayout = content.findViewById<TextInputLayout>(R.id.tilSceneInstruction)
        val name = content.findViewById<TextInputEditText>(R.id.etSceneName)
        val prompt = content.findViewById<TextInputEditText>(R.id.etSceneInstruction)
        name.setText(existing?.label.orEmpty())
        prompt.setText(existing?.instruction.orEmpty())

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (existing == null) "新建${mode.label}场景" else "编辑场景")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val label = name.text?.toString().orEmpty().trim()
                val instruction = prompt.text?.toString().orEmpty().trim()
                nameLayout.error = if (label.isEmpty()) "请填写场景名称" else null
                promptLayout.error = if (instruction.isEmpty()) "请填写场景提示词" else null
                if (label.isEmpty() || instruction.isEmpty()) return@setOnClickListener

                val saved = if (existing == null) {
                    SceneLibraryStore.create(context, mode, label, instruction)
                } else {
                    if (SceneLibraryStore.update(
                            context,
                            mode,
                            existing.copy(label = label, instruction = instruction),
                        )
                    ) {
                        existing
                    } else {
                        null
                    }
                }
                if (saved == null) {
                    promptLayout.error = "场景库数据异常，请先恢复模板"
                    return@setOnClickListener
                }
                notifySceneChanged()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun notifySceneChanged() {
        onSceneChanged(mode)
        reload()
    }
}
