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
 * 锁住「界面语言与翻译行为相互独立」这条边界。
 *
 * 阶段2 把 UI 显示名做成了资源（跟随界面语言），但发给模型的 systemInstruction
 * 必须保持恒定。否则用户把界面切成英文，翻译 prompt 也跟着变，等于悄悄改了翻译行为。
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
    fun systemInstructionStaysIdenticalAcrossAppLanguages() {
        AppLocale.save(context, AppLocale.TAG_ZH_HANS)
        AppLocale.apply(AppLocale.TAG_ZH_HANS)
        val zhPrompt = buildPrompt()

        AppLocale.save(context, AppLocale.TAG_EN)
        AppLocale.apply(AppLocale.TAG_EN)
        val enPrompt = buildPrompt()

        assertEquals("界面语言不得改变发给模型的 prompt", zhPrompt, enPrompt)
        assertTrue("翻译方向必须保持固定中文标签", zhPrompt.contains("【翻译方向：英语 → 简体中文】"))
        assertTrue("输入模式必须保持固定中文标签", zhPrompt.contains("【输入模式：同传】"))
    }

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
