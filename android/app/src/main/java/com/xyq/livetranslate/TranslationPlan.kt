package com.xyq.livetranslate

/**
 * 可复用的翻译方案。提示词（场景 + 自定义说明 + 方案提示词）全部保存在方案内。
 * 本版本不再使用独立术语库或临时本场上下文。
 */
data class TranslationPlan(
    val mode: TranslationMode,
    val sourceLanguageCode: String = DEFAULT_SOURCE_LANGUAGE,
    val targetLanguageCode: String = DEFAULT_TARGET_LANGUAGE,
    val scenePresetId: String = defaultSceneId(mode),
    val customSceneInstruction: String = "",
    val advancedInstruction: String = "",
    /** 历史字段，本版本始终置空，读取时忽略。 */
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
            glossaryKey = "",
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

/** 用户保存的长期方案。 */
data class SavedTranslationPlan(
    val id: String,
    val name: String,
    val plan: TranslationPlan,
)
