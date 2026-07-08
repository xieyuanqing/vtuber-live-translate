package com.xyq.livetranslate

/** 当前会话的临时 prompt：不保存进主播资料，只注入本次 Gemini Live 会话。 */
data class SessionPromptContext(
    val video: YouTubeVideoInfo? = null,
    val manualContext: String = "",
) {
    fun isBlank(): Boolean = video == null && manualContext.isBlank()
}

/** 把“固定主播资料”和“临时会话上下文”组合成最终 systemInstruction。 */
object PromptBuilder {
    fun build(
        profile: StreamerProfile,
        session: SessionPromptContext = SessionPromptContext(),
    ): String = buildString {
        appendFixedProfile(profile)
        if (!session.isBlank()) appendTemporaryContext(session)
        appendStyleRules(profile)
    }.trim()

    fun buildFixedProfileBlock(profile: StreamerProfile): String = buildString {
        appendFixedProfile(profile)
    }.trim()

    fun buildTemporaryContextBlock(session: SessionPromptContext): String = buildString {
        appendTemporaryContext(session)
    }.trim()

    private fun StringBuilder.appendFixedProfile(profile: StreamerProfile) {
        appendLine("你正在进行真实直播场景下的低延迟字幕翻译。")
        appendLine()
        appendLine("任务：把日语 VTuber 直播音频实时翻译成自然中文。优先准确理解日语原意，其次保持低延迟和可读性。不要添加解释、总结或原文中不存在的信息。")
        appendLine()
        appendLine("固定提示词（主播资料）：")
        appendLine("- 主播：${joinNonBlank(profile.nameJp, profile.nameZh, profile.key)}")
        if (profile.affiliation.isNotBlank()) appendLine("- 所属/设定：${profile.affiliation}")
        if (profile.category.isNotBlank()) appendLine("- 分类：${profile.category}")
        if (profile.aliases.isNotEmpty()) appendLine("- 别名/口癖：${profile.aliases.joinToString("、")}")
        appendLine()

        if (profile.terms.isNotEmpty()) {
            appendLine("术语与专名：")
            profile.terms.forEach { appendLine("- $it") }
            appendLine()
        }

        if (profile.misheard.isNotEmpty()) {
            appendLine("常见错听修正：")
            profile.misheard.forEach { appendLine("- $it") }
            appendLine()
        }
    }

    private fun StringBuilder.appendTemporaryContext(session: SessionPromptContext) {
        appendLine("临时提示词（仅本次会话使用，不作为长期主播资料）：")
        session.video?.let { video ->
            appendLine("- 标题：${video.title}")
            appendLine("- 频道：${video.authorName}")
            appendLine("- URL：${video.url}")
        }
        if (session.manualContext.isNotBlank()) {
            appendLine("- 用户补充：${session.manualContext.trim()}")
        }
        appendLine()
    }

    private fun StringBuilder.appendStyleRules(profile: StreamerProfile) {
        appendLine("中文风格：")
        appendLine("- 输出自然中文，适合 VTuber 粉丝实时字幕。")
        appendLine("- 专名按上面的固定译法。")
        appendLine("- 保留主播语气；吐槽处可以稍微口语化。")
        appendLine("- 不要把每个口癖都机械翻译。")
        appendLine("- 不确定的听众 ID 不要硬编。")
        if (profile.style.isNotBlank()) appendLine("- ${profile.style}")
    }

    private fun joinNonBlank(vararg xs: String): String =
        xs.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" / ")
}
