package com.xyq.livetranslate

/**
 * 可复用的翻译方案。当前场次的背景资料不属于长期方案，必须通过 [SessionPromptContext]
 * 在启动服务时单独传入，停止后清除。
 */
data class TranslationPlan(
    val mode: TranslationMode,
    val sourceLanguageCode: String = DEFAULT_SOURCE_LANGUAGE,
    val targetLanguageCode: String = DEFAULT_TARGET_LANGUAGE,
    val scenePresetId: String = defaultSceneId(mode),
    val customSceneInstruction: String = "",
    val advancedInstruction: String = "",
    val glossaryKey: String = "",
) {
    fun normalized(): TranslationPlan {
        val source = sourceLanguageCode.takeIf { code ->
            TranslationLanguageCatalog.sources.any { it.code.equals(code, ignoreCase = true) }
        } ?: DEFAULT_SOURCE_LANGUAGE
        val target = targetLanguageCode.takeIf { code ->
            code != DEFAULT_SOURCE_LANGUAGE &&
                TranslationLanguageCatalog.targets.any { it.code.equals(code, ignoreCase = true) }
        } ?: DEFAULT_TARGET_LANGUAGE
        val preset = ScenePromptCatalog.resolve(mode, scenePresetId)
        return copy(
            sourceLanguageCode = source,
            targetLanguageCode = target,
            scenePresetId = preset.id,
            customSceneInstruction = customSceneInstruction.trim(),
            advancedInstruction = advancedInstruction.trim(),
            glossaryKey = glossaryKey.trim(),
        )
    }

    val scene: ScenePromptPreset
        get() = ScenePromptCatalog.resolve(mode, scenePresetId)

    val directionLabel: String
        get() = "${TranslationLanguageCatalog.source(sourceLanguageCode).label} → ${TranslationLanguageCatalog.target(targetLanguageCode).label}"

    companion object {
        const val DEFAULT_SOURCE_LANGUAGE = "auto"
        const val DEFAULT_TARGET_LANGUAGE = "zh"

        fun default(mode: TranslationMode): TranslationPlan = TranslationPlan(mode = mode)

        fun defaultSceneId(mode: TranslationMode): String = when (mode) {
            TranslationMode.INTERPRETATION -> "general"
            TranslationMode.VIDEO -> "general_video"
        }
    }
}

/** 用户保存的长期方案；不包含本场临时背景。 */
data class SavedTranslationPlan(
    val id: String,
    val name: String,
    val plan: TranslationPlan,
)
