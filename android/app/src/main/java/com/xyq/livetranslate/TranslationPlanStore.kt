package com.xyq.livetranslate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * v2 翻译方案存储。两种模式使用完全独立的键；不读取 v1 的全局语言/提示词设置。
 */
object TranslationPlanStore {
    private const val PREFS = "translation_plans_v2"
    private const val KEY_DRAFT_PREFIX = "draft_"
    private const val KEY_SAVED_PREFIX = "saved_"
    private const val KEY_CORRUPT_BACKUP_PREFIX = "corrupt_saved_backup_"

    fun loadDraft(context: Context, mode: TranslationMode): TranslationPlan {
        val raw = prefs(context).getString(KEY_DRAFT_PREFIX + mode.storageKey, null)
            ?: return TranslationPlan.default(mode)
        return runCatching { decodePlan(JSONObject(raw), mode) }
            .getOrElse { TranslationPlan.default(mode) }
            .normalized()
    }

    fun saveDraft(context: Context, plan: TranslationPlan) {
        val normalized = plan.normalized()
        prefs(context).edit()
            .putString(KEY_DRAFT_PREFIX + normalized.mode.storageKey, encodePlan(normalized).toString())
            .apply()
    }

    fun resetDraft(context: Context, mode: TranslationMode) {
        prefs(context).edit()
            .remove(KEY_DRAFT_PREFIX + mode.storageKey)
            .apply()
    }

    fun listSaved(context: Context, mode: TranslationMode): List<SavedTranslationPlan> {
        val raw = prefs(context).getString(KEY_SAVED_PREFIX + mode.storageKey, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                decodeSavedItem(array, index, mode).getOrNull()?.let(::add)
            }
        }
    }

    @Synchronized
    fun saveAs(
        context: Context,
        mode: TranslationMode,
        name: String,
        plan: TranslationPlan,
    ): SavedTranslationPlan {
        backupCorruptSavedData(context, mode)
        val saved = SavedTranslationPlan(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "未命名方案" },
            plan = plan.copy(mode = mode).normalized(),
        )
        val updated = listSaved(context, mode).toMutableList().apply {
            val index = indexOfFirst { it.id == saved.id }
            if (index >= 0) set(index, saved) else add(saved)
        }
        writeSavedPlans(context, mode, updated)
        return saved
    }

    @Synchronized
    fun updateSaved(
        context: Context,
        mode: TranslationMode,
        id: String,
        name: String,
        plan: TranslationPlan,
    ): SavedTranslationPlan? {
        backupCorruptSavedData(context, mode)
        val items = listSaved(context, mode).toMutableList()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return null
        val updated = SavedTranslationPlan(
            id = id,
            name = name.trim().ifEmpty { items[index].name },
            plan = plan.copy(mode = mode).normalized(),
        )
        items[index] = updated
        writeSavedPlans(context, mode, items)
        return updated
    }

    @Synchronized
    fun deleteSavedPlan(context: Context, mode: TranslationMode, id: String) {
        backupCorruptSavedData(context, mode)
        writeSavedPlans(context, mode, listSaved(context, mode).filterNot { it.id == id })
    }

    fun applySaved(
        context: Context,
        mode: TranslationMode,
        id: String,
    ): TranslationPlan? {
        val plan = listSaved(context, mode).firstOrNull { it.id == id }?.plan ?: return null
        saveDraft(context, plan)
        return plan
    }

    internal fun encodePlan(plan: TranslationPlan): JSONObject = JSONObject().apply {
        put("mode", plan.mode.storageKey)
        put("sourceLanguageCode", plan.sourceLanguageCode)
        put("targetLanguageCode", plan.targetLanguageCode)
        put("scenePresetId", plan.scenePresetId)
        put("customSceneInstruction", plan.customSceneInstruction)
        put("advancedInstruction", plan.advancedInstruction)
        put("glossaryKey", "")
    }

    internal fun decodePlan(json: JSONObject, expectedMode: TranslationMode): TranslationPlan {
        val storedMode = json.optString("mode")
        require(storedMode.isEmpty() || storedMode == expectedMode.storageKey) {
            "方案模式与存储分区不一致"
        }
        return TranslationPlan(
            mode = expectedMode,
            sourceLanguageCode = json.optString(
                "sourceLanguageCode",
                TranslationPlan.DEFAULT_SOURCE_LANGUAGE,
            ),
            targetLanguageCode = json.optString(
                "targetLanguageCode",
                TranslationPlan.DEFAULT_TARGET_LANGUAGE,
            ),
            scenePresetId = json.optString(
                "scenePresetId",
                TranslationPlan.defaultSceneId(expectedMode),
            ),
            customSceneInstruction = json.optString("customSceneInstruction", ""),
            advancedInstruction = json.optString("advancedInstruction", ""),
            glossaryKey = "",
        ).normalized()
    }

    private fun writeSavedPlans(
        context: Context,
        mode: TranslationMode,
        plans: List<SavedTranslationPlan>,
    ) {
        val array = JSONArray()
        plans.forEach { saved ->
            array.put(JSONObject().apply {
                put("id", saved.id)
                put("name", saved.name)
                put("plan", encodePlan(saved.plan))
            })
        }
        prefs(context).edit()
            .putString(KEY_SAVED_PREFIX + mode.storageKey, array.toString())
            .apply()
    }

    private fun decodeSavedItem(
        array: JSONArray,
        index: Int,
        mode: TranslationMode,
    ): Result<SavedTranslationPlan> = runCatching {
        val item = array.getJSONObject(index)
        val id = item.getString("id").trim()
        val name = item.getString("name").trim()
        require(id.isNotEmpty() && name.isNotEmpty())
        SavedTranslationPlan(
            id = id,
            name = name,
            plan = decodePlan(item.getJSONObject("plan"), mode).normalized(),
        )
    }

    private fun backupCorruptSavedData(context: Context, mode: TranslationMode) {
        val storage = prefs(context)
        val raw = storage.getString(KEY_SAVED_PREFIX + mode.storageKey, null) ?: return
        val array = runCatching { JSONArray(raw) }.getOrNull()
        val isCorrupt = array == null ||
            (0 until array.length()).any { decodeSavedItem(array, it, mode).isFailure }
        if (!isCorrupt) return
        storage.edit()
            .putString(KEY_CORRUPT_BACKUP_PREFIX + mode.storageKey, raw)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
