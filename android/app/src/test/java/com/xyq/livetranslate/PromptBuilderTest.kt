package com.xyq.livetranslate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {
    @Test
    fun `plan prompt includes scene and advanced instruction`() {
        val plan = TranslationPlan(
            mode = TranslationMode.INTERPRETATION,
            sourceLanguageCode = "ja",
            targetLanguageCode = "zh",
            scenePresetId = "meeting",
            advancedInstruction = "专名保留日文；语气正式。",
        )
        val prompt = PromptBuilder.build(plan = plan)
        assertTrue(prompt.contains("会议"))
        assertTrue(prompt.contains("【方案提示词】"))
        assertTrue(prompt.contains("专名保留日文"))
        assertFalse(prompt.contains("【仅本场有效的上下文】"))
        assertFalse(prompt.contains("【术语与场景资料】"))
    }

    @Test
    fun `custom scene uses custom instruction`() {
        val plan = TranslationPlan(
            mode = TranslationMode.VIDEO,
            scenePresetId = "custom",
            customSceneInstruction = "这是医学课程。",
            advancedInstruction = "CT 读作西梯。",
        )
        val prompt = PromptBuilder.build(plan = plan)
        assertTrue(prompt.contains("这是医学课程。"))
        assertTrue(prompt.contains("CT 读作西梯。"))
    }

    @Test
    fun `visible preview only shows user plan fields`() {
        val plan = TranslationPlan(
            mode = TranslationMode.INTERPRETATION,
            scenePresetId = "general",
            advancedInstruction = "本场讨论 API 发布计划。",
        )
        val preview = PromptBuilder.visibleContextPreview(plan = plan)
        assertTrue(preview.contains("同传"))
        assertTrue(preview.contains("【方案提示词】"))
        assertTrue(preview.contains("本场讨论 API 发布计划"))
        assertFalse(preview.contains("低延迟实时字幕翻译引擎"))
    }
}
