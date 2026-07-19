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

    // ---------- 第二 AI（资料自动分析） ----------

    fun secondAiEnabled(c: Context): Boolean =
        prefs(c).getBoolean("secondAiEnabled", false)

    fun setSecondAiEnabled(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean("secondAiEnabled", enabled).apply()
    }

    fun secondAiBaseUrl(c: Context): String =
        prefs(c).getString("secondAiBaseUrl", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

    fun saveSecondAiBaseUrl(c: Context, url: String) {
        prefs(c).edit().putString("secondAiBaseUrl", url.ifBlank { DEFAULT_BASE_URL }).apply()
    }

    fun secondAiApiKey(c: Context): String {
        val enc = prefs(c).getString("secondAiApiKeyEnc", "") ?: ""
        return if (enc.isEmpty()) "" else KeystoreCrypto.decrypt(enc)
    }

    fun saveSecondAiApiKey(c: Context, raw: String) {
        prefs(c).edit().putString("secondAiApiKeyEnc", KeystoreCrypto.encrypt(raw.trim())).apply()
    }

    fun secondAiFormat(c: Context): String =
        prefs(c).getString("secondAiFormat", "gemini") ?: "gemini"

    fun saveSecondAiFormat(c: Context, format: String) {
        prefs(c).edit().putString("secondAiFormat", format.ifBlank { "gemini" }).apply()
    }

    fun secondAiModel(c: Context): String =
        prefs(c).getString("secondAiModel", "models/gemini-3-flash-001") ?: "models/gemini-3-flash-001"

    fun saveSecondAiModel(c: Context, model: String) {
        prefs(c).edit().putString("secondAiModel", model.ifBlank { "models/gemini-3-flash-001" }).apply()
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
