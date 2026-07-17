package com.xyq.livetranslate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 用户可编辑的场景库。同传与视频分别存储，默认模板只在首次使用或手动恢复时写入。
 */
object SceneLibraryStore {
    private const val PREFS = "scene_library_v1"
    private const val KEY_ITEMS_PREFIX = "items_"
    private const val KEY_DEFAULT_PREFIX = "default_"

    @Synchronized
    fun list(context: Context, mode: TranslationMode): List<ScenePromptPreset> {
        val storage = prefs(context)
        val key = itemsKey(mode)
        if (!storage.contains(key)) {
            write(context, mode, DefaultSceneCatalog.defaults(mode), DefaultSceneCatalog.fallbackId(mode))
        }
        val decoded = decodeList(storage.getString(key, null))
        // 已有数据损坏时只提供内存回退；只有用户明确恢复模板时才覆盖原始列表。
        return decoded.ifEmpty { DefaultSceneCatalog.defaults(mode) }
    }

    fun resolve(context: Context, mode: TranslationMode, id: String): ScenePromptPreset {
        val items = list(context, mode)
        return items.firstOrNull { it.id == id } ?: default(context, mode)
    }

    @Synchronized
    fun default(context: Context, mode: TranslationMode): ScenePromptPreset {
        val items = list(context, mode)
        val storage = prefs(context)
        val storedId = storage.getString(defaultKey(mode), null)
        val selected = items.firstOrNull { it.id == storedId } ?: items.first()
        if (storedId != selected.id) {
            storage.edit().putString(defaultKey(mode), selected.id).apply()
        }
        return selected
    }

    @Synchronized
    fun create(
        context: Context,
        mode: TranslationMode,
        label: String,
        instruction: String,
    ): ScenePromptPreset? {
        val storedItems = readItemsForMutation(context, mode) ?: return null
        val item = ScenePromptPreset(
            id = UUID.randomUUID().toString(),
            label = label.trim().ifEmpty { "新场景" },
            instruction = instruction.trim(),
        )
        val defaultId = storedDefaultId(context, mode, storedItems)
        write(context, mode, storedItems + item, defaultId)
        return item
    }

    @Synchronized
    fun update(
        context: Context,
        mode: TranslationMode,
        item: ScenePromptPreset,
    ): Boolean {
        val normalized = item.copy(
            id = item.id.trim(),
            label = item.label.trim(),
            instruction = item.instruction.trim(),
        )
        if (normalized.id.isEmpty() || normalized.label.isEmpty() || normalized.instruction.isEmpty()) {
            return false
        }
        val items = readItemsForMutation(context, mode)?.toMutableList() ?: return false
        val index = items.indexOfFirst { it.id == normalized.id }
        if (index < 0) return false
        items[index] = normalized
        write(context, mode, items, storedDefaultId(context, mode, items))
        return true
    }

    @Synchronized
    fun delete(context: Context, mode: TranslationMode, id: String): Boolean {
        val items = readItemsForMutation(context, mode) ?: return false
        if (items.size <= 1 || items.none { it.id == id }) return false
        val remaining = items.filterNot { it.id == id }
        val currentDefault = storedDefaultId(context, mode, items)
        val nextDefault = if (currentDefault == id) remaining.first().id else currentDefault
        write(context, mode, remaining, nextDefault)
        return true
    }

    @Synchronized
    fun setDefault(context: Context, mode: TranslationMode, id: String): Boolean {
        val items = readItemsForMutation(context, mode) ?: return false
        if (items.none { it.id == id }) return false
        prefs(context).edit().putString(defaultKey(mode), id).apply()
        return true
    }

    @Synchronized
    fun reset(context: Context, mode: TranslationMode) {
        write(
            context,
            mode,
            DefaultSceneCatalog.defaults(mode),
            DefaultSceneCatalog.fallbackId(mode),
        )
    }

    private fun write(
        context: Context,
        mode: TranslationMode,
        items: List<ScenePromptPreset>,
        defaultId: String,
    ) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("label", item.label)
                put("instruction", item.instruction)
            })
        }
        prefs(context).edit()
            .putString(itemsKey(mode), array.toString())
            .putString(defaultKey(mode), defaultId)
            .apply()
    }

    private fun decodeList(raw: String?): List<ScenePromptPreset> {
        val array = runCatching { JSONArray(raw ?: return emptyList()) }.getOrNull()
            ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val id = json.optString("id").trim()
                val label = json.optString("label").trim()
                val instruction = json.optString("instruction").trim()
                if (id.isNotEmpty() && label.isNotEmpty() && instruction.isNotEmpty() && none { it.id == id }) {
                    add(ScenePromptPreset(id, label, instruction))
                }
            }
        }
    }

    /** 普通 CRUD 只接受完整、非空且无重复 ID 的原始列表，避免把容错视图覆盖回存储。 */
    private fun readItemsForMutation(
        context: Context,
        mode: TranslationMode,
    ): List<ScenePromptPreset>? {
        val storage = prefs(context)
        val key = itemsKey(mode)
        if (!storage.contains(key)) {
            write(context, mode, DefaultSceneCatalog.defaults(mode), DefaultSceneCatalog.fallbackId(mode))
        }
        val array = runCatching { JSONArray(storage.getString(key, null)) }.getOrNull() ?: return null
        if (array.length() == 0) return null
        val seenIds = mutableSetOf<String>()
        val items = mutableListOf<ScenePromptPreset>()
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: return null
            val id = json.optString("id").trim()
            val label = json.optString("label").trim()
            val instruction = json.optString("instruction").trim()
            if (id.isEmpty() || label.isEmpty() || instruction.isEmpty() || !seenIds.add(id)) {
                return null
            }
            items += ScenePromptPreset(id, label, instruction)
        }
        return items
    }

    private fun storedDefaultId(
        context: Context,
        mode: TranslationMode,
        items: List<ScenePromptPreset>,
    ): String {
        val storedId = prefs(context).getString(defaultKey(mode), null)
        return items.firstOrNull { it.id == storedId }?.id ?: items.first().id
    }

    private fun itemsKey(mode: TranslationMode) = KEY_ITEMS_PREFIX + mode.storageKey
    private fun defaultKey(mode: TranslationMode) = KEY_DEFAULT_PREFIX + mode.storageKey

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
