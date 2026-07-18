package com.xyq.livetranslate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * v3 草稿存储：每模式一份运行时草稿（语言 + 场景引用）。
 * 可复用配置统一放在 SceneLibraryStore；旧的命名方案已并入场景库。
 */
object TranslationPlanStore {
    private const val PREFS = "translation_plans_v3"
    private const val KEY_DRAFT_PREFIX = "draft_"
    private const val KEY_SAVED_PREFIX = "saved_"
    private const val KEY_CORRUPT_BACKUP_PREFIX = "corrupt_saved_backup_"
    private const val KEY_PLANS_MERGED = "plans_merged_into_scenes"

    fun loadDraft(context: Context, mode: TranslationMode): TranslationPlan {
        val raw = prefs(context).getString(KEY_DRAFT_PREFIX + mode.storageKey, null)
            ?: return defaultPlan(context, mode)
        return runCatching { decodePlan(JSONObject(raw), mode) }
            .getOrElse { defaultPlan(context, mode) }
    }

    fun saveDraft(context: Context, plan: TranslationPlan) {
        val normalized = normalizeForStorage(context, plan)
        prefs(context).edit()
            .putString(KEY_DRAFT_PREFIX + normalized.mode.storageKey, encodePlan(normalized).toString())
            .apply()
    }

    fun resetDraft(context: Context, mode: TranslationMode) {
        prefs(context).edit()
            .remove(KEY_DRAFT_PREFIX + mode.storageKey)
            .apply()
    }

    /**
     * 一次性迁移：把旧方案库中带额外提示词的命名方案折算成场景库条目
     * （场景提示词 = 原引用场景提示词 + 方案额外提示词），然后清空方案存储。
     * 纯场景别名（无额外提示词）不生成重复场景。
     */
    @Synchronized
    fun migrateLegacySavedPlans(context: Context) {
        val storage = prefs(context)
        if (storage.getBoolean(KEY_PLANS_MERGED, false)) return
        val editor = storage.edit()
        TranslationMode.entries.forEach { mode ->
            val raw = storage.getString(KEY_SAVED_PREFIX + mode.storageKey, null)
            if (raw != null) {
                val array = runCatching { JSONArray(raw) }.getOrNull() ?: JSONArray()
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val name = item.optString("name").trim()
                    val plan = item.optJSONObject("plan") ?: continue
                    val extra = plan.optString("advancedInstruction").trim()
                    if (name.isEmpty() || extra.isEmpty()) continue
                    val sceneId = plan.optString("scenePresetId").trim()
                    val base = SceneLibraryStore.resolve(context, mode, sceneId)
                    SceneLibraryStore.create(
                        context,
                        mode,
                        name,
                        (base.instruction + "\n" + extra).trim(),
                    )
                }
                editor.remove(KEY_SAVED_PREFIX + mode.storageKey)
            }
            editor.remove(KEY_CORRUPT_BACKUP_PREFIX + mode.storageKey)
        }
        editor.putBoolean(KEY_PLANS_MERGED, true).apply()
    }

    internal fun encodePlan(plan: TranslationPlan): JSONObject = JSONObject().apply {
        put("mode", plan.mode.storageKey)
        put("sourceLanguageCode", plan.sourceLanguageCode)
        put("targetLanguageCode", plan.targetLanguageCode)
        put("scenePresetId", plan.scenePresetId)
    }

    internal fun decodePlan(json: JSONObject, expectedMode: TranslationMode): TranslationPlan {
        val storedMode = json.optString("mode")
        require(storedMode.isEmpty() || storedMode == expectedMode.storageKey) {
            "草稿模式与存储分区不一致"
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
        ).normalized()
    }

    private fun defaultPlan(context: Context, mode: TranslationMode) =
        TranslationPlan(
            mode = mode,
            scenePresetId = SceneLibraryStore.default(context, mode).id,
        )

    private fun normalizeForStorage(context: Context, plan: TranslationPlan): TranslationPlan {
        val requestedSceneId = plan.scenePresetId.trim()
        val normalized = plan.normalized()
        return normalized.copy(
            scenePresetId = requestedSceneId.ifEmpty {
                SceneLibraryStore.default(context, normalized.mode).id
            },
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
