package com.xyq.livetranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 锁住 prompt 的语言边界。
 *
 * 原规则是「systemInstruction 必须完全恒定」。2026-09-13 放宽了**一处**：内置场景的
 * 描述正文（[ScenePromptPreset.instruction]）跟随界面语言——英文界面的用户读不懂也改不了
 * 中文提示词，可用性比「两种界面语言下翻译行为完全一致」更要紧。
 *
 * 其余成分仍然固定中文，本测试逐条锁住：提示词底座、翻译方向、输入模式、场景名。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PromptLocaleIndependenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun resetLocale() {
        AppLocale.save(context, AppLocale.TAG_SYSTEM)
        AppLocale.apply(AppLocale.TAG_SYSTEM)
    }

    private fun buildPrompt(): String {
        val scene = DefaultSceneCatalog.resolve(TranslationMode.INTERPRETATION, "meeting")
        val plan = TranslationPlan(
            mode = TranslationMode.INTERPRETATION,
            sourceLanguageCode = "en",
            targetLanguageCode = "zh-Hans",
            scenePresetId = scene.id,
        )
        return PromptBuilder.build(scene = scene, plan = plan)
    }

    @Test
    fun onlyTheSceneBodyFollowsAppLanguage() {
        AppLocale.save(context, AppLocale.TAG_ZH_HANS)
        AppLocale.apply(AppLocale.TAG_ZH_HANS)
        val zhPrompt = buildPrompt()

        AppLocale.save(context, AppLocale.TAG_EN)
        AppLocale.apply(AppLocale.TAG_EN)
        val enPrompt = buildPrompt()

        // 唯一允许跟随界面语言的成分：场景描述正文
        assertTrue("英文界面下场景描述应为英文", enPrompt.contains("This is a meeting or business discussion"))
        assertTrue("中文界面下场景描述应为中文", zhPrompt.contains("这是会议或商务讨论"))

        // 其余成分逐条固定中文
        listOf(
            "【翻译方向：英语 → 简体中文】",
            "【输入模式：同传】",
            "【场景：会议】",
            "你是实时语音翻译引擎：",
            "输入来自麦克风现场语音",
        ).forEach { fixed ->
            assertTrue("「$fixed」必须固定中文", zhPrompt.contains(fixed))
            assertTrue("「$fixed」必须固定中文，不得跟随界面语言", enPrompt.contains(fixed))
        }

        // 两份 prompt 的差异必须**只**来自场景描述那一段
        assertEquals(
            "除场景描述外，prompt 不得随界面语言变化",
            zhPrompt.replace(zhSceneBody(), ""),
            enPrompt.replace(enSceneBody(), ""),
        )
    }

    private fun zhSceneBody(): String =
        "这是会议或商务讨论。准确处理议题、结论、数字、职责和行动项，保持专业、简洁。"

    private fun enSceneBody(): String = context.getString(R.string.rt_scene_meeting_instruction)

    @Test
    fun languageCodesNeverChangeWithAppLanguage() {
        AppLocale.save(context, AppLocale.TAG_EN)
        AppLocale.apply(AppLocale.TAG_EN)

        // code 是业务数据，任何界面语言下都必须一致
        assertEquals("auto", TranslationLanguageCatalog.source("auto").code)
        assertEquals("zh-Hans", TranslationLanguageCatalog.target("zh-Hans").code)

        // promptLabel 是写进 prompt 的固定中文，不跟随界面语言
        assertEquals("自动检测", TranslationLanguageCatalog.source("auto").promptLabel)
        assertEquals("简体中文", TranslationLanguageCatalog.target("zh-Hans").promptLabel)

        // label 是界面展示名，英文界面下应为英文
        assertEquals("Auto detect", TranslationLanguageCatalog.source("auto").label)
        assertEquals("Simplified Chinese", TranslationLanguageCatalog.target("zh-Hans").label)
    }

    /** 用户改过的描述是用户数据，任何界面语言下都原样发给模型。 */
    @Test
    fun userEditedSceneInstructionNeverFollowsAppLanguage() {
        val mode = TranslationMode.INTERPRETATION
        val mine = "这是我自己的会议提示词，别动它。"
        val edited = SceneLibraryStore.resolve(context, mode, "meeting").copy(instructionText = mine)
        assertTrue(SceneLibraryStore.update(context, mode, edited))

        AppLocale.save(context, AppLocale.TAG_EN)
        AppLocale.apply(AppLocale.TAG_EN)
        assertEquals(mine, SceneLibraryStore.resolve(context, mode, "meeting").instruction)
    }

    @Test
    fun userEditedSceneNameSurvivesLanguageSwitch() {
        val mode = TranslationMode.INTERPRETATION
        val original = SceneLibraryStore.resolve(context, mode, "meeting")
        val renamed = original.copy(labelText = "我的专属会议场景")
        assertTrue(SceneLibraryStore.update(context, mode, renamed))

        AppLocale.save(context, AppLocale.TAG_EN)
        AppLocale.apply(AppLocale.TAG_EN)

        val afterSwitch = SceneLibraryStore.resolve(context, mode, "meeting")
        assertEquals(
            "用户改过的场景名不能被界面语言覆盖",
            "我的专属会议场景",
            afterSwitch.label,
        )
    }
}
