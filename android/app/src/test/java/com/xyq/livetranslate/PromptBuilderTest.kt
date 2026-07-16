package com.xyq.livetranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {
    @Test
    fun `mode catalogs stay independent`() {
        assertEquals(
            listOf("通用", "会议", "课堂", "采访", "旅行交流", "自定义"),
            ScenePromptCatalog.presets(TranslationMode.INTERPRETATION).map { it.label },
        )
        assertEquals(
            listOf("通用视频", "直播", "VTuber", "动漫", "游戏", "新闻", "课程", "自定义"),
            ScenePromptCatalog.presets(TranslationMode.VIDEO).map { it.label },
        )
    }

    @Test
    fun `interpretation prompt uses meeting scene without video bias`() {
        val prompt = PromptBuilder.build(
            glossary = null,
            context = SessionPromptContext(
                video = YouTubeVideoInfo(
                    url = "https://youtu.be/stale",
                    title = "不应进入同传的旧视频",
                    authorName = "旧频道",
                ),
                manualContext = "本次讨论 Aurora 项目的上线日期。",
            ),
            plan = TranslationPlan(
                mode = TranslationMode.INTERPRETATION,
                sourceLanguageCode = "ja",
                targetLanguageCode = "zh",
                scenePresetId = "meeting",
            ),
        )

        assertTrue(prompt.contains("【翻译方向：日语 → 中文】"))
        assertTrue(prompt.contains("【输入模式：同传】"))
        assertTrue(prompt.contains("【场景预设：会议】"))
        assertTrue(prompt.contains("Aurora 项目的上线日期"))
        assertTrue(prompt.contains("不回答、解释、总结或续写"))
        assertFalse(prompt.contains("输入来自视频、直播"))
        assertFalse(prompt.contains("VTuber 直播"))
        assertFalse(prompt.contains("不应进入同传的旧视频"))
        assertFalse(prompt.contains("旧频道"))
    }

    @Test
    fun `video prompt includes generic glossary and video metadata`() {
        val glossary = GlossaryProfile(
            id = "channel-terms",
            name = "测试频道术语",
            description = "周年直播资料",
            category = "人物 / 专名",
            aliases = listOf("Test"),
            terms = listOf("固有名詞 = 专名"),
            style = "自然口语",
        )
        val prompt = PromptBuilder.build(
            glossary = glossary,
            context = SessionPromptContext(
                video = YouTubeVideoInfo(
                    url = "https://youtu.be/example",
                    title = "周年直播",
                    authorName = "测试频道",
                ),
            ),
            plan = TranslationPlan(
                mode = TranslationMode.VIDEO,
                scenePresetId = "vtuber",
            ),
        )

        assertTrue(prompt.contains("【输入模式：视频】"))
        assertTrue(prompt.contains("【场景预设：VTuber】"))
        assertTrue(prompt.contains("资料名称：测试频道术语"))
        assertTrue(prompt.contains("说明：周年直播资料"))
        assertTrue(prompt.contains("视频标题：周年直播"))
        assertTrue(prompt.contains("频道/作者：测试频道"))
        assertFalse(prompt.contains("https://youtu.be/example"))
    }

    @Test
    fun `selected language direction is explicit in prompt and preview`() {
        val plan = TranslationPlan(
            mode = TranslationMode.VIDEO,
            scenePresetId = "general_video",
            sourceLanguageCode = "en",
            targetLanguageCode = "ja",
        )
        val prompt = PromptBuilder.build(
            glossary = null,
            context = SessionPromptContext(),
            plan = plan,
        )
        val preview = PromptBuilder.visibleContextPreview(
            glossary = null,
            context = SessionPromptContext(),
            plan = plan,
        )

        assertTrue(prompt.contains("【翻译方向：英语 → 日语】"))
        assertTrue(prompt.contains("输入语音应为英语；将其翻译为日语"))
        assertTrue(preview.startsWith("翻译：英语 → 日语"))
    }

    @Test
    fun `custom scene is used and hidden rules stay out of preview`() {
        val custom = "医学术语保留英文缩写，数字不得省略。"
        val plan = TranslationPlan(
            mode = TranslationMode.INTERPRETATION,
            scenePresetId = "custom",
            customSceneInstruction = custom,
        )
        val prompt = PromptBuilder.build(
            glossary = null,
            context = SessionPromptContext(),
            plan = plan,
        )
        val preview = PromptBuilder.visibleContextPreview(
            glossary = null,
            context = SessionPromptContext(manualContext = "本场讨论 CT 检查。"),
            plan = plan,
        )

        assertTrue(prompt.contains(custom))
        assertTrue(preview.contains(custom))
        assertTrue(preview.contains("本场讨论 CT 检查"))
        assertFalse(preview.contains("你是低延迟实时字幕翻译引擎"))
        assertFalse(preview.contains("输入来自现场麦克风"))
    }

    @Test
    fun `translation plan normalizes invalid values without crossing modes`() {
        val interpretation = TranslationPlan(
            mode = TranslationMode.INTERPRETATION,
            sourceLanguageCode = "xx",
            targetLanguageCode = "auto",
            scenePresetId = "vtuber",
            customSceneInstruction = "  只保留医学缩写。  ",
        ).normalized()
        val video = TranslationPlan.default(TranslationMode.VIDEO)

        assertEquals("auto", interpretation.sourceLanguageCode)
        assertEquals("zh", interpretation.targetLanguageCode)
        assertEquals("general", interpretation.scenePresetId)
        assertEquals("只保留医学缩写。", interpretation.customSceneInstruction)
        assertEquals("general_video", video.scenePresetId)
        assertEquals("自动检测 → 中文", interpretation.directionLabel)
    }

    @Test
    fun `plan composes only its own mode and session context`() {
        val plan = TranslationPlan(
            mode = TranslationMode.INTERPRETATION,
            sourceLanguageCode = "en",
            targetLanguageCode = "zh-Hans",
            scenePresetId = "meeting",
            customSceneInstruction = "视频侧不应读取这一项",
        )
        val prompt = PromptBuilder.build(
            glossary = null,
            context = SessionPromptContext(manualContext = "本场讨论 API 发布计划。"),
            plan = plan,
        )

        assertTrue(prompt.contains("【输入模式：同传】"))
        assertTrue(prompt.contains("【场景预设：会议】"))
        assertTrue(prompt.contains("【翻译方向：英语 → 简体中文】"))
        assertTrue(prompt.contains("本场讨论 API 发布计划"))
        assertFalse(prompt.contains("输入来自视频、直播"))
        assertFalse(prompt.contains("视频侧不应读取这一项"))
    }

    @Test
    fun `generic glossary contributes terminology without vtuber fields`() {
        val prompt = PromptBuilder.build(
            glossary = GlossaryProfile(
                id = "medical",
                name = "医学会议",
                category = "会议",
                terms = listOf("CT = CT", "MRI = MRI"),
                corrections = listOf("心房→心房"),
            ),
            context = SessionPromptContext(),
            plan = TranslationPlan(
                mode = TranslationMode.INTERPRETATION,
                scenePresetId = "meeting",
            ),
        )

        assertTrue(prompt.contains("【术语与场景资料】"))
        assertTrue(prompt.contains("资料名称：医学会议"))
        assertTrue(prompt.contains("固定译法：CT = CT；MRI = MRI"))
        assertFalse(prompt.contains("VTuber"))
    }
}
