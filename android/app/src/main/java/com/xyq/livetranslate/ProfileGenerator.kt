package com.xyq.livetranslate

import org.json.JSONObject

/**
 * 用第二 AI 根据 YouTube 视频链接自动生成：
 * - 主播资料（[StreamerProfile] 全部字段）
 * - 本场临时提示词
 *
 * 调用方在后台线程使用。
 */
object ProfileGenerator {

    /**
     * 一次 AI 调用返回两份产物。
     */
    data class GeneratedResult(
        val profile: StreamerProfile?,
        val temporaryContext: String,
        /** AI 自己对生成内容的简短解释（方便用户判断靠不靠谱） */
        val note: String,
    )

    private val SYSTEM_PROMPT = """
你是一个专业的 VTuber 圈子信息整理助手。用户会给你一个 YouTube 直播链接和它的元数据（标题、频道名），你需要基于你对 VTuber 文化的了解（必要时联网搜索），生成一份结构化的 JSON。

这份 JSON 的用途：注入给一个「日语音频 → 中文字幕」的实时翻译模型当背景知识。翻译模型只能利用名词、译法和场景信息，所以所有字段都要往"帮它听对、译对专名"的方向写，不要写给人看的介绍文。

你的回答必须是一个严格的 JSON 对象，不能包含任何 markdown 代码块标记、说明文字或注释。JSON 结构如下：

{
  "profile": {
    "key": "资料名（下拉列表显示名，建议用日文名或中文名，如 風真いろは）",
    "nameJp": "日文名（直播时主播常用的日文名，如 風真いろは）",
    "nameZh": "中文名（大陆圈内粉丝常用中文称呼，没有就空字符串）",
    "affiliation": "所属/人设（如 hololive 6期生/侍、Nijisanji / 彩虹社、个人勢，有设定背景可补充一句话）",
    "category": "分类（hololive / Nijisanji / 彩虹社 / 个人勢 / VSPO / 其他）",
    "aliases": ["粉丝常用昵称/简称/口癖语尾，每条一个，如 风真队、ござる"],
    "terms": ["直播里会反复出现的专名固定译法，每条一个，格式如 メンバーシップ = 会员、スパチャ = SC，优先高频词，5–15 条为宜"],
    "misheard": ["容易听错的近似词修正，每条一个，格式如 風間→風真，不确定可空"],
    "style": "翻译风格建议（一句话，如 保留她特有的敬语口吻，吐槽处可稍微口语化）"
  },
  "temporaryContext": "本场直播的临时翻译提示（纯文本）。写给翻译模型看：本场主题/环节是什么，可能反复出现的专有名词及译法（游戏名、歌名、活动名、可能连动的成员名）。100–250 字，信息密度优先。",
  "note": "一句话简短备注（如 推测依据标题、是否高置信度、需要用户注意的点）"
}

规则：
- 如果你不认识这个主播/频道，诚实标注，profile 字段用你能推断出的最小信息填（至少 key/nameJp/nameZh/category 尽量填），note 里说明"不熟悉该主播，信息可能不准确"。
- 别名、术语、错听修正如果不确定可以留空数组 []，不要编造。
- category 尽量从 hololive / Nijisanji / 彩虹社 / 个人勢 / VSPO / 其他 中选一个最合理的。
- temporaryContext 要具体：标题里的游戏名、歌回、新衣装、周年、连动对象一定点出来并给出中文叫法；不要复述链接或频道名，不要"本场应该会很有趣"之类对翻译没帮助的话；实在推断不出内容就只写一句主题猜测。
""".trimIndent()

    /**
     * 根据 YouTube 视频信息生成。
     * @param videoInfo 从 oEmbed 拿到的标题和频道
     * @param baseUrl / apiKey / model / format 第二 AI 的连接参数
     */
    fun generate(
        videoInfo: YouTubeVideoInfo,
        baseUrl: String,
        apiKey: String,
        model: String,
        format: AiTextClient.Format,
    ): GeneratedResult {
        val userPrompt = buildString {
            appendLine("请根据以下 YouTube 直播信息生成主播资料和本场提示词：")
            appendLine()
            appendLine("视频标题：${videoInfo.title}")
            appendLine("频道名：${videoInfo.authorName}")
            appendLine("URL：${videoInfo.url}")
            appendLine()
            appendLine("注意：如果频道名或标题中包含 VTuber 名字，请用你对 VTuber 圈子的了解来推断完整信息。")
        }

        val resp = AiTextClient.generate(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = userPrompt,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            format = format,
        )

        return parseResult(resp)
    }

    private fun parseResult(json: JSONObject): GeneratedResult {
        val profileJson = json.optJSONObject("profile")
        val profile = if (profileJson != null) {
            runCatching { StreamerProfileJson.decode(profileJson) }.getOrNull()
        } else {
            null
        }

        val tempContext = json.optString("temporaryContext", "")
        val note = json.optString("note", "")

        return GeneratedResult(
            profile = profile,
            temporaryContext = tempContext.ifBlank { "（AI 未生成本场提示词，可手动补充）" },
            note = note.ifBlank { "已生成" },
        )
    }
}