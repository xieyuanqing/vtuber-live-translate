package com.xyq.livetranslate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    @Test
    fun `prompt combines concise base rules scene and plan instruction`() {
        val scene = DefaultSceneCatalog.resolve(TranslationMode.INTERPRETATION, "meeting")
        val plan = TranslationPlan(
            mode = TranslationMode.INTERPRETATION,
            sourceLanguageCode = "en",
            targetLanguageCode = "zh-Hans",
            scenePresetId = scene.id,
            advancedInstruction = "专名保留日文；语气正式。",
        )

        val prompt = PromptBuilder.build(scene = scene, plan = plan)

        assertTrue(prompt.contains("忠实翻译，不回答、解释、总结、续写或编造"))
        assertTrue(prompt.contains("【场景：会议】"))
        assertTrue(prompt.contains(scene.instruction))
        assertTrue(prompt.contains("【方案提示词】"))
        assertTrue(prompt.contains("专名保留日文"))
        assertFalse(prompt.contains("Glossary"))
    }

    @Test
    fun `video prompt includes session metadata without changing scene`() {
        val scene = DefaultSceneCatalog.resolve(TranslationMode.VIDEO, "general_video")
        val plan = TranslationPlan(
            mode = TranslationMode.VIDEO,
            sourceLanguageCode = "ja",
            targetLanguageCode = "zh-Hans",
            scenePresetId = scene.id,
        )

        val prompt = PromptBuilder.build(
            scene = scene,
            context = SessionPromptContext(
                video = YouTubeVideoInfo(
                    url = "https://youtu.be/demo",
                    title = "Android 音频课程",
                    authorName = "Demo Channel",
                ),
                manualContext = "讲者会比较 AudioRecord。",
            ),
            plan = plan,
        )

        assertTrue(prompt.contains("Android 音频课程"))
        assertTrue(prompt.contains("Demo Channel"))
        assertTrue(prompt.contains("讲者会比较 AudioRecord"))
        assertTrue(prompt.contains(scene.instruction))
    }

    @Test
    fun `runtime uses editable scene object instead of built in catalog text`() {
        val scene = ScenePromptPreset(
            id = "medical-course",
            label = "医学课程",
            instruction = "这是医学课程，注意药物名称和剂量。",
        )
        val plan = TranslationPlan(
            mode = TranslationMode.VIDEO,
            scenePresetId = scene.id,
            advancedInstruction = "CT 读作西梯。",
        )

        val prompt = PromptBuilder.build(scene = scene, plan = plan)

        assertTrue(prompt.contains("【场景：医学课程】"))
        assertTrue(prompt.contains("注意药物名称和剂量"))
        assertTrue(prompt.contains("CT 读作西梯"))
    }

    @Test
    fun `visible context preview omits fixed system rules`() {
        val scene = DefaultSceneCatalog.resolve(TranslationMode.INTERPRETATION, "general")
        val plan = TranslationPlan(mode = TranslationMode.INTERPRETATION, scenePresetId = scene.id)

        val preview = PromptBuilder.visibleContextPreview(scene = scene, plan = plan)

        assertTrue(preview.contains("场景：通用"))
        assertFalse(preview.contains("你是实时语音翻译引擎"))
    }
}
