package com.xyq.livetranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// 场景模板名现在取自资源，显式固定为中文 locale，断言的才是中文模板名。
@Config(sdk = [33], application = android.app.Application::class)
class SceneLibraryStoreTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun useChineseUi() {
        // 场景模板名取自资源。中文模板在默认 values/ 下，因此显式把 App 界面语言
        // 设为简体中文，断言的才是中文模板名（走的正是生产代码的语言切换路径）。
        AppLocale.setApplicationContextForTest(context)
        AppLocale.save(context, AppLocale.TAG_ZH_HANS)
        AppLocale.apply(AppLocale.TAG_ZH_HANS)
    }

    @After
    fun clearStores() {
        AppLocale.save(context, AppLocale.TAG_SYSTEM)
        AppLocale.apply(AppLocale.TAG_SYSTEM)
        context.getSharedPreferences("scene_library_v1", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences("translation_plans_v3", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun seedsEditableDefaultsSeparatelyForBothModes() {
        val interpretation = SceneLibraryStore.list(context, TranslationMode.INTERPRETATION)
        val video = SceneLibraryStore.list(context, TranslationMode.VIDEO)

        assertTrue(interpretation.any { it.id == "general" && it.label == "通用" })
        assertTrue(interpretation.any { it.id == "travel" })
        assertTrue(video.any { it.id == "general_video" && it.label == "通用视频" })
        assertTrue(video.any { it.id == "vtuber" })
        assertFalse(interpretation.any { item -> video.any { it.id == item.id } })
    }

    @Test
    fun corruptExistingLibraryIsNotSilentlyOverwritten() {
        val mode = TranslationMode.INTERPRETATION
        val storage = context.getSharedPreferences("scene_library_v1", Context.MODE_PRIVATE)
        val key = "items_${mode.storageKey}"
        storage.edit().putString(key, "not-json").commit()

        val fallback = SceneLibraryStore.list(context, mode)
        assertSameScenes(DefaultSceneCatalog.defaults(mode), fallback)
        assertNull(SceneLibraryStore.create(context, mode, "新场景", "不能覆盖损坏数据"))
        assertFalse(SceneLibraryStore.update(context, mode, fallback.first().copy(labelText = "修改")))
        assertFalse(SceneLibraryStore.delete(context, mode, fallback.first().id))
        assertFalse(SceneLibraryStore.setDefault(context, mode, fallback.last().id))
        assertEquals("not-json", storage.getString(key, null))
    }

    @Test
    fun partiallyCorruptLibraryRemainsReadOnlyUntilExplicitReset() {
        val mode = TranslationMode.VIDEO
        val storage = context.getSharedPreferences("scene_library_v1", Context.MODE_PRIVATE)
        val key = "items_${mode.storageKey}"
        val raw = """[{"id":"valid","label":"可读场景","instruction":"保留它"},{"id":"","label":"损坏","instruction":"坏条目"}]"""
        storage.edit().putString(key, raw).commit()

        assertEquals(listOf(ScenePromptPreset("valid", "可读场景", "保留它")), SceneLibraryStore.list(context, mode))
        assertNull(SceneLibraryStore.create(context, mode, "新场景", "仍不能覆盖"))
        assertFalse(SceneLibraryStore.delete(context, mode, "valid"))
        assertEquals(raw, storage.getString(key, null))

        SceneLibraryStore.reset(context, mode)
        assertSameScenes(DefaultSceneCatalog.defaults(mode), SceneLibraryStore.list(context, mode))
    }

    @Test
    fun defaultTemplateCanBeEditedAndResolvedByPlans() {
        val original = SceneLibraryStore.resolve(context, TranslationMode.INTERPRETATION, "meeting")
        val changed = original.copy(
            labelText = "内部会议",
            instructionText = "只关注决策、数字和待办事项。",
        )

        assertTrue(SceneLibraryStore.update(context, TranslationMode.INTERPRETATION, changed))
        val resolved = SceneLibraryStore.resolve(context, TranslationMode.INTERPRETATION, "meeting")

        assertEquals("内部会议", resolved.label)
        assertEquals("只关注决策、数字和待办事项。", resolved.instruction)
        assertEquals("meeting", TranslationPlan(mode = TranslationMode.INTERPRETATION, scenePresetId = "meeting").scenePresetId)
    }

    @Test
    fun userCanCreateSceneAndMakeItTheModeDefault() {
        val created = requireNotNull(SceneLibraryStore.create(
            context,
            TranslationMode.VIDEO,
            "医学课程",
            "准确处理医学名词，不额外解释。",
        ))

        assertTrue(SceneLibraryStore.setDefault(context, TranslationMode.VIDEO, created.id))
        assertEquals(created.id, SceneLibraryStore.default(context, TranslationMode.VIDEO).id)
        assertEquals(created.id, TranslationPlanStore.loadDraft(context, TranslationMode.VIDEO).scenePresetId)
        assertNotEquals(created.id, SceneLibraryStore.default(context, TranslationMode.INTERPRETATION).id)
    }

    @Test
    fun resetRestoresOriginalTemplatesAndRemovesCustomScenes() {
        val mode = TranslationMode.VIDEO
        val original = SceneLibraryStore.resolve(context, mode, "livestream")
        SceneLibraryStore.update(
            context,
            mode,
            original.copy(labelText = "已修改", instructionText = "已修改的提示词"),
        )
        SceneLibraryStore.create(context, mode, "临时场景", "临时提示词")

        SceneLibraryStore.reset(context, mode)

        val restored = SceneLibraryStore.list(context, mode)
        assertSameScenes(DefaultSceneCatalog.defaults(mode), restored)
        assertEquals("直播", SceneLibraryStore.resolve(context, mode, "livestream").label)
    }

    @Test
    fun deletingDefaultFallsBackToAnotherSceneButNeverDeletesLastOne() {
        val mode = TranslationMode.INTERPRETATION
        val created = requireNotNull(SceneLibraryStore.create(context, mode, "临时", "临时测试场景。"))
        SceneLibraryStore.setDefault(context, mode, created.id)

        assertTrue(SceneLibraryStore.delete(context, mode, created.id))
        assertNotEquals(created.id, SceneLibraryStore.default(context, mode).id)

        SceneLibraryStore.reset(context, mode)
        val all = SceneLibraryStore.list(context, mode)
        all.drop(1).forEach { assertTrue(SceneLibraryStore.delete(context, mode, it.id)) }
        assertFalse(SceneLibraryStore.delete(context, mode, SceneLibraryStore.list(context, mode).single().id))
    }

    /**
     * 没改过的内置场景名必须跟随界面语言。
     *
     * 旧实现把「当前语言下解析出的名字」写进存储，首次初始化用的什么语言就冻在什么语言，
     * 之后切界面语言再也不变。
     */
    @Test
    fun defaultSceneNamesFollowAppLanguageUntilRenamed() {
        val mode = TranslationMode.INTERPRETATION
        // 先在中文界面下完成首次初始化（@Before 已切中文）
        assertEquals("会议", SceneLibraryStore.resolve(context, mode, "meeting").label)

        AppLocale.save(context, AppLocale.TAG_EN)
        AppLocale.apply(AppLocale.TAG_EN)
        assertEquals(
            "没改过的内置场景名必须跟随界面语言",
            "Meeting",
            SceneLibraryStore.resolve(context, mode, "meeting").label,
        )

        AppLocale.save(context, AppLocale.TAG_ZH_HANS)
        AppLocale.apply(AppLocale.TAG_ZH_HANS)
        assertEquals("会议", SceneLibraryStore.resolve(context, mode, "meeting").label)
    }

    /** 用户改过名字就固定下来，切界面语言不得覆盖。 */
    @Test
    fun renamedSceneKeepsUserTextAcrossLanguages() {
        val mode = TranslationMode.INTERPRETATION
        val renamed = SceneLibraryStore.resolve(context, mode, "meeting").copy(labelText = "周会")
        assertTrue(SceneLibraryStore.update(context, mode, renamed))

        AppLocale.save(context, AppLocale.TAG_EN)
        AppLocale.apply(AppLocale.TAG_EN)
        assertEquals("周会", SceneLibraryStore.resolve(context, mode, "meeting").label)
    }

    /** 改回模板默认名（任一界面语言的写法）就当作没覆盖，重新跟随界面语言。 */
    @Test
    fun renamingBackToATemplateNameResumesFollowingAppLanguage() {
        val mode = TranslationMode.INTERPRETATION
        val renamed = SceneLibraryStore.resolve(context, mode, "meeting").copy(labelText = "周会")
        assertTrue(SceneLibraryStore.update(context, mode, renamed))

        val restored = SceneLibraryStore.resolve(context, mode, "meeting").copy(labelText = "会议")
        assertTrue(SceneLibraryStore.update(context, mode, restored))

        AppLocale.save(context, AppLocale.TAG_EN)
        AppLocale.apply(AppLocale.TAG_EN)
        assertEquals("Meeting", SceneLibraryStore.resolve(context, mode, "meeting").label)
    }

    /**
     * 首次初始化时的界面语言绝不能漏进发给模型的 prompt。
     *
     * 旧实现把英文展示名存成了 labelText，而 `promptLabel = labelText ?: promptLabelText`，
     * 于是英文界面下初始化过的机器，systemInstruction 里的场景名变成了英文。
     */
    @Test
    fun firstRunLanguageNeverLeaksIntoTheModelPrompt() {
        val mode = TranslationMode.INTERPRETATION
        AppLocale.save(context, AppLocale.TAG_EN)
        AppLocale.apply(AppLocale.TAG_EN)

        // 在英文界面下完成首次初始化
        assertEquals("Meeting", SceneLibraryStore.resolve(context, mode, "meeting").label)
        assertEquals(
            "发给模型的场景名必须固定中文",
            "会议",
            SceneLibraryStore.resolve(context, mode, "meeting").promptLabel,
        )

        AppLocale.save(context, AppLocale.TAG_ZH_HANS)
        AppLocale.apply(AppLocale.TAG_ZH_HANS)
        assertEquals("会议", SceneLibraryStore.resolve(context, mode, "meeting").promptLabel)
    }

    /** 存量数据：早期版本冻进去的英文名要被识别回「没改过」，而不是当成用户自定义。 */
    @Test
    fun legacyFrozenLabelsAreRecognizedAsUntouched() {
        val mode = TranslationMode.INTERPRETATION
        context.getSharedPreferences("scene_library_v1", Context.MODE_PRIVATE).edit()
            .putString(
                "items_interpretation",
                """[{"id":"meeting","label":"Meeting","instruction":"这是会议或商务讨论。"}]""",
            )
            .putString("default_interpretation", "meeting")
            .commit()

        assertEquals("会议", SceneLibraryStore.resolve(context, mode, "meeting").label)
        assertEquals("会议", SceneLibraryStore.resolve(context, mode, "meeting").promptLabel)
    }

    /** 没改过的内置场景描述也跟随界面语言。 */
    @Test
    fun defaultSceneInstructionsFollowAppLanguage() {
        val mode = TranslationMode.INTERPRETATION
        assertTrue(
            SceneLibraryStore.resolve(context, mode, "meeting").instruction
                .startsWith("这是会议或商务讨论"),
        )

        AppLocale.save(context, AppLocale.TAG_EN)
        AppLocale.apply(AppLocale.TAG_EN)
        assertTrue(
            "没改过的场景描述必须跟随界面语言",
            SceneLibraryStore.resolve(context, mode, "meeting").instruction
                .startsWith("This is a meeting or business discussion"),
        )
    }

    /** 存量数据：早期版本冻进去的中文描述要被识别回「没改过」。 */
    @Test
    fun legacyFrozenInstructionsAreRecognizedAsUntouched() {
        val mode = TranslationMode.INTERPRETATION
        context.getSharedPreferences("scene_library_v1", Context.MODE_PRIVATE).edit()
            .putString(
                "items_interpretation",
                """[{"id":"meeting","label":"Meeting",""" +
                    """"instruction":"这是会议或商务讨论。准确处理议题、结论、数字、职责和行动项，保持专业、简洁。"}]""",
            )
            .putString("default_interpretation", "meeting")
            .commit()

        AppLocale.save(context, AppLocale.TAG_EN)
        AppLocale.apply(AppLocale.TAG_EN)
        val scene = SceneLibraryStore.resolve(context, mode, "meeting")
        assertEquals("Meeting", scene.label)
        assertTrue(scene.instruction.startsWith("This is a meeting or business discussion"))
    }

    /** 只比较业务字段：内部的 labelRes / promptLabelText 不属于断言目标。 */
    private fun assertSameScenes(
        expected: List<ScenePromptPreset>,
        actual: List<ScenePromptPreset>,
    ) {
        assertEquals(
            expected.map { Triple(it.id, it.label, it.instruction) },
            actual.map { Triple(it.id, it.label, it.instruction) },
        )
    }

}
