package com.xyq.livetranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun editingSavedPlanUpdatesInPlaceWithoutChangingDraft() {
        clearStore()
        val mode = TranslationMode.VIDEO
        val originalDraft = TranslationPlan.default(mode)
        TranslationPlanStore.saveDraft(context, originalDraft)
        val saved = TranslationPlanStore.saveAs(
            context,
            mode,
            "直播方案",
            originalDraft.copy(scenePresetId = "livestream"),
        )

        val updated = TranslationPlanStore.updateSaved(
            context,
            mode,
            saved.id,
            "动漫方案",
            saved.plan.copy(scenePresetId = "anime"),
        )

        assertNotNull(updated)
        val all = TranslationPlanStore.listSaved(context, mode)
        assertEquals(1, all.size)
        assertEquals(saved.id, all.single().id)
        assertEquals("动漫方案", all.single().name)
        assertEquals("anime", all.single().plan.scenePresetId)
        assertEquals(originalDraft, TranslationPlanStore.loadDraft(context, mode))
    }

    @Test
    fun missingSceneReferencesResolveAtUseTimeWithoutChangingStoredIds() {
        clearStore()
        val mode = TranslationMode.VIDEO
        val custom = requireNotNull(SceneLibraryStore.create(context, mode, "临时", "临时场景"))
        val plan = TranslationPlan.default(mode).copy(scenePresetId = custom.id)
        TranslationPlanStore.saveDraft(context, plan)
        val saved = TranslationPlanStore.saveAs(context, mode, "临时方案", plan)

        SceneLibraryStore.delete(context, mode, custom.id)

        val draft = TranslationPlanStore.loadDraft(context, mode)
        val stored = TranslationPlanStore.listSaved(context, mode).single { it.id == saved.id }.plan
        val fallbackId = SceneLibraryStore.default(context, mode).id
        assertEquals(custom.id, draft.scenePresetId)
        assertEquals(custom.id, stored.scenePresetId)
        assertEquals(fallbackId, SceneLibraryStore.resolve(context, mode, draft.scenePresetId).id)
        assertEquals(fallbackId, SceneLibraryStore.resolve(context, mode, stored.scenePresetId).id)
    }

    @Test
    fun fallbackDoesNotRewriteReferencesDuringUnrelatedCrud() {
        clearStore()
        val mode = TranslationMode.VIDEO
        val original = TranslationPlan.default(mode).copy(scenePresetId = "anime")
        TranslationPlanStore.saveDraft(context, original)
        val saved = TranslationPlanStore.saveAs(context, mode, "动漫方案", original)

        SceneLibraryStore.delete(context, mode, "anime")
        val fallbackId = SceneLibraryStore.default(context, mode).id
        assertEquals("anime", TranslationPlanStore.loadDraft(context, mode).scenePresetId)
        assertEquals("anime", TranslationPlanStore.listSaved(context, mode).single().plan.scenePresetId)
        assertEquals(
            fallbackId,
            SceneLibraryStore.resolve(context, mode, original.scenePresetId).id,
        )

        val renamed = TranslationPlanStore.updateSaved(
            context,
            mode,
            saved.id,
            "动漫方案（改名）",
            TranslationPlanStore.listSaved(context, mode).single { it.id == saved.id }.plan,
        )
        assertEquals("anime", renamed?.plan?.scenePresetId)
        TranslationPlanStore.saveAs(context, mode, "无关方案", TranslationPlan.default(mode))
        SceneLibraryStore.reset(context, mode)

        assertEquals("anime", TranslationPlanStore.loadDraft(context, mode).scenePresetId)
        assertEquals(
            "anime",
            TranslationPlanStore.listSaved(context, mode).single { it.id == saved.id }.plan.scenePresetId,
        )
    }

    @Test
    fun applyingSavedPlanKeepsCurrentLanguageAndOnlyTakesSceneAndPrompt() {
        clearStore()
        val mode = TranslationMode.VIDEO
        // 当前草稿使用日→中；用户随时可调的语言。
        val currentDraft = TranslationPlan.default(mode).copy(
            sourceLanguageCode = "ja",
            targetLanguageCode = "zh",
        )
        TranslationPlanStore.saveDraft(context, currentDraft)
        // 一个存了不同语言（英→中）的方案。
        val saved = TranslationPlanStore.saveAs(
            context,
            mode,
            "英文直播",
            TranslationPlan.default(mode).copy(
                sourceLanguageCode = "en",
                targetLanguageCode = "zh",
                scenePresetId = "livestream",
                advancedInstruction = "保留主播口头禅",
            ),
        )

        val applied = requireNotNull(TranslationPlanStore.applySaved(context, mode, saved.id))

        // 语言保留当前草稿（日→中），不被方案里的英文覆盖。
        assertEquals("ja", applied.sourceLanguageCode)
        assertEquals("zh", applied.targetLanguageCode)
        // 场景与长期提示词按方案套用。
        assertEquals("livestream", applied.scenePresetId)
        assertEquals("保留主播口头禅", applied.advancedInstruction)
        // 写回草稿的也保持当前语言。
        val draft = TranslationPlanStore.loadDraft(context, mode)
        assertEquals("ja", draft.sourceLanguageCode)
        assertEquals("livestream", draft.scenePresetId)
    }

    @Test
    fun updatingMissingSavedPlanReturnsNullWithoutChangingStore() {
        clearStore()
        val mode = TranslationMode.INTERPRETATION
        val draft = TranslationPlan.default(mode)
        TranslationPlanStore.saveDraft(context, draft)

        val updated = TranslationPlanStore.updateSaved(
            context,
            mode,
            "missing-id",
            "不存在的方案",
            draft.copy(scenePresetId = "meeting"),
        )

        assertNull(updated)
        assertEquals(emptyList<SavedTranslationPlan>(), TranslationPlanStore.listSaved(context, mode))
        assertEquals(draft, TranslationPlanStore.loadDraft(context, mode))
    }
}
