package com.xyq.livetranslate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 用户可编辑的场景库。同传与视频分别存储，默认模板只在首次使用或手动恢复时写入。
 *
 * 存储里的 `label` 只保存**用户改过的名字**：没改过的内置场景存空串，读取时与
 * [DefaultSceneCatalog] 模板合并，于是展示名跟随界面语言、`promptLabel` 保持固定中文。
 * 早期版本把「当前界面语言下解析出来的名字」直接写进了存储，首次初始化时用的是哪种
 * 语言，场景名就被冻在哪种语言，还会顺着 `promptLabel` 漏进发给模型的 prompt；
 * [hydrate] 会把这种存量值识别回「没改过」。
 */
object SceneLibraryStore {
    private const val PREFS = "scene_library_v1"
    private const val KEY_ITEMS_PREFIX = "items_"
    private const val KEY_DEFAULT_PREFIX = "default_"

    /** string resource 在运行时不会变，模板名的各语言写法缓存一次即可。 */
    private val labelVariantCache = mutableMapOf<Int, Set<String>>()

    @Synchronized
    fun list(context: Context, mode: TranslationMode): List<ScenePromptPreset> {
        val storage = prefs(context)
        val key = itemsKey(mode)
        if (!storage.contains(key)) {
            write(context, mode, DefaultSceneCatalog.defaults(mode), DefaultSceneCatalog.fallbackId(mode))
        }
        val decoded = decodeList(context, mode, storage.getString(key, null))
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
            label = label.trim().ifEmpty { context.getString(R.string.rt_scene_new_default_name) },
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
        val id = item.id.trim()
        val label = item.label.trim()
        val instruction = item.instruction.trim()
        if (id.isEmpty() || label.isEmpty() || instruction.isEmpty()) return false
        // 改回模板默认名（任一界面语言的写法）就当作没覆盖，重新跟随界面语言。
        val normalized = hydrate(context, mode, id, label, instruction) ?: return false
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
                // 只存用户覆盖；空串表示「没改过，跟随界面语言」。
                put("label", item.label)
                put("instruction", item.instruction)
            })
        }
        prefs(context).edit()
            .putString(itemsKey(mode), array.toString())
            .putString(defaultKey(mode), defaultId)
            .apply()
    }

    private fun decodeList(
        context: Context,
        mode: TranslationMode,
        raw: String?,
    ): List<ScenePromptPreset> {
        val array = runCatching { JSONArray(raw ?: return emptyList()) }.getOrNull()
            ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val id = json.optString("id").trim()
                val label = json.optString("label").trim()
                val instruction = json.optString("instruction").trim()
                if (id.isEmpty() || instruction.isEmpty() || any { it.id == id }) continue
                val item = hydrate(context, mode, id, label, instruction) ?: continue
                add(item)
            }
        }
    }

    /**
     * 把一条存储记录还原成运行时场景。
     *
     * `storedLabel` 为空、或等于该模板在任一支持界面语言下的默认名，都算「用户没改过」：
     * 回落到模板，展示名跟随界面语言，`promptLabel` 仍是固定中文。用户自建场景没有模板，
     * 名字为空时视为损坏记录。
     */
    private fun hydrate(
        context: Context,
        mode: TranslationMode,
        id: String,
        storedLabel: String,
        instruction: String,
    ): ScenePromptPreset? {
        val template = DefaultSceneCatalog.defaults(mode).firstOrNull { it.id == id }
            ?: return if (storedLabel.isEmpty()) null else ScenePromptPreset(id, storedLabel, instruction)
        val untouched = storedLabel.isEmpty() || storedLabel in defaultLabelVariants(context, template)
        return template.copy(
            instruction = instruction,
            labelText = if (untouched) null else storedLabel,
        )
    }

    /** 模板名在所有支持界面语言下的写法，用来识别「没改过的默认名」。 */
    private fun defaultLabelVariants(context: Context, template: ScenePromptPreset): Set<String> {
        val resId = template.labelRes
        if (resId == 0) return emptySet()
        synchronized(labelVariantCache) {
            labelVariantCache[resId]?.let { return it }
        }
        val variants = listOf(AppLocale.TAG_ZH_HANS, AppLocale.TAG_EN)
            .mapNotNull { tag ->
                runCatching {
                    AppLocale.getLocalizedContext(context, tag).getString(resId).trim()
                }.getOrNull()
            }
            .filter { it.isNotEmpty() }
            .toSet()
        synchronized(labelVariantCache) {
            labelVariantCache[resId] = variants
        }
        return variants
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
            if (id.isEmpty() || instruction.isEmpty() || !seenIds.add(id)) return null
            items += hydrate(context, mode, id, label, instruction) ?: return null
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
