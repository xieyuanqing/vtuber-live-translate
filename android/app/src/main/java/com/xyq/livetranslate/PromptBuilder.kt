package com.xyq.livetranslate

enum class TranslationMode(val storageKey: String, val label: String) {
    INTERPRETATION("interpretation", "同传"),
    VIDEO("video", "视频"),
}

data class TranslationLanguage(val code: String, val label: String)

object TranslationLanguageCatalog {
    val sources = listOf(
        TranslationLanguage("ja", "日语"),
        TranslationLanguage("auto", "自动检测"),
        TranslationLanguage("en", "英语"),
        TranslationLanguage("zh", "中文"),
        TranslationLanguage("ko", "韩语"),
        TranslationLanguage("es", "西班牙语"),
        TranslationLanguage("fr", "法语"),
        TranslationLanguage("de", "德语"),
        TranslationLanguage("ru", "俄语"),
    )
    val targets = listOf(
        TranslationLanguage("zh", "中文"),
        TranslationLanguage("zh-Hans", "简体中文"),
        TranslationLanguage("zh-Hant", "繁体中文"),
        TranslationLanguage("en", "英语"),
        TranslationLanguage("ja", "日语"),
        TranslationLanguage("ko", "韩语"),
        TranslationLanguage("es", "西班牙语"),
        TranslationLanguage("fr", "法语"),
        TranslationLanguage("de", "德语"),
        TranslationLanguage("ru", "俄语"),
    )

    fun source(code: String): TranslationLanguage =
        sources.firstOrNull { it.code.equals(code, ignoreCase = true) }
            ?: TranslationLanguage(code, code)

    fun target(code: String): TranslationLanguage =
        targets.firstOrNull { it.code.equals(code, ignoreCase = true) }
            ?: TranslationLanguage(code, code)
}

data class ScenePromptPreset(
    val id: String,
    val label: String,
    val instruction: String,
)

/** 首次初始化场景库使用的默认模板，不作为运行时场景真源。 */
object DefaultSceneCatalog {
    private val interpretationPresets = listOf(
        ScenePromptPreset(
            id = "general",
            label = "通用",
            instruction = "适用于日常对话和一般现场交流。优先保证意思准确、表达自然，避免书面腔。",
        ),
        ScenePromptPreset(
            id = "meeting",
            label = "会议",
            instruction = "这是会议或商务讨论。准确处理议题、结论、数字、职责和行动项，保持专业、简洁。",
        ),
        ScenePromptPreset(
            id = "classroom",
            label = "课堂",
            instruction = "这是课堂或讲座。保留学科术语、定义、例子和推导关系，让译文便于跟随讲解。",
        ),
        ScenePromptPreset(
            id = "interview",
            label = "采访",
            instruction = "这是采访。区分提问与回答，保留人物语气、观点和措辞边界，不替说话人润色立场。",
        ),
        ScenePromptPreset(
            id = "travel",
            label = "旅行交流",
            instruction = "这是旅行中的现场交流。优先准确处理地点、时间、价格、路线、规则和礼貌表达。",
        ),
    )

    private val videoPresets = listOf(
        ScenePromptPreset(
            id = "general_video",
            label = "通用视频",
            instruction = "适用于一般视频内容。保持前后字幕连贯，准确处理标题、人物、组织和主题词。",
        ),
        ScenePromptPreset(
            id = "livestream",
            label = "直播",
            instruction = "这是实时直播。适应口语、省略、互动和话题跳转，弹幕或观众称呼按上下文自然翻译。",
        ),
        ScenePromptPreset(
            id = "vtuber",
            label = "VTuber",
            instruction = "这是 VTuber 直播。优先使用圈内常见的人名、组合名和直播术语译法；不确定的专名保留原文。",
        ),
        ScenePromptPreset(
            id = "anime",
            label = "动漫",
            instruction = "这是动漫内容。保持角色口吻和称谓关系，作品名、角色名、招式与设定优先采用通行译名。",
        ),
        ScenePromptPreset(
            id = "game",
            label = "游戏",
            instruction = "这是游戏内容。准确处理游戏名、角色、技能、道具、地图和机制术语，保留玩家口语节奏。",
        ),
        ScenePromptPreset(
            id = "news",
            label = "新闻",
            instruction = "这是新闻内容。保持客观和信息密度，准确翻译人名、地名、机构、数字、日期与引语。",
        ),
        ScenePromptPreset(
            id = "course",
            label = "课程",
            instruction = "这是课程或教学视频。保留专业术语、步骤、定义和因果关系，译文清楚但不额外解释。",
        ),
    )

    fun defaults(mode: TranslationMode): List<ScenePromptPreset> = when (mode) {
        TranslationMode.INTERPRETATION -> interpretationPresets
        TranslationMode.VIDEO -> videoPresets
    }

    fun fallbackId(mode: TranslationMode): String = defaults(mode).first().id

    fun resolve(mode: TranslationMode, id: String): ScenePromptPreset =
        defaults(mode).firstOrNull { it.id == id } ?: defaults(mode).first()
}

data class SessionPromptContext(
    val video: YouTubeVideoInfo? = null,
    val manualContext: String = "",
)

object PromptBuilder {
    private val baseInstruction = """
        你是实时语音翻译引擎：
        - 忠实翻译，不回答、解释、总结、续写或编造。
        - 只输出目标语言译文，不添加标签或前言。
        - 保留语气、数字和专名，并结合上下文自然断句；不确定的专名保留原文。
    """.trimIndent()

    private fun modeInstruction(mode: TranslationMode): String = when (mode) {
        TranslationMode.INTERPRETATION ->
            "输入来自麦克风现场语音，可能有噪声、多人对话或不完整句子；按实际语义翻译。"
        TranslationMode.VIDEO ->
            "输入来自视频或其他应用的连续音频；结合前后文保持字幕连贯。"
    }

    fun build(
        scene: ScenePromptPreset,
        context: SessionPromptContext = SessionPromptContext(),
        plan: TranslationPlan,
    ): String {
        val normalized = plan.normalized()
        val modeContext = context.forMode(normalized.mode)
        val sourceLanguage = TranslationLanguageCatalog.source(normalized.sourceLanguageCode)
        val targetLanguage = TranslationLanguageCatalog.target(normalized.targetLanguageCode)

        return buildString {
            appendLine(baseInstruction)
            appendLine()
            appendLine("【翻译方向：${sourceLanguage.label} → ${targetLanguage.label}】")
            if (sourceLanguage.code == "auto") {
                appendLine("自动识别输入语音语言，并统一翻译为${targetLanguage.label}。")
            } else {
                appendLine("输入语音应为${sourceLanguage.label}；将其翻译为${targetLanguage.label}。")
            }
            appendLine()
            appendLine("【输入模式：${normalized.mode.label}】")
            appendLine(modeInstruction(normalized.mode))
            appendLine()
            appendLine("【场景：${scene.label}】")
            appendLine(scene.instruction)
            appendSessionContext(modeContext)
            if (normalized.advancedInstruction.isNotBlank()) {
                appendLine()
                appendLine("【方案提示词】")
                appendLine(normalized.advancedInstruction)
            }
        }.trim()
    }

    /** UI 只展示用户选择和提供的资料，不暴露内置基础/模式提示词。 */
    fun visibleContextPreview(
        scene: ScenePromptPreset,
        context: SessionPromptContext = SessionPromptContext(),
        plan: TranslationPlan,
    ): String {
        val normalized = plan.normalized()
        val modeContext = context.forMode(normalized.mode)
        return buildString {
            appendLine("翻译：${normalized.directionLabel}")
            appendLine("模式：${normalized.mode.label}")
            appendLine("场景：${scene.label}")
            appendLine("场景要求：${scene.instruction}")
            appendSessionContext(modeContext)
            if (normalized.advancedInstruction.isNotBlank()) {
                appendLine()
                appendLine("【方案提示词】")
                appendLine(normalized.advancedInstruction)
            }
        }.trim()
    }

    private fun SessionPromptContext.forMode(mode: TranslationMode): SessionPromptContext =
        if (mode == TranslationMode.INTERPRETATION) copy(video = null) else this

    private fun StringBuilder.appendSessionContext(context: SessionPromptContext) {
        val video = context.video
        val manual = context.manualContext.trim()
        if (video == null && manual.isEmpty()) return

        appendLine()
        appendLine("【仅本场有效的上下文】")
        if (video != null) {
            if (video.title.isNotEmpty()) appendLine("视频标题：${video.title}")
            if (video.authorName.isNotEmpty()) appendLine("频道/作者：${video.authorName}")
        }
        if (manual.isNotEmpty()) appendLine(manual)
    }
}
