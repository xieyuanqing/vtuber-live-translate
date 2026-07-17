package com.xyq.livetranslate

/** 可复用翻译方案：语言方向 + 场景引用 + 方案级额外提示词。 */
data class TranslationPlan(
    val mode: TranslationMode,
    val sourceLanguageCode: String = DEFAULT_SOURCE_LANGUAGE,
    val targetLanguageCode: String = DEFAULT_TARGET_LANGUAGE,
    val scenePresetId: String = defaultSceneId(mode),
    val advancedInstruction: String = "",
) {
    fun normalized(): TranslationPlan {
        val source = sourceLanguageCode.takeIf { code ->
            TranslationLanguageCatalog.sources.any { it.code.equals(code, ignoreCase = true) }
        } ?: DEFAULT_SOURCE_LANGUAGE
        val target = targetLanguageCode.takeIf { code ->
            code != DEFAULT_SOURCE_LANGUAGE &&
                TranslationLanguageCatalog.targets.any { it.code.equals(code, ignoreCase = true) }
        } ?: DEFAULT_TARGET_LANGUAGE
        val sceneId = scenePresetId.trim().ifEmpty { defaultSceneId(mode) }
        return copy(
            sourceLanguageCode = source,
            targetLanguageCode = target,
            scenePresetId = sceneId,
            advancedInstruction = advancedInstruction.trim(),
        )
    }

    val directionLabel: String
        get() = "${TranslationLanguageCatalog.source(sourceLanguageCode).label} → ${TranslationLanguageCatalog.target(targetLanguageCode).label}"

    companion object {
        const val DEFAULT_SOURCE_LANGUAGE = "auto"
        const val DEFAULT_TARGET_LANGUAGE = "zh"

        fun default(mode: TranslationMode): TranslationPlan = TranslationPlan(mode = mode)

        fun defaultSceneId(mode: TranslationMode): String = DefaultSceneCatalog.fallbackId(mode)
    }
}

/** 用户保存的长期方案。 */
data class SavedTranslationPlan(
    val id: String,
    val name: String,
    val plan: TranslationPlan,
)
