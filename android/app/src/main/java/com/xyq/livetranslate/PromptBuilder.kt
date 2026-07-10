package com.xyq.livetranslate

/** 当前会话的临时 prompt：不保存进主播资料，只注入本次 Gemini Live 会话。 */
data class SessionPromptContext(
    val video: YouTubeVideoInfo? = null,
    val manualContext: String = "",
) {
    fun isBlank(): Boolean = video == null && manualContext.isBlank()
}

/**
 * 把「固定主播资料」和「临时会话上下文」组合成最终 systemInstruction。
 * 原则：翻译模型只关心"翻的是谁、这场在干嘛、专名怎么译"——
 * 只给名词和译法，不给链接、App 内部概念和它做不到的要求（如"保持低延迟"）。
 */
object PromptBuilder {
    fun build(
        profile: StreamerProfile,
        session: SessionPromptContext = SessionPromptContext(),
    ): String = buildString {
        appendLine("你是日语 VTuber 直播的实时字幕翻译，把听到的日语翻译成自然、口语化的中文字幕。忠实原意，不解释、不总结，没听清的内容宁可略过也不要编造。")
        appendLine()

        appendLine("主播：${joinNonBlank(profile.nameJp, profile.nameZh, profile.key)}")
        if (profile.affiliation.isNotBlank()) appendLine("所属/设定：${profile.affiliation}")
        if (profile.category.isNotBlank()) appendLine("分类：${profile.category}")
        if (profile.aliases.isNotEmpty()) appendLine("别名/口癖：${profile.aliases.joinToString("、")}")

        if (!session.isBlank()) {
            appendLine()
            appendLine("本场直播：")
            session.video?.let { video ->
                if (video.title.isNotBlank()) appendLine("- 标题：${video.title}")
                if (video.authorName.isNotBlank()) appendLine("- 频道：${video.authorName}")
            }
            if (session.manualContext.isNotBlank()) {
                appendLine(session.manualContext.trim())
            }
        }

        if (profile.terms.isNotEmpty()) {
            appendLine()
            appendLine("术语固定译法：")
            profile.terms.forEach { appendLine("- $it") }
        }

        if (profile.misheard.isNotEmpty()) {
            appendLine()
            appendLine("常见错听修正：")
            profile.misheard.forEach { appendLine("- $it") }
        }

        appendLine()
        appendLine("中文风格：")
        appendLine("- 面向粉丝的实时字幕，简洁易读")
        appendLine("- 专名按上面的固定译法，拿不准的保留原文")
        appendLine("- 语气跟着主播走，吐槽处可以口语化；口癖不必每次机械直译")
        appendLine("- 听众 ID 等拿不准的名字不要硬编")
        if (profile.style.isNotBlank()) appendLine("- ${profile.style}")
    }.trim()

    private fun joinNonBlank(vararg xs: String): String =
        xs.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" / ")
}
