package com.xyq.livetranslate

import android.content.Context
import android.content.SharedPreferences


/**
 * 设置存取。
 * - API key：Keystore 加密存储，支持英文逗号分隔多个（会话随机选一个）
 * - 悬浮窗样式：字号 / 背景不透明度 / 行数
 */
object SettingsStore {

    const val DEFAULT_BASE_URL = "wss://generativelanguage.googleapis.com"

    // ---------- API keys ----------

    fun apiKeysRaw(c: Context): String {
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

    // ---------- 悬浮窗样式 ----------

    const val DEFAULT_FONT_SP = 16
    const val DEFAULT_BG_OPACITY = 66
    const val DEFAULT_OVERLAY_LINES = 3

    fun fontSizeSp(c: Context): Int = prefs(c).getInt("fontSp", DEFAULT_FONT_SP)
    fun bgOpacityPct(c: Context): Int = prefs(c).getInt("bgOpacity", DEFAULT_BG_OPACITY)
    fun overlayMaxLines(c: Context): Int = prefs(c).getInt("overlayMaxLines", DEFAULT_OVERLAY_LINES)

    fun saveStyle(c: Context, fontSp: Int, bgOpacity: Int, maxLines: Int) {
        prefs(c).edit()
            .putInt("fontSp", fontSp)
            .putInt("bgOpacity", bgOpacity)
            .putInt("overlayMaxLines", maxLines)
            .apply()
        StatusBus.styleVersion.incrementAndGet() // 运行中的悬浮窗下一帧应用
    }

    // ---------- 翻译参数 / 断句参数，改动下次开始翻译时生效 ----------

    const val DEFAULT_ROTATE_SECONDS = 505         // 服务端约 590s GoAway，提前主动轮换
    const val DEFAULT_STAB_IDLE_MS = 2500
    const val DEFAULT_STAB_MAX_CHARS = 42

    fun echoTargetLanguage(c: Context): Boolean =
        prefs(c).getBoolean("advEchoTarget", true)

    fun saveEchoTargetLanguage(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean("advEchoTarget", v).apply()
    }

    fun rotateSeconds(c: Context): Int =
        prefs(c).getInt("advRotateSeconds", DEFAULT_ROTATE_SECONDS).coerceIn(120, 580)

    fun saveRotateSeconds(c: Context, v: Int) {
        prefs(c).edit().putInt("advRotateSeconds", v.coerceIn(120, 580)).apply()
    }

    fun stabIdleMs(c: Context): Int =
        prefs(c).getInt("advStabIdleMs", DEFAULT_STAB_IDLE_MS).coerceIn(1000, 6000)

    fun saveStabIdleMs(c: Context, v: Int) {
        prefs(c).edit().putInt("advStabIdleMs", v.coerceIn(1000, 6000)).apply()
    }

    fun stabMaxChars(c: Context): Int =
        prefs(c).getInt("advStabMaxChars", DEFAULT_STAB_MAX_CHARS).coerceIn(20, 80)

    fun saveStabMaxChars(c: Context, v: Int) {
        prefs(c).edit().putInt("advStabMaxChars", v.coerceIn(20, 80)).apply()
    }

    // ---------- 第二 AI（背景分析 AI） ----------

    const val SERVICE_GEMINI = "gemini"
    const val SERVICE_OPENCODE_ZEN = "opencode_zen"
    const val SERVICE_CUSTOM = "custom"
    const val DEFAULT_GEMINI_MODEL = "models/gemini-2.5-flash"

    /** 提取多 Key 中的首个有效 Key，避免将逗号串当成一把 Key */
    fun extractFirstApiKey(raw: String): String =
        raw.split(',').firstOrNull { it.trim().isNotEmpty() }?.trim().orEmpty()

    /** 精确核对官方 Gemini 域名，防止类似 evilgoogleapis.com 误判为官方服务 */
    fun isOfficialGeminiEndpoint(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return true
        val host = try {
            val normalized = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://") &&
                !trimmed.startsWith("wss://") && !trimmed.startsWith("ws://")
            ) {
                "https://$trimmed"
            } else {
                trimmed
            }
            java.net.URI(normalized).host?.lowercase()
        } catch (e: Exception) {
            null
        } ?: return false
        return host == "generativelanguage.googleapis.com"
    }

    /** 现有用户背景分析配置无损迁移：保留手工格式、URL、模型与加密 Key，自定义代理不误归类 */
    fun migrateSecondAiSettingsIfNeeded(c: Context) {
        val sp = prefs(c)
        if (sp.contains("secondAiService")) return
        val oldFormat = sp.getString("secondAiFormat", "gemini") ?: "gemini"
        val oldBaseUrl = sp.getString("secondAiBaseUrl", "") ?: ""
        val oldApiKeyEnc = sp.getString("secondAiApiKeyEnc", "") ?: ""
        val oldModel = sp.getString("secondAiModel", "") ?: ""

        val isCustom = oldFormat == "openai" ||
            (oldBaseUrl.isNotBlank() && !isOfficialGeminiEndpoint(oldBaseUrl))

        val editor = sp.edit()
        if (isCustom) {
            editor.putString("secondAiService", SERVICE_CUSTOM)
            editor.putString("secondAiCustomFormat", oldFormat)
            editor.putString("secondAiCustomBaseUrl", oldBaseUrl)
            if (oldApiKeyEnc.isNotEmpty()) editor.putString("secondAiCustomApiKeyEnc", oldApiKeyEnc)
            editor.putString("secondAiCustomModel", oldModel)
        } else {
            editor.putString("secondAiService", SERVICE_GEMINI)
            editor.putString("secondAiGeminiBaseUrl", oldBaseUrl.ifBlank { DEFAULT_BASE_URL })
            if (oldApiKeyEnc.isNotEmpty()) editor.putString("secondAiGeminiApiKeyEnc", oldApiKeyEnc)
            editor.putString("secondAiGeminiModel", oldModel.ifBlank { DEFAULT_GEMINI_MODEL })
        }
        editor.apply()
    }

    fun secondAiService(c: Context): String {
        migrateSecondAiSettingsIfNeeded(c)
        return prefs(c).getString("secondAiService", SERVICE_GEMINI) ?: SERVICE_GEMINI
    }

    fun saveSecondAiService(c: Context, service: String) {
        val target = when (service) {
            SERVICE_OPENCODE_ZEN -> SERVICE_OPENCODE_ZEN
            SERVICE_CUSTOM -> SERVICE_CUSTOM
            else -> SERVICE_GEMINI
        }
        prefs(c).edit().putString("secondAiService", target).apply()
    }

    // --- Gemini 独立配置 ---

    fun secondAiGeminiApiKey(c: Context): String {
        migrateSecondAiSettingsIfNeeded(c)
        val enc = prefs(c).getString("secondAiGeminiApiKeyEnc", "") ?: ""
        return if (enc.isEmpty()) "" else KeystoreCrypto.decrypt(enc)
    }

    fun saveSecondAiGeminiApiKey(c: Context, raw: String) {
        prefs(c).edit().putString("secondAiGeminiApiKeyEnc", KeystoreCrypto.encrypt(raw.trim())).apply()
    }

    fun secondAiGeminiBaseUrl(c: Context): String {
        migrateSecondAiSettingsIfNeeded(c)
        return prefs(c).getString("secondAiGeminiBaseUrl", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
    }

    fun saveSecondAiGeminiBaseUrl(c: Context, url: String) {
        prefs(c).edit().putString("secondAiGeminiBaseUrl", url.ifBlank { DEFAULT_BASE_URL }).apply()
    }

    fun secondAiGeminiModel(c: Context): String {
        migrateSecondAiSettingsIfNeeded(c)
        return prefs(c).getString("secondAiGeminiModel", DEFAULT_GEMINI_MODEL) ?: DEFAULT_GEMINI_MODEL
    }

    fun saveSecondAiGeminiModel(c: Context, model: String) {
        prefs(c).edit().putString("secondAiGeminiModel", model.ifBlank { DEFAULT_GEMINI_MODEL }).apply()
    }

    // --- OpenCode Zen 独立配置（免 Key，固定 Base 与模型白名单） ---

    fun secondAiZenModel(c: Context): String {
        migrateSecondAiSettingsIfNeeded(c)
        val model = prefs(c).getString("secondAiZenModel", OpenCodeZenCatalog.CANDIDATE_MODEL)
            ?: OpenCodeZenCatalog.CANDIDATE_MODEL
        return if (OpenCodeZenCatalog.isAllowedFreeModel(model)) model else OpenCodeZenCatalog.CANDIDATE_MODEL
    }

    fun saveSecondAiZenModel(c: Context, model: String) {
        val safeModel = if (OpenCodeZenCatalog.isAllowedFreeModel(model)) model.trim() else OpenCodeZenCatalog.CANDIDATE_MODEL
        prefs(c).edit().putString("secondAiZenModel", safeModel).apply()
    }

    // --- 自定义服务独立配置 ---

    fun secondAiCustomFormat(c: Context): String {
        migrateSecondAiSettingsIfNeeded(c)
        return prefs(c).getString("secondAiCustomFormat", "openai") ?: "openai"
    }

    fun saveSecondAiCustomFormat(c: Context, format: String) {
        prefs(c).edit().putString("secondAiCustomFormat", format.ifBlank { "openai" }).apply()
    }

    fun secondAiCustomApiKey(c: Context): String {
        migrateSecondAiSettingsIfNeeded(c)
        val enc = prefs(c).getString("secondAiCustomApiKeyEnc", "") ?: ""
        return if (enc.isEmpty()) "" else KeystoreCrypto.decrypt(enc)
    }

    fun saveSecondAiCustomApiKey(c: Context, raw: String) {
        prefs(c).edit().putString("secondAiCustomApiKeyEnc", KeystoreCrypto.encrypt(raw.trim())).apply()
    }

    fun secondAiCustomBaseUrl(c: Context): String {
        migrateSecondAiSettingsIfNeeded(c)
        return prefs(c).getString("secondAiCustomBaseUrl", "") ?: ""
    }

    fun saveSecondAiCustomBaseUrl(c: Context, url: String) {
        prefs(c).edit().putString("secondAiCustomBaseUrl", url.trim()).apply()
    }

    fun secondAiCustomModel(c: Context): String {
        migrateSecondAiSettingsIfNeeded(c)
        return prefs(c).getString("secondAiCustomModel", "") ?: ""
    }

    fun saveSecondAiCustomModel(c: Context, model: String) {
        prefs(c).edit().putString("secondAiCustomModel", model.trim()).apply()
    }

    // --- 当前激活服务的动态解析（对外统一入口，绝不向未配置的 Custom 偷偷回退 Gemini 默认值） ---

    fun secondAiBaseUrl(c: Context): String = when (secondAiService(c)) {
        SERVICE_GEMINI -> secondAiGeminiBaseUrl(c)
        SERVICE_OPENCODE_ZEN -> OpenCodeZenCatalog.BASE_URL
        SERVICE_CUSTOM -> secondAiCustomBaseUrl(c)
        else -> secondAiGeminiBaseUrl(c)
    }

    fun saveSecondAiBaseUrl(c: Context, url: String) {
        when (secondAiService(c)) {
            SERVICE_GEMINI -> saveSecondAiGeminiBaseUrl(c, url)
            SERVICE_OPENCODE_ZEN -> Unit // Zen 固定 Base URL
            SERVICE_CUSTOM -> saveSecondAiCustomBaseUrl(c, url)
        }
    }

    fun secondAiApiKey(c: Context): String = when (secondAiService(c)) {
        SERVICE_GEMINI -> secondAiGeminiApiKey(c)
        SERVICE_OPENCODE_ZEN -> OpenCodeZenCatalog.PUBLIC_KEY
        SERVICE_CUSTOM -> secondAiCustomApiKey(c)
        else -> secondAiGeminiApiKey(c)
    }

    fun saveSecondAiApiKey(c: Context, raw: String) {
        when (secondAiService(c)) {
            SERVICE_GEMINI -> saveSecondAiGeminiApiKey(c, raw)
            SERVICE_OPENCODE_ZEN -> Unit // Zen 内部固定 public，不保存用户 key
            SERVICE_CUSTOM -> saveSecondAiCustomApiKey(c, raw)
        }
    }

    fun secondAiFormat(c: Context): String = when (secondAiService(c)) {
        SERVICE_GEMINI -> "gemini"
        SERVICE_OPENCODE_ZEN -> "openai"
        SERVICE_CUSTOM -> secondAiCustomFormat(c)
        else -> "gemini"
    }

    fun saveSecondAiFormat(c: Context, format: String) {
        when (secondAiService(c)) {
            SERVICE_GEMINI -> Unit
            SERVICE_OPENCODE_ZEN -> Unit
            SERVICE_CUSTOM -> saveSecondAiCustomFormat(c, format)
        }
    }

    fun secondAiModel(c: Context): String = when (secondAiService(c)) {
        SERVICE_GEMINI -> secondAiGeminiModel(c)
        SERVICE_OPENCODE_ZEN -> secondAiZenModel(c)
        SERVICE_CUSTOM -> secondAiCustomModel(c)
        else -> secondAiGeminiModel(c)
    }

    fun saveSecondAiModel(c: Context, model: String) {
        when (secondAiService(c)) {
            SERVICE_GEMINI -> saveSecondAiGeminiModel(c, model)
            SERVICE_OPENCODE_ZEN -> saveSecondAiZenModel(c, model)
            SERVICE_CUSTOM -> saveSecondAiCustomModel(c, model)
        }
    }

    // ---------- 检查更新 ----------

    fun autoCheckUpdate(c: Context): Boolean =
        prefs(c).getBoolean("autoCheckUpdate", true)

    fun saveAutoCheckUpdate(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean("autoCheckUpdate", enabled).apply()
    }

    fun ignoredUpdateVersionCode(c: Context): Long =
        prefs(c).getLong("ignoredUpdateVersionCode", 0L)

    fun saveIgnoredUpdateVersionCode(c: Context, versionCode: Long) {
        prefs(c).edit().putLong("ignoredUpdateVersionCode", versionCode.coerceAtLeast(0L)).apply()
    }

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("settings", Context.MODE_PRIVATE)
}
