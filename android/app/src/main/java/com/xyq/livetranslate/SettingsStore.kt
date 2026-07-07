package com.xyq.livetranslate

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * 设置存取。
 * - API key：Keystore 加密存储，支持英文逗号分隔多个（会话随机选一个）
 * - prompt 预设：多套按名字存，开播前选一套
 * - 悬浮窗样式：字号 / 背景不透明度 / 行数
 * 兼容旧版明文字段（apiKey / prompt），首次访问自动迁移。
 */
object SettingsStore {

    const val DEFAULT_BASE_URL = "wss://generativelanguage.googleapis.com"
    const val DEFAULT_PRESET_NAME = "通用VTuber"

    val DEFAULT_PROMPT = """
        你正在为日语 VTuber 直播做低延迟实时字幕翻译。
        把听到的日语忠实翻译成自然、口语化的中文，适合粉丝观看直播时快速阅读。
        不要添加解释、总结或原文中不存在的内容。
        人名、专有名词使用圈内常见译法，拿不准时保留原文。
        没听清的内容宁可略过，不要编造。
    """.trimIndent()

    @Volatile private var migrated = false

    // ---------- API keys ----------

    fun apiKeysRaw(c: Context): String {
        migrate(c)
        val enc = prefs(c).getString("apiKeysEnc", "") ?: ""
        return if (enc.isEmpty()) "" else KeystoreCrypto.decrypt(enc)
    }

    fun apiKeyList(c: Context): List<String> =
        apiKeysRaw(c).split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun saveApiKeys(c: Context, raw: String) {
        prefs(c).edit().putString("apiKeysEnc", KeystoreCrypto.encrypt(raw.trim())).apply()
    }

    // ---------- Base URL ----------

    fun baseUrl(c: Context): String =
        prefs(c).getString("baseUrl", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

    fun saveBaseUrl(c: Context, url: String) {
        prefs(c).edit().putString("baseUrl", url.ifBlank { DEFAULT_BASE_URL }).apply()
    }

    // ---------- prompt 预设 ----------

    fun presetNames(c: Context): List<String> {
        migrate(c)
        val json = presetsJson(c)
        val names = ArrayList<String>()
        json.keys().forEach { names.add(it) }
        names.sort()
        return names
    }

    fun presetText(c: Context, name: String): String =
        presetsJson(c).optString(name, DEFAULT_PROMPT)

    fun savePreset(c: Context, name: String, text: String) {
        val json = presetsJson(c)
        json.put(name, text)
        prefs(c).edit().putString("promptPresets", json.toString()).apply()
    }

    fun deletePreset(c: Context, name: String): Boolean {
        val json = presetsJson(c)
        if (json.length() <= 1) return false
        json.remove(name)
        prefs(c).edit().putString("promptPresets", json.toString()).apply()
        if (selectedPreset(c) == name) {
            setSelectedPreset(c, presetNames(c).first())
        }
        return true
    }

    fun selectedPreset(c: Context): String {
        val sel = prefs(c).getString("promptSelected", DEFAULT_PRESET_NAME) ?: DEFAULT_PRESET_NAME
        return if (presetsJson(c).has(sel)) sel else presetNames(c).first()
    }

    fun setSelectedPreset(c: Context, name: String) {
        prefs(c).edit().putString("promptSelected", name).apply()
    }

    /** 当前会话实际使用的提示词。 */
    fun activePrompt(c: Context): String = presetText(c, selectedPreset(c))

    private fun presetsJson(c: Context): JSONObject {
        migrate(c)
        val raw = prefs(c).getString("promptPresets", "") ?: ""
        val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        if (json.length() == 0) {
            json.put(DEFAULT_PRESET_NAME, DEFAULT_PROMPT)
            prefs(c).edit().putString("promptPresets", json.toString()).apply()
        }
        return json
    }

    // ---------- 悬浮窗样式 ----------

    fun fontSizeSp(c: Context): Int = prefs(c).getInt("fontSp", 16)
    fun bgOpacityPct(c: Context): Int = prefs(c).getInt("bgOpacity", 66)
    fun overlayMaxLines(c: Context): Int = prefs(c).getInt("overlayMaxLines", 3)

    fun saveStyle(c: Context, fontSp: Int, bgOpacity: Int, maxLines: Int) {
        prefs(c).edit()
            .putInt("fontSp", fontSp)
            .putInt("bgOpacity", bgOpacity)
            .putInt("overlayMaxLines", maxLines)
            .apply()
        StatusBus.styleVersion.incrementAndGet() // 运行中的悬浮窗下一帧应用
    }

    // ---------- 旧版迁移 ----------

    private fun migrate(c: Context) {
        if (migrated) return
        migrated = true
        val p = prefs(c)
        val legacyKey = p.getString("apiKey", null)
        if (legacyKey != null) {
            if (legacyKey.isNotBlank() && (p.getString("apiKeysEnc", "") ?: "").isEmpty()) {
                p.edit().putString("apiKeysEnc", KeystoreCrypto.encrypt(legacyKey)).apply()
            }
            p.edit().remove("apiKey").apply()
        }
        val legacyPrompt = p.getString("prompt", null)
        if (legacyPrompt != null) {
            if (legacyPrompt.isNotBlank()) {
                val json = runCatching {
                    JSONObject(p.getString("promptPresets", "") ?: "")
                }.getOrDefault(JSONObject())
                json.put(DEFAULT_PRESET_NAME, legacyPrompt)
                p.edit()
                    .putString("promptPresets", json.toString())
                    .putString("promptSelected", DEFAULT_PRESET_NAME)
                    .apply()
            }
            p.edit().remove("prompt").apply()
        }
    }

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("settings", Context.MODE_PRIVATE)
}
