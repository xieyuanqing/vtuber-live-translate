package com.xyq.livetranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
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
@Config(sdk = [33], application = android.app.Application::class)
class SceneLibraryStoreTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @After
    fun clearStores() {
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
        assertEquals(DefaultSceneCatalog.defaults(mode), fallback)
        assertNull(SceneLibraryStore.create(context, mode, "新场景", "不能覆盖损坏数据"))
        assertFalse(SceneLibraryStore.update(context, mode, fallback.first().copy(label = "修改")))
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
        assertEquals(DefaultSceneCatalog.defaults(mode), SceneLibraryStore.list(context, mode))
    }

    @Test
    fun defaultTemplateCanBeEditedAndResolvedByPlans() {
        val original = SceneLibraryStore.resolve(context, TranslationMode.INTERPRETATION, "meeting")
        val changed = original.copy(
            label = "内部会议",
            instruction = "只关注决策、数字和待办事项。",
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
            original.copy(label = "已修改", instruction = "已修改的提示词"),
        )
        SceneLibraryStore.create(context, mode, "临时场景", "临时提示词")

        SceneLibraryStore.reset(context, mode)

        val restored = SceneLibraryStore.list(context, mode)
        assertEquals(DefaultSceneCatalog.defaults(mode), restored)
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
}
