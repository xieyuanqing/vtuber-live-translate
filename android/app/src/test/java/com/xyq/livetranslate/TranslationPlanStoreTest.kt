package com.xyq.livetranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class TranslationPlanStoreTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @After
    fun clearStore() {
        context.getSharedPreferences("translation_plans_v3", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences("scene_library_v1", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun draftKeepsLanguageAndSceneIndependentlyPerMode() {
        clearStore()
        val interp = TranslationPlan.default(TranslationMode.INTERPRETATION).copy(
            sourceLanguageCode = "ja",
            targetLanguageCode = "zh",
        )
        val video = TranslationPlan.default(TranslationMode.VIDEO).copy(
            sourceLanguageCode = "en",
            scenePresetId = "livestream",
        )
        TranslationPlanStore.saveDraft(context, interp)
        TranslationPlanStore.saveDraft(context, video)

        val loadedInterp = TranslationPlanStore.loadDraft(context, TranslationMode.INTERPRETATION)
        val loadedVideo = TranslationPlanStore.loadDraft(context, TranslationMode.VIDEO)
        assertEquals("ja", loadedInterp.sourceLanguageCode)
        assertEquals("en", loadedVideo.sourceLanguageCode)
        assertEquals("livestream", loadedVideo.scenePresetId)
    }

    @Test
    fun missingSceneReferencesResolveAtUseTimeWithoutChangingStoredIds() {
        clearStore()
        val mode = TranslationMode.VIDEO
        val custom = requireNotNull(SceneLibraryStore.create(context, mode, "临时", "临时场景"))
        val plan = TranslationPlan.default(mode).copy(scenePresetId = custom.id)
        TranslationPlanStore.saveDraft(context, plan)

        SceneLibraryStore.delete(context, mode, custom.id)

        val draft = TranslationPlanStore.loadDraft(context, mode)
        val fallbackId = SceneLibraryStore.default(context, mode).id
        assertEquals(custom.id, draft.scenePresetId)
        assertEquals(fallbackId, SceneLibraryStore.resolve(context, mode, draft.scenePresetId).id)
    }

    @Test
    fun sceneResetDoesNotRewriteDraftReference() {
        clearStore()
        val mode = TranslationMode.VIDEO
        val original = TranslationPlan.default(mode).copy(scenePresetId = "anime")
        TranslationPlanStore.saveDraft(context, original)

        SceneLibraryStore.delete(context, mode, "anime")
        val fallbackId = SceneLibraryStore.default(context, mode).id
        assertEquals("anime", TranslationPlanStore.loadDraft(context, mode).scenePresetId)
        assertEquals(fallbackId, SceneLibraryStore.resolve(context, mode, "anime").id)

        SceneLibraryStore.reset(context, mode)
        assertEquals("anime", TranslationPlanStore.loadDraft(context, mode).scenePresetId)
        assertEquals("anime", SceneLibraryStore.resolve(context, mode, "anime").id)
    }

    @Test
    fun legacySavedPlansWithExtraPromptFoldIntoSceneLibraryOnce() {
        clearStore()
        val mode = TranslationMode.VIDEO
        val baseScene = SceneLibraryStore.resolve(context, mode, "livestream")
        // 手写旧版方案库 JSON：一条带额外提示词、一条纯别名。
        val legacy = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "plan-1")
                put("name", "英文直播")
                put("plan", JSONObject().apply {
                    put("mode", mode.storageKey)
                    put("scenePresetId", "livestream")
                    put("advancedInstruction", "保留主播口头禅")
                })
            })
            put(JSONObject().apply {
                put("id", "plan-2")
                put("name", "纯别名方案")
                put("plan", JSONObject().apply {
                    put("mode", mode.storageKey)
                    put("scenePresetId", "anime")
                    put("advancedInstruction", "")
                })
            })
        }
        val prefs = context.getSharedPreferences("translation_plans_v3", Context.MODE_PRIVATE)
        prefs.edit().putString("saved_" + mode.storageKey, legacy.toString()).commit()

        TranslationPlanStore.migrateLegacySavedPlans(context)

        val scenes = SceneLibraryStore.list(context, mode)
        val migrated = scenes.single { it.label == "英文直播" }
        assertTrue(migrated.instruction.contains(baseScene.instruction))
        assertTrue(migrated.instruction.contains("保留主播口头禅"))
        // 纯别名不生成重复场景。
        assertFalse(scenes.any { it.label == "纯别名方案" })
        // 旧方案数据清空，且迁移只执行一次。
        assertNull(prefs.getString("saved_" + mode.storageKey, null))
        TranslationPlanStore.migrateLegacySavedPlans(context)
        assertEquals(1, SceneLibraryStore.list(context, mode).count { it.label == "英文直播" })
    }

    @Test
    fun draftDecodingIgnoresLegacyAdvancedInstructionField() {
        clearStore()
        val mode = TranslationMode.INTERPRETATION
        val legacyDraft = JSONObject().apply {
            put("mode", mode.storageKey)
            put("sourceLanguageCode", "ja")
            put("targetLanguageCode", "zh")
            put("scenePresetId", "meeting")
            put("advancedInstruction", "旧字段")
        }
        context.getSharedPreferences("translation_plans_v3", Context.MODE_PRIVATE)
            .edit()
            .putString("draft_" + mode.storageKey, legacyDraft.toString())
            .commit()

        val draft = TranslationPlanStore.loadDraft(context, mode)
        assertEquals("ja", draft.sourceLanguageCode)
        assertEquals("meeting", draft.scenePresetId)
    }
}
