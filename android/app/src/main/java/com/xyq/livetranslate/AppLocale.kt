package com.xyq.livetranslate

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * 界面语言设置管理与资源查找辅助。
 *
 * 支持三种模式：
 * - 跟随系统 (system)
 * - 简体中文 (zh-Hans)
 * - English (en)
 *
 * 存储于 "settings" SharedPreferences 中的 "appLanguage" 键。
 * 界面语言切换与翻译方向、场景提示词等完全独立。
 */
object AppLocale {
    const val TAG_SYSTEM = "system"
    const val TAG_ZH_HANS = "zh-Hans"
    const val TAG_EN = "en"

    const val PREFS_NAME = "settings"
    const val KEY_APP_LANGUAGE = "appLanguage"

    @Volatile
    private var applicationContext: Context? = null

    fun init(context: Context) {
        applicationContext = context.applicationContext
        AppStrings.init(context)
        val saved = current(context)
        if (saved != TAG_SYSTEM) {
            apply(saved)
        }
    }

    internal fun setApplicationContextForTest(context: Context?) {
        applicationContext = context?.applicationContext
        AppStrings.init(context)
    }

    /**
     * 规范化语言 tag，非法或未知值统一回退到 system。
     */
    fun normalize(tag: String?): String = when (tag) {
        TAG_ZH_HANS -> TAG_ZH_HANS
        TAG_EN -> TAG_EN
        TAG_SYSTEM -> TAG_SYSTEM
        else -> TAG_SYSTEM
    }

    /**
     * 获取当前设置的语言 tag (system|zh-Hans|en)。
     */
    fun current(context: Context): String {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_APP_LANGUAGE, TAG_SYSTEM)
        return normalize(raw)
    }

    /**
     * 保存语言设置到 "settings" SharedPreferences。
     */
    fun save(context: Context, tag: String) {
        val normalized = normalize(tag)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APP_LANGUAGE, normalized)
            .apply()
    }

    /**
     * 通过 AppCompat 标准方案应用语言设置。
     * 跟随系统时传入空 LocaleList，API33+ 由系统管辖，API29-32 由 AppCompat 自动持久化。
     */
    fun apply(tag: String) {
        val normalized = normalize(tag)
        val locales = if (normalized == TAG_SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(normalized)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /**
     * 持久化并立即通过 AppCompatDelegate 生效。
     */
    fun saveAndApply(context: Context, tag: String) {
        save(context, tag)
        apply(tag)
    }

    /**
     * 兼容重载：传入 Context 与 tag。
     */
    fun apply(context: Context, tag: String) {
        saveAndApply(context, tag)
    }

    /**
     * 获取指定语言的本地化显示文本。
     */
    fun getDisplayName(context: Context, tag: String): String = when (normalize(tag)) {
        TAG_ZH_HANS -> context.getString(R.string.locale_option_zh_hans)
        TAG_EN -> context.getString(R.string.locale_option_en)
        else -> context.getString(R.string.locale_option_system)
    }

    /**
     * 为给定的 Context 绑定对应 tag 的 Configuration 并返回 Context，
     * 用于跨 Locale 查找字符串资源。
     */
    fun getLocalizedContext(context: Context, tag: String): Context {
        val normalized = normalize(tag)
        if (normalized == TAG_SYSTEM) {
            return context
        }
        val targetLocale = when (normalized) {
            TAG_ZH_HANS -> Locale.forLanguageTag("zh-Hans")
            TAG_EN -> Locale.ENGLISH
            else -> return context
        }
        val config = Configuration(context.resources.configuration)
        config.setLocale(targetLocale)
        return context.createConfigurationContext(config)
    }
}

/**
 * 无 Context 场合的字符串资源查找辅助。
 *
 * 仅执行资源查找（按照当前 AppLocale 选择对应的 values/values-en 资源），
 * 绝不做中文原文到英文的硬编码字典映射。
 */
object AppStrings {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context?) {
        appContext = context?.applicationContext
    }

    private fun resolveContext(): Context? {
        appContext?.let { return it }
        return runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val method = atClass.getMethod("currentApplication")
            (method.invoke(null) as? Context)?.also {
                appContext = it.applicationContext
            }
        }.getOrNull()
    }

    /**
     * 按当前 AppLocale 查找指定 string resource ID 的文本。
     */
    fun get(@StringRes resId: Int, vararg formatArgs: Any): String {
        val context = resolveContext() ?: return ""
        val currentTag = AppLocale.current(context)
        val localizedContext = AppLocale.getLocalizedContext(context, currentTag)
        return if (formatArgs.isEmpty()) {
            localizedContext.getString(resId)
        } else {
            localizedContext.getString(resId, *formatArgs)
        }
    }

    /**
     * 按资源名称查找字符串。
     */
    fun get(resName: String, vararg formatArgs: Any): String {
        val context = resolveContext() ?: return resName
        val id = context.resources.getIdentifier(resName, "string", context.packageName)
        if (id == 0) return resName
        return get(id, *formatArgs)
    }

    /**
     * 带显式 Context 的辅助方法。
     */
    fun getWithContext(context: Context, @StringRes resId: Int, vararg formatArgs: Any): String {
        val currentTag = AppLocale.current(context)
        val localizedContext = AppLocale.getLocalizedContext(context, currentTag)
        return if (formatArgs.isEmpty()) {
            localizedContext.getString(resId)
        } else {
            localizedContext.getString(resId, *formatArgs)
        }
    }
}
