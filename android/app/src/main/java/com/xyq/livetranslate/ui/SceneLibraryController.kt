package com.xyq.livetranslate.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
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
            val selectedMode = when (checkedId) {
                views.videoButton.id -> TranslationMode.VIDEO
                else -> TranslationMode.INTERPRETATION
            }
            if (selectedMode == mode) return@addOnButtonCheckedListener
            mode = selectedMode
            reload()
        }
        views.newSceneButton.setOnClickListener { showSceneEditor() }
        views.resetButton.setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.rt_dialog_reset_scenes_title))
                .setMessage(context.getString(R.string.rt_dialog_reset_scenes_message))
                .setNegativeButton(context.getString(R.string.rt_action_cancel), null)
                .setPositiveButton(context.getString(R.string.rt_action_reset)) { _, _ ->
                    SceneLibraryStore.reset(context, mode)
                    notifySceneChanged()
                    toast(context.getString(R.string.rt_toast_scenes_reset, modeLocalized(mode)))
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
        card.findViewById<TextView>(R.id.tvSceneName).text = scene.label
        card.findViewById<TextView>(R.id.tvSceneInstruction).text =
            scene.instruction.replace('\n', ' ')
        card.findViewById<TextView>(R.id.chipSceneDefault).visibility =
            if (isDefault) View.VISIBLE else View.GONE
        card.findViewById<TextView>(R.id.chipSceneInUse).visibility =
            if (isInUse) View.VISIBLE else View.GONE
        // 整行点击 = 使用该场景。
        card.setOnClickListener { useScene(scene) }
        card.findViewById<ImageButton>(R.id.btnSceneMore).setOnClickListener { anchor ->
            PopupMenu(context, anchor).apply {
                menu.add(0, 1, 0, context.getString(R.string.rt_action_edit))
                if (!isDefault) menu.add(0, 2, 1, context.getString(R.string.rt_action_set_default))
                menu.add(0, 3, 2, context.getString(R.string.rt_action_delete))
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> {
                            showSceneEditor(scene)
                            true
                        }
                        2 -> {
                            setDefaultScene(scene)
                            true
                        }
                        3 -> {
                            confirmDeleteScene(scene)
                            true
                        }
                        else -> false
                    }
                }
                show()
            }
        }
        return card
    }

    private fun useScene(scene: ScenePromptPreset) {
        val draft = TranslationPlanStore.loadDraft(context, mode)
        if (draft.scenePresetId == scene.id) {
            toast(context.getString(R.string.rt_toast_scene_already_in_use, scene.label))
            return
        }
        TranslationPlanStore.saveDraft(context, draft.copy(scenePresetId = scene.id))
        notifySceneChanged()
        toast(context.getString(R.string.rt_toast_scene_switched, scene.label))
    }

    /** 「设为默认」只改以后的默认项；本次用哪个场景由「使用」单独决定。 */
    private fun setDefaultScene(scene: ScenePromptPreset) {
        if (!SceneLibraryStore.setDefault(context, mode, scene.id)) {
            toast(context.getString(R.string.rt_scene_data_corrupt_hint))
            return
        }
        val inUse = SceneLibraryStore.resolve(
            context,
            mode,
            TranslationPlanStore.loadDraft(context, mode).scenePresetId,
        )
        notifySceneChanged()
        toast(
            if (inUse.id == scene.id) {
                context.getString(R.string.rt_toast_set_default_scene, modeLocalized(mode))
            } else {
                context.getString(R.string.rt_toast_set_default_scene_kept_current, modeLocalized(mode), inUse.label)
            },
        )
    }

    private fun confirmDeleteScene(scene: ScenePromptPreset) {
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.rt_dialog_delete_scene_title, scene.label))
            .setMessage(context.getString(R.string.rt_dialog_delete_scene_message))
            .setNegativeButton(context.getString(R.string.rt_action_cancel), null)
            .setPositiveButton(context.getString(R.string.rt_action_delete)) { _, _ ->
                if (SceneLibraryStore.delete(context, mode, scene.id)) {
                    notifySceneChanged()
                    toast(context.getString(R.string.rt_toast_scene_deleted, scene.label))
                } else {
                    val message = if (SceneLibraryStore.list(context, mode).size <= 1) {
                        context.getString(R.string.rt_toast_keep_at_least_one_scene)
                    } else {
                        context.getString(R.string.rt_scene_data_corrupt_hint)
                    }
                    toast(message)
                }
            }
            .show()
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
            .setTitle(
                if (existing == null) {
                    context.getString(R.string.rt_dialog_new_scene_title, modeLocalized(mode))
                } else {
                    context.getString(R.string.rt_dialog_edit_scene_title)
                }
            )
            .setView(content)
            .setNegativeButton(context.getString(R.string.rt_action_cancel), null)
            .setPositiveButton(context.getString(R.string.rt_action_save), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val label = name.text?.toString().orEmpty().trim()
                val instruction = prompt.text?.toString().orEmpty().trim()
                nameLayout.error = if (label.isEmpty()) context.getString(R.string.rt_error_fill_scene_name) else null
                promptLayout.error = if (instruction.isEmpty()) context.getString(R.string.rt_error_fill_scene_prompt) else null
                if (label.isEmpty() || instruction.isEmpty()) return@setOnClickListener

                val saved = if (existing == null) {
                    SceneLibraryStore.create(context, mode, label, instruction)
                } else {
                    if (SceneLibraryStore.update(
                            context,
                            mode,
                            existing.copy(labelText = label, instruction = instruction),
                        )
                    ) {
                        existing
                    } else {
                        null
                    }
                }
                if (saved == null) {
                    promptLayout.error = context.getString(R.string.rt_scene_data_corrupt_hint)
                    return@setOnClickListener
                }
                notifySceneChanged()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun modeLocalized(m: TranslationMode): String =
        if (m == TranslationMode.INTERPRETATION) {
            context.getString(R.string.rt_mode_interpretation)
        } else {
            context.getString(R.string.rt_mode_video)
        }

    private fun notifySceneChanged() {
        onSceneChanged(mode)
        reload()
    }
}
