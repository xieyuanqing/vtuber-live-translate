package com.xyq.livetranslate

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = LiveTranslateApp::class)
class AppLocaleTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun cleanup() {
        StatusBus.serviceRunning = false
        StatusBus.captureMode = ""
        StatusBus.reset()
        context.getSharedPreferences(AppLocale.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences("translation_plans_v3", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        AppLocale.apply(AppLocale.TAG_SYSTEM)
    }

    @Test
    fun defaultLocaleIsSystem() {
        assertEquals(AppLocale.TAG_SYSTEM, AppLocale.current(context))
    }

    @Test
    fun saveAndReadZhHansAndEn() {
        AppLocale.save(context, AppLocale.TAG_ZH_HANS)
        assertEquals(AppLocale.TAG_ZH_HANS, AppLocale.current(context))
        val sp = context.getSharedPreferences(AppLocale.PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals(AppLocale.TAG_ZH_HANS, sp.getString(AppLocale.KEY_APP_LANGUAGE, null))

        AppLocale.save(context, AppLocale.TAG_EN)
        assertEquals(AppLocale.TAG_EN, AppLocale.current(context))
        assertEquals(AppLocale.TAG_EN, sp.getString(AppLocale.KEY_APP_LANGUAGE, null))

        AppLocale.save(context, AppLocale.TAG_SYSTEM)
        assertEquals(AppLocale.TAG_SYSTEM, AppLocale.current(context))
        assertEquals(AppLocale.TAG_SYSTEM, sp.getString(AppLocale.KEY_APP_LANGUAGE, null))
    }

    @Test
    fun invalidTagFallsBackToSystem() {
        val sp = context.getSharedPreferences(AppLocale.PREFS_NAME, Context.MODE_PRIVATE)
        listOf("fr", "unknown", "zh_CN", "", "ja", "123").forEach { invalid ->
            sp.edit().putString(AppLocale.KEY_APP_LANGUAGE, invalid).commit()
            assertEquals(
                "Expected fallback to system for invalid tag: $invalid",
                AppLocale.TAG_SYSTEM,
                AppLocale.current(context),
            )
        }
    }

    @Test
    fun languageChangeDoesNotAlterTranslationPlanLanguages() {
        val interpPlan = TranslationPlan.default(TranslationMode.INTERPRETATION).copy(
            sourceLanguageCode = "ja",
            targetLanguageCode = "zh",
        )
        val videoPlan = TranslationPlan.default(TranslationMode.VIDEO).copy(
            sourceLanguageCode = "ja",
            targetLanguageCode = "zh",
        )
        TranslationPlanStore.saveDraft(context, interpPlan)
        TranslationPlanStore.saveDraft(context, videoPlan)

        // 切换语言为 en
        AppLocale.saveAndApply(context, AppLocale.TAG_EN)
        assertEquals(AppLocale.TAG_EN, AppLocale.current(context))

        // 验证两个模式下的翻译语言方向完全未被更改
        val loadedInterpEn = TranslationPlanStore.loadDraft(context, TranslationMode.INTERPRETATION)
        val loadedVideoEn = TranslationPlanStore.loadDraft(context, TranslationMode.VIDEO)
        assertEquals("ja", loadedInterpEn.sourceLanguageCode)
        assertEquals("zh", loadedInterpEn.targetLanguageCode)
        assertEquals("ja", loadedVideoEn.sourceLanguageCode)
        assertEquals("zh", loadedVideoEn.targetLanguageCode)

        // 再次切换为 zh-Hans
        AppLocale.saveAndApply(context, AppLocale.TAG_ZH_HANS)
        assertEquals(AppLocale.TAG_ZH_HANS, AppLocale.current(context))

        val loadedInterpZh = TranslationPlanStore.loadDraft(context, TranslationMode.INTERPRETATION)
        val loadedVideoZh = TranslationPlanStore.loadDraft(context, TranslationMode.VIDEO)
        assertEquals("ja", loadedInterpZh.sourceLanguageCode)
        assertEquals("zh", loadedInterpZh.targetLanguageCode)
        assertEquals("ja", loadedVideoZh.sourceLanguageCode)
        assertEquals("zh", loadedVideoZh.targetLanguageCode)
    }

    @Test
    fun languageChangeDoesNotInterruptRunningServiceOrEmitStopIntent() {
        StatusBus.serviceRunning = true
        StatusBus.captureMode = StatusBus.MODE_MIC

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        // 模拟切换语言
        AppLocale.saveAndApply(activity, AppLocale.TAG_EN)

        // 切换语言触发 recreate，验证生命周期
        controller.recreate()

        // 确认服务运行状态未受影响
        assertTrue("CaptureService 应该保持运行状态", StatusBus.serviceRunning)

        val shadowApp = Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
        assertNull("不应该停止任何服务", shadowApp.nextStoppedService)

        // 检查未发出任何包含 ACTION_STOP 的 Intent
        val stopIntents = shadowApp.broadcastIntents.filter { it.action == CaptureService.ACTION_STOP }
        assertTrue("绝不能发出 CaptureService.ACTION_STOP 广播", stopIntents.isEmpty())
    }

    @Test
    fun appStringsProvidesLocalizedLookupWithoutContext() {
        AppStrings.init(context)

        // zh-Hans 下资源查找
        AppLocale.save(context, AppLocale.TAG_ZH_HANS)
        val zhTitle = AppStrings.get(R.string.locale_dialog_title)
        assertEquals("界面语言", zhTitle)
        val zhDesc = AppStrings.get("settings_language_desc")
        assertEquals("切换应用界面的显示语言", zhDesc)

        // en 下资源查找
        AppLocale.save(context, AppLocale.TAG_EN)
        val enTitle = AppStrings.get(R.string.locale_dialog_title)
        assertEquals("App Language", enTitle)
        val enDesc = AppStrings.get("settings_language_desc")
        assertEquals("Change the display language of the app interface", enDesc)
        val enEnglishOption = AppStrings.get(R.string.locale_option_en)
        assertEquals("English", enEnglishOption)
    }

    @Test
    fun mainActivityShowsLanguageSelectionDialogAndAppliesSelection() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        activity.showLanguageSelectionDialog()

        val dialog = ShadowDialog.getLatestDialog() as? AlertDialog
        assertNotNull("应该弹出语言选择对话框", dialog)

        // MaterialAlertDialog 的标题不经由 ShadowDialog.title 暴露，直接断言真正重要的行为：
        // 三个选项齐全、文案取自资源、点选后设置真的生效。
        val listView = dialog!!.listView
        assertNotNull("对话框应包含单选项列表", listView)
        assertEquals(3, listView.adapter.count)
        assertEquals(
            activity.getString(R.string.locale_option_system),
            listView.adapter.getItem(0).toString(),
        )
        assertEquals(
            activity.getString(R.string.locale_option_zh_hans),
            listView.adapter.getItem(1).toString(),
        )
        assertEquals(
            activity.getString(R.string.locale_option_en),
            listView.adapter.getItem(2).toString(),
        )

        // 点击英文选项（index 2）
        listView.performItemClick(listView.adapter.getView(2, null, listView), 2, 2L)

        assertEquals(AppLocale.TAG_EN, AppLocale.current(activity))
    }
}
